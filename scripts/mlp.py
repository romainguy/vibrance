import json
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
        # x is the RGB input of shape (Batch, 3)
        encoded = [x] # We include the raw RGB inputs as a baseline
        for freq in self.freq_bands:
            encoded.append(torch.sin(x * freq * torch.pi))
            encoded.append(torch.cos(x * freq * torch.pi))
            
        return torch.cat(encoded, dim=-1)

class TinyMLP(nn.Module):
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
        # dynamo=False,
        # dynamic_axes={
        #     'rgb_input': {0: 'batch_size'},
        #     'latent_out': {0: 'batch_size'}
        # }
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

def train(data_config, dataset):
    model = TinyMLP(num_frequencies=6, hidden_dim=32)
    print(f"Model parameters: {sum(p.numel() for p in model.parameters())}")

    dataset_tensor = torch.tensor(dataset, dtype=torch.float32)

    optimizer = optim.Adam(model.parameters(), lr=2e-3)
    criterion = nn.MSELoss()

    scheduler = optim.lr_scheduler.ReduceLROnPlateau(
        optimizer, mode='min', factor=0.5, patience=40_000
    )

    batch_size = 8_192
    epochs = 1_000_000
    loss_threshold = 3.1e-6
    best_loss = float('inf')

    for epoch in range(epochs):
        optimizer.zero_grad()
        
        random_indices = torch.randint(0, 256, (batch_size, 3))

        # Normalize the RGB inputs to [0.0, 1.0] for the Neural Network
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

        current_loss = loss.item()
        scheduler.step(current_loss)

        if current_loss < best_loss:
            best_loss = current_loss
        if current_loss < loss_threshold:
            print(f"Early stopping at epoch {epoch} with loss {current_loss:.8f}")
            break
        
        if epoch % 500 == 0:
            current_lr = optimizer.param_groups[0]['lr']
            print(f"Epoch {epoch} | Best loss: {best_loss:.8f} | Learning rate: {current_lr:.2e}")

    print(f"Final training loss: {best_loss:.8f}")
    export(data_config, model)

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
        print("python3 mlp.py [train|query]")
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
