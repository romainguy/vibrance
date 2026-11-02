import json
import numpy as np
import onnxruntime as ort
import pigments
import sys
import torch
import torch.nn as nn
import torch.optim as optim

def get_device():
    if torch.backends.mps.is_available():
        return torch.device("mps")
    elif torch.cuda.is_available():
        return torch.device("cuda")
    else:
        print("⚠️ Running PyTorch on CPU")
        return torch.device("cpu")

class PositionalEncoding(nn.Module):
    def __init__(self, num_frequencies=4):
        super().__init__()
        self.num_frequencies = num_frequencies
        # Create frequency bands: 2^0, 2^1, ..., 2^(L-1)
        bands = 2.0 ** torch.linspace(0, num_frequencies - 1, num_frequencies)
        self.register_buffer("bands", bands, persistent=False)

    def forward(self, x):
        # Baseline
        angles = x.unsqueeze(-2) * (self.bands.unsqueeze(-1) * torch.pi)
        trig = torch.cat([torch.sin(angles), torch.cos(angles)], dim=-1)
        return torch.cat([x, trig.flatten(-2)], dim=-1)

class PigmentsMLP(nn.Module):
    def __init__(self, num_frequencies=4, hidden_dim=32):
        super().__init__()
        self.positional_encoding = PositionalEncoding(num_frequencies)

        # Calculate input dimension: 
        # 3 (raw RGB) + (3 channels * 2 (sin/cos) * num_frequencies)
        in_dim = 3 + (3 * 2 * num_frequencies)

        self.net = nn.Sequential(
            nn.Linear(in_dim, hidden_dim),
            nn.ReLU(),
            nn.Linear(hidden_dim, hidden_dim),
            nn.ReLU(),
            nn.Linear(hidden_dim, 3)
        )

    def forward(self, x):
        x_encoded = self.positional_encoding(x)
        return self.net(x_encoded)

def export(data_config, model):
    model = model.cpu()
    model.eval()

    # Export to ONNX
    dummy_input = torch.tensor([[0.5, 0.5, 0.5], [0.5, 0.5, 0.5]], dtype=torch.float32)
    torch.onnx.export(
        model,
        dummy_input,
        data_config.model_path_onnx,
        export_params=True,
        input_names=['rgb_input'],
        output_names=['latent_out'],
        dynamic_shapes={"x": {0: "batch_size"}},
        dynamo=True
    )
    print(f"Exported to ONNX format: {data_config.model_path_onnx}")

    # Export to JSON
    export_data = {}
    export_data["positional_encoding_frequencies"] = model.positional_encoding.bands.detach().cpu().numpy().tolist()
    for name, param in model.named_parameters():
        export_data[name] = param.detach().cpu().numpy().tolist()
    with open(data_config.model_path_json, "w") as f:
        json.dump(export_data, f)        
    print(f"Exported to JSON format: {data_config.model_path_json}")

def evaluate_global_accuracy(model, dataset_tensor, batch_size=262_144):
    device = dataset_tensor.device
    model.eval()
    with torch.no_grad():
        coords = torch.arange(256, dtype=torch.float32, device=device) / 255.0
        grid_r, grid_g, grid_b = torch.meshgrid(coords, coords, coords, indexing='ij')
        flat_inputs = torch.stack([grid_r, grid_g, grid_b], dim=-1).reshape(-1, 3)
        flat_targets = dataset_tensor.reshape(-1, 3)

        num_samples = flat_inputs.shape[0]
        max_error = torch.zeros((), device=device)
        weighted_mean_error = torch.zeros((), device=device)

        for start in range(0, num_samples, batch_size):
            x_test = flat_inputs[start:start + batch_size]
            truth = flat_targets[start:start + batch_size]
            predictions = model(x_test)
            errors = torch.abs(predictions - truth)
            max_error = torch.maximum(max_error, torch.max(errors))
            weighted_mean_error += torch.mean(errors) * x_test.shape[0]

        mean_error = (weighted_mean_error / num_samples).item()
        model.train()
        return max_error.item(), mean_error

def train_ohem(model, dataset_tensor):
    device = dataset_tensor.device
    epochs = 100_000
    batch_size = 8_192

    optimizer = optim.Adam(model.parameters(), lr=1e-3)
    criterion = nn.MSELoss()
    scheduler = optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=epochs)

    targeted_batch = int(batch_size * 0.1)                # 10% strictly Z > 220
    ohem_batch = int(batch_size * 0.2)                    # 20% dynamic hard examples
    rand_batch = batch_size - targeted_batch - ohem_batch # 70% uniform random

    best_loss = torch.tensor(float('inf'), device=device)
    max_error = torch.zeros((), device=device)
    
    for epoch in range(epochs):
        optimizer.zero_grad()
        
        # Uniform random batch
        random_indices = torch.randint(0, 256, (rand_batch, 3), device=device)

        # OHEM batch
        model.eval()
        with torch.no_grad():
            large_pool_indices = torch.randint(0, 256, (65_536, 3), device=device)
            x_test = large_pool_indices.float() / 255.0
            truth = dataset_tensor[
                large_pool_indices[:, 0], 
                large_pool_indices[:, 1], 
                large_pool_indices[:, 2]
            ]

            predictions = model(x_test)

            errors = torch.max(torch.abs(predictions - truth), dim=1).values
            max_error = errors.max()

            worst_indices = torch.topk(errors, ohem_batch).indices
            hard_indices = large_pool_indices[worst_indices]
        model.train()

        # Targeted batch
        x_targeted = torch.randint(0, 256, (targeted_batch, 1), device=device)
        y_targeted = torch.randint(0, 256, (targeted_batch, 1), device=device)
        z_targeted = torch.randint(210, 256, (targeted_batch, 1), device=device)
        targeted_indices = torch.cat([x_targeted, y_targeted, z_targeted], dim=1)

        combined_indices = torch.cat([random_indices, hard_indices, targeted_indices], dim=0)

        x_train = combined_indices.float() / 255.0

        y_target = dataset_tensor[
            combined_indices[:, 0], 
            combined_indices[:, 1], 
            combined_indices[:, 2]
        ]
        
        y_prediction = model(x_train)

        loss = criterion(y_prediction, y_target)
        loss.backward()

        nn.utils.clip_grad_norm_(model.parameters(), max_norm=1.0)

        optimizer.step()
        scheduler.step()

        best_loss = torch.minimum(best_loss, loss.detach())

        if epoch % 1000 == 0:
            print(
                f"Epoch {epoch} | LR: {optimizer.param_groups[0]['lr']:.2e} | "
                f"Best Loss: {best_loss.item():.8f} | Max Error: {max_error.item():.4f}"
            )

    return best_loss.item()

def train_full(model, dataset_tensor):
    device = dataset_tensor.device
    epochs = 1_500
    batch_size = 131_072
    topk_count = 64

    optimizer = optim.Adam(model.parameters(), lr=3e-3)

    coords = torch.arange(256, dtype=torch.float32, device=device) / 255.0
    grid_r, grid_g, grid_b = torch.meshgrid(coords, coords, coords, indexing='ij')
    flat_inputs = torch.stack([grid_r, grid_g, grid_b], dim=-1).reshape(-1, 3)
    flat_targets = dataset_tensor.reshape(-1, 3)

    num_samples = flat_inputs.shape[0]
    num_batches = (num_samples + batch_size - 1) // batch_size

    scheduler = optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=epochs * num_batches)

    best_loss = torch.tensor(float('inf'), device=device)
    best_max_error = float('inf')
    best_state_dict = None

    for epoch in range(epochs):
        permutation = torch.randperm(num_samples, device=device)
        shuffled_inputs = flat_inputs[permutation]
        shuffled_targets = flat_targets[permutation]
        epoch_loss = torch.zeros((), device=device)
        max_error_sq = torch.zeros((), device=device)

        for start in range(0, num_samples, batch_size):
            optimizer.zero_grad()

            x_train = shuffled_inputs[start:start + batch_size]
            y_target = shuffled_targets[start:start + batch_size]

            y_prediction = model(x_train)

            diff = y_prediction - y_target
            sq_error = diff * diff
            mse_loss = torch.mean(sq_error)
            per_sample_sq_error = torch.max(sq_error, dim=-1).values
            topk_loss = torch.mean(torch.topk(per_sample_sq_error, k=topk_count).values)
            l4_loss = torch.mean(sq_error * sq_error)
            loss = mse_loss + 0.5 * topk_loss + 10.0 * l4_loss

            loss.backward()

            nn.utils.clip_grad_norm_(model.parameters(), max_norm=1.0)

            optimizer.step()
            scheduler.step()

            with torch.no_grad():
                epoch_loss += mse_loss
                max_error_sq = torch.maximum(max_error_sq, torch.max(per_sample_sq_error))

        current_loss = epoch_loss / num_batches
        best_loss = torch.minimum(best_loss, current_loss)

        epoch_max_error = torch.sqrt(max_error_sq).item()
        if epoch_max_error < best_max_error:
            best_max_error = epoch_max_error
            best_state_dict = {k: v.detach().clone() for k, v in model.state_dict().items()}

        if epoch % 20 == 0:
            print(
                f"Epoch {epoch} | LR: {optimizer.param_groups[0]['lr']:.2e} | "
                f"Best MSE: {best_loss.item():.8f} | Max Error: {epoch_max_error:.4f} | "
                f"Best Max Error: {best_max_error:.4f}"
            )

    if best_state_dict is not None:
        model.load_state_dict(best_state_dict)

    return best_loss.item()

def train(data_config, dataset):
    device = get_device()
    print(f"Using device: {device}")

    model = PigmentsMLP().to(device)
    print(f"Model parameters: {sum(p.numel() for p in model.parameters())}")

    dataset_tensor = torch.tensor(dataset, dtype=torch.float32, device=device)

    if data_config.use_ohem:
        print("Training mode: OHEM")
        best_loss = train_ohem(model, dataset_tensor)
    else:
        print("Training mode: Full dataset")
        best_loss = train_full(model, dataset_tensor)

    max_error, mean_error = evaluate_global_accuracy(model, dataset_tensor)
    print(f"Final Training Loss: {best_loss:.8f}")
    print(f"Global Max Error: {max_error:.6f} | Global Mean Error: {mean_error:.6f}")

    export(data_config, model)

def calculate_operations(num_freqs, hidden_dimension):
    input_dimension = 3 + (3 * 2 * num_freqs)
    ops_layer1 = input_dimension * hidden_dimension
    ops_layer2 = hidden_dimension * hidden_dimension
    ops_layer3 = hidden_dimension * 3
    trig_ops = 3 * 2 * num_freqs
    return trig_ops + ops_layer1 + ops_layer2 + ops_layer3

def automated_search(dataset_tensor):
    device = get_device()
    print(f"Using device: {device}")
    dataset_tensor = dataset_tensor.to(device)

    frequencies_to_test = [4, 6]
    hidden_dims_to_test = [8, 16, 32]
    
    results = []
    
    for freqs in frequencies_to_test:
        for hidden in hidden_dims_to_test:
            print(f"Training Model: {freqs} Freqs, {hidden} Hidden Neurons...")
            
            model = PigmentsMLP(num_frequencies=freqs, hidden_dim=hidden).to(device)
            optimizer = optim.Adam(model.parameters(), lr=2e-3)
            criterion = nn.MSELoss()

            best_loss = torch.tensor(float('inf'), device=device)

            model.train()
            for _ in range(40_000):
                optimizer.zero_grad()
                random_indices = torch.randint(0, 256, (8_192, 3), device=device)

                x_train = random_indices.float() / 255.0

                y_target = dataset_tensor[
                    random_indices[:, 0], 
                    random_indices[:, 1], 
                    random_indices[:, 2]
                ]

                y_pred = model(x_train)

                loss = criterion(y_pred, y_target)
                loss.backward()
                optimizer.step()

                best_loss = torch.minimum(best_loss, loss.detach())
                
            model.eval()
            with torch.no_grad():
                test_indices = torch.randint(0, 256, (65_536, 3), device=device)
                x_test = test_indices.float() / 255.0
                y_test = dataset_tensor[test_indices[:, 0], test_indices[:, 1], test_indices[:, 2]]
                preds = model(x_test)
                max_error = torch.max(torch.abs(preds - y_test)).item()
                
            ops = calculate_operations(freqs, hidden)
            results.append({
                "freqs": freqs, 
                "hidden": hidden, 
                "max_error": max_error, 
                "math_ops": ops,
                "best_loss": best_loss.item()
            })
            
    print(f"{'Freqs':<8} | {'Hidden':<8} | {'Math Ops':<10} | {'Max Error':<9} | {'Best Loss'}")
    print("-" * 60)

    results.sort(key=lambda x: x["math_ops"])
    for r in results:
        print(f"{r['freqs']:<8} | {r['hidden']:<8} | {r['math_ops']:<10} | {r['max_error']:.7f} | {r['best_loss']:.6f}")

def query_single_color(r, g, b, data_config, dataset):
    print(f"Loading model: {data_config.model_path_onnx}...")

    session = ort.InferenceSession(data_config.model_path_onnx)
    input_name = session.get_inputs()[0].name
    output_name = session.get_outputs()[0].name

    input_data = np.array([[r / 255.0, g / 255.0, b / 255.0]], dtype=np.float32)
    results = session.run([output_name], {input_name: input_data})
    latent_vector = results[0][0]

    truth = dataset[r, g, b]
    
    difference = np.abs(latent_vector - truth)
    
    print(f">> RGB({r}, {g}, {b})")
    print(f"Predicted Latent: {latent_vector}")
    print(f"Actual Latent:    {truth}")
    print(f"Absolute Error:   {difference}")

if __name__ == "__main__":
    if len(sys.argv) == 1:
        print("python3 color_to_pigments.py [train|query|autosearch]")
        sys.exit(0)

    command = sys.argv[1]

    data_config = pigments.load_data_config_from_json('data_config.json')
    dataset = np.load(data_config.lut_path_npy).astype(np.float32)

    if command == "train":
        train(data_config, dataset)
    elif command == "query":
        if len(sys.argv) != 5:
            print("Usage: python3 color_to_pigments.py query r g b")
            sys.exit(1)
        r, g, b = map(int, sys.argv[2:5])
        query_single_color(r, g, b, data_config, dataset)
    elif command == "autosearch":
        dataset_tensor = torch.tensor(dataset, dtype=torch.float32)
        automated_search(dataset_tensor)
