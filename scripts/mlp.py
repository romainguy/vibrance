import json
from xml.parsers.expat import model
import numpy as np
import onnxruntime as ort
import sys
import torch
import torch.nn as nn
import torch.optim as optim
from torch.utils.mobile_optimizer import optimize_for_mobile

model_path_onnx = "data/pigments_mlp.onnx"
model_path_json = "data/pigments_mlp.json"
lut_path_npy = 'data/pigments_lut_256_fp16.npy'

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

def export(model):
    model.eval()

    # Export to ONNX
    dummy_input = torch.tensor([[0.5, 0.5, 0.5]], dtype=torch.float32)
    torch.onnx.export(
        model,
        dummy_input,
        model_path_onnx,
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
    print(f"Exported to ONNX format: {model_path_onnx}")

    # Export to JSON
    export_data = {}
    export_data["pe_freqs"] = model.pe.freq_bands.detach().cpu().numpy().tolist()
    for name, param in model.named_parameters():
        export_data[name] = param.detach().cpu().numpy().tolist()
    with open(model_path_json, "w") as f:
        json.dump(export_data, f)        
    print(f"Exported raw matrix weights to: {model_path_json}")

def train(dataset):
    model = TinyMLP(num_frequencies=6, hidden_dim=32)
    print(f"Model parameters: {sum(p.numel() for p in model.parameters())}")

    dataset_tensor = torch.tensor(dataset, dtype=torch.float32)

    optimizer = optim.Adam(model.parameters(), lr=1e-3)
    criterion = nn.MSELoss()

    # TODO: We should probably used a learning rate scheduler to handle the
    # loss plateauing after a few thousand epochs. For now, we'll just run
    # for a fixed number of epochs and hope for the best.
    batch_size = 8192
    epochs = 500_000
    loss_threshold = 3.3e-6
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

        if loss.item() < best_loss:
            best_loss = loss.item()
        if loss.item() < loss_threshold:
            print(f"Early stopping at epoch {epoch} with loss {loss.item():.8f}")
            break
        
        if epoch % 500 == 0:
            print(f"Epoch {epoch} | Best loss: {best_loss:.8f}")

    print(f"Final training loss: {best_loss:.8f}")
    export(model)

def query_single_color(r, g, b, dataset):
    print(f"Loading model: {model_path_onnx}...")

    session = ort.InferenceSession(model_path_onnx)
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

    dataset = np.load(lut_path_npy).astype(np.float32)

    if command == "train":
        train(dataset)
    elif command == "query":
        if len(sys.argv) != 5:
            print("Usage: python3 mlp.py query r g b")
            sys.exit(1)
        r, g, b = map(int, sys.argv[2:5])
        query_single_color(r, g, b, dataset)
