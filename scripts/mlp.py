import json
from pyexpat import model
import numpy as np
import onnxruntime as ort
import pigments
import sys
import torch
import torch.nn as nn
import torch.optim as optim

class PositionalEncoding(nn.Module):
    def __init__(self, num_frequencies=6):
        super().__init__()
        self.num_frequencies = num_frequencies
        # Create frequency bands: 2^0, 2^1, ..., 2^(L-1)
        self.freq_bands = 2.0 ** torch.linspace(0, num_frequencies - 1, num_frequencies)

    def forward(self, x):
        # Baseline
        encoded = [x]
        for freq in self.freq_bands:
            encoded.append(torch.sin(x * freq * torch.pi))
            encoded.append(torch.cos(x * freq * torch.pi))
            
        return torch.cat(encoded, dim=-1)

class PigmentsMLP(nn.Module):
    def __init__(self, num_frequencies=6, hidden_dim=32):
        super().__init__()
        self.pe = PositionalEncoding(num_frequencies)

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
        x_encoded = self.pe(x)
        return self.net(x_encoded)

def export(data_config, model):
    model.eval()

    # Export to ONNX
    dummy_input = torch.tensor([[0.5, 0.5, 0.5]], dtype=torch.float32)
    torch.onnx.export(
        model,
        dummy_input,
        data_config.model_path_onnx,
        export_params=True,
        input_names=['rgb_input'],
        output_names=['latent_out'],
        dynamic_shapes={"x": {0: "batch_size"}}
    )
    print(f"Exported to ONNX format: {data_config.model_path_onnx}")

    # Export to JSON
    export_data = {}
    export_data["pe_freqs"] = model.pe.freq_bands.detach().cpu().numpy().tolist()
    for name, param in model.named_parameters():
        export_data[name] = param.detach().cpu().numpy().tolist()
    with open(data_config.model_path_json, "w") as f:
        json.dump(export_data, f)        
    print(f"Exported raw matrix weights to: {data_config.model_path_json}")

def evaluate_global_accuracy(model, dataset_tensor, num_samples=65_536):
    model.eval()
    with torch.no_grad():
        indices = torch.randint(0, 256, (num_samples, 3))
        x_test = indices.float() / 255.0
        predictions = model(x_test)
        truth = dataset_tensor[indices[:, 0], indices[:, 1], indices[:, 2]]
        errors = torch.abs(predictions - truth)
        max_error = torch.max(errors).item()
        mean_error = torch.mean(errors).item()
        model.train()
        return max_error, mean_error

def train(data_config, dataset):
    model = PigmentsMLP()
    print(f"Model parameters: {sum(p.numel() for p in model.parameters())}")

    dataset_tensor = torch.tensor(dataset, dtype=torch.float32)

    epochs = 100_000
    batch_size = 8_192

    optimizer = optim.Adam(model.parameters(), lr=1e-3)
    criterion = nn.HuberLoss(delta=0.1)
    scheduler = optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=epochs)

    hard_batch = int(batch_size * 0.2)
    rand_batch = batch_size - hard_batch

    best_loss = float('inf')
    max_error = 0
    mean_error = 0

    for epoch in range(epochs):
        optimizer.zero_grad()
        
        random_indices = torch.randint(0, 256, (rand_batch, 3))

        model.eval()
        with torch.no_grad():
            large_pool_indices = torch.randint(0, 256, (65_536, 3))
            x_test = large_pool_indices.float() / 255.0
            truth = dataset_tensor[
                large_pool_indices[:, 0], 
                large_pool_indices[:, 1], 
                large_pool_indices[:, 2]
            ]

            predictions = model(x_test)
            
            errors = torch.mean(torch.abs(predictions - truth), dim=1)
            max_error = torch.max(errors).item()
            mean_error = torch.mean(errors).item()

            worst_indices = torch.topk(errors, hard_batch).indices
            hard_indices = large_pool_indices[worst_indices]
        model.train()

        combined_indices = torch.cat([random_indices, hard_indices], dim=0)

        # Normalize the RGB inputs to [0.0, 1.0] for the Neural Network
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

        current_loss = loss.item()
        if current_loss < best_loss:
            best_loss = current_loss

        if epoch % 1000 == 0:
            print(f"Epoch {epoch} | LR: {optimizer.param_groups[0]['lr']:.2e} | Best Loss: {best_loss:.8f} | Max Error: {max_error:.4f} | Mean Error: {mean_error:.4f}")

    max_error, mean_error = evaluate_global_accuracy(model, dataset_tensor)
    print(f"Final Training Loss: {best_loss:.8f}")
    print(f"Global Max Error: {max_error:.6f} | Global Mean Error: {mean_error:.6f}")

    export(data_config, model)

def calculate_operations(num_freqs, hidden_dim):
    in_dim = 3 + (3 * 2 * num_freqs)
    ops_layer1 = in_dim * hidden_dim
    ops_layer2 = hidden_dim * hidden_dim
    ops_layer3 = hidden_dim * 3
    trig_ops = 3 * 2 * num_freqs
    return trig_ops + ops_layer1 + ops_layer2 + ops_layer3

def automated_search(dataset_tensor):
    frequencies_to_test = [4, 6]
    hidden_dims_to_test = [8, 16, 32]
    
    results = []
    
    for freqs in frequencies_to_test:
        for hidden in hidden_dims_to_test:
            print(f"Training Model: {freqs} Freqs, {hidden} Hidden Neurons...")
            
            model = PigmentsMLP(num_frequencies=freqs, hidden_dim=hidden)
            optimizer = optim.Adam(model.parameters(), lr=2e-3)
            criterion = nn.MSELoss()

            best_loss = float('inf')

            model.train()
            for _ in range(80_000):
                optimizer.zero_grad()
                random_indices = torch.randint(0, 256, (8_192, 3))

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

                best_loss = min(best_loss, loss.item())
                
            model.eval()
            with torch.no_grad():
                test_indices = torch.randint(0, 256, (65_536, 3))
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
                "best_loss": best_loss
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
        print("python3 mlp.py [train|query|autosearch]")
        sys.exit(0)

    command = sys.argv[1]

    data_config = pigments.load_data_config_from_json('data_config.json')
    dataset = np.load(data_config.lut_path_npy).astype(np.float32)

    if command == "train":
        train(data_config, dataset)
    elif command == "query":
        if len(sys.argv) != 5:
            print("Usage: python3 mlp.py query r g b")
            sys.exit(1)
        r, g, b = map(int, sys.argv[2:5])
        query_single_color(r, g, b, data_config, dataset)
    elif command == "autosearch":
        dataset_tensor = torch.tensor(dataset, dtype=torch.float32)
        automated_search(dataset_tensor)
