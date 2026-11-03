import json
import model_json_to_kt
import numpy as np
import onnxruntime as ort
import pigments
import sys
import time
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
        out = self.net(x_encoded)
        if not self.training:
            return torch.clamp(out, 0.0, 1.0)
        return out

def _oetf_srgb_torch(x):
    x = torch.clamp(x, 0.0, 1.0)
    return torch.where(
        x <= 0.0031308,
        12.92 * x,
        1.055 * torch.pow(torch.clamp(x, min=0.0031308), 1.0 / 2.4) - 0.055
    )

def _ste_clamp_01(raw_pred, y_target):
    """
    Target-aware straight-through clamp to [0, 1] matching ColorToPigmentsModel.kt:
    allows negative pre-activations on c_i = 0 faces (and >1 pre-activations on c_i = 1)
    at zero loss without killing corrective gradients when raw_pred is on the wrong side.
    """
    clamped = torch.clamp(raw_pred, 0.0, 1.0)
    in_range = (raw_pred >= 0.0).to(raw_pred.dtype) * (raw_pred <= 1.0).to(raw_pred.dtype)
    need_up = (raw_pred < 0.0).to(raw_pred.dtype) * (y_target > 0.0).to(raw_pred.dtype)
    need_dn = (raw_pred > 1.0).to(raw_pred.dtype) * (y_target < 1.0).to(raw_pred.dtype)
    pass_g = torch.clamp(in_range + need_up + need_dn, max=1.0)
    eff = raw_pred * pass_g
    return eff + (clamped - eff).detach()

def _mix_c3_torch(pg, c3):
    c_sum = c3.sum(dim=-1, keepdim=True)
    c4 = torch.cat([c3, torch.clamp(1.0 - c_sum, min=0.0)], dim=-1)
    K = pg.P_star_K.to(device=c3.device, dtype=c3.dtype)
    S = pg.P_star_S.to(device=c3.device, dtype=c3.dtype)
    return torch.clamp(pg.mix_torch(c4, K, S), 0.0, 1.0)

def _build_boundary_indices(device):
    coords = torch.arange(256)
    gr, gg, gb = torch.meshgrid(coords, coords, coords, indexing="ij")
    flat_idx = torch.arange(256 * 256 * 256)
    on_0_255_r = ((gr == 0) | (gr == 255)).reshape(-1)
    on_0_255_g = ((gg == 0) | (gg == 255)).reshape(-1)
    on_0_255_b = ((gb == 0) | (gb == 255)).reshape(-1)
    num_ext = on_0_255_r.int() + on_0_255_g.int() + on_0_255_b.int()

    near_r = ((gr <= 2) | (gr >= 253)).reshape(-1)
    near_g = ((gg <= 2) | (gg >= 253)).reshape(-1)
    near_b = ((gb <= 2) | (gb >= 253)).reshape(-1)
    corner_box_idx = flat_idx[(near_r.int() + near_g.int() + near_b.int()) == 3].to(device)
    edge_idx = flat_idx[num_ext >= 2].to(device)
    face_idx = flat_idx[num_ext >= 1].to(device)
    high_z_idx = flat_idx[(gb >= 205).reshape(-1)].to(device)
    return corner_box_idx, edge_idx, face_idx, high_z_idx

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

    if getattr(data_config, "model_path_kotlin", None):
        model_json_to_kt.convert_json_to_kotlin(
            data_config.model_path_json,
            data_config.model_path_kotlin
        )

def evaluate_global_accuracy(model, dataset_tensor, pg=None, batch_size=262_144):
    device = dataset_tensor.device
    if pg is None:
        pg = pigments.Pigments(pigments.load_config_from_json('config.json'))
    was_training = model.training
    model.eval()
    with torch.no_grad():
        coords = torch.arange(256, dtype=torch.float32, device=device) / 255.0
        grid_r, grid_g, grid_b = torch.meshgrid(coords, coords, coords, indexing='ij')
        flat_inputs = torch.stack([grid_r, grid_g, grid_b], dim=-1).reshape(-1, 3)
        flat_targets = dataset_tensor.reshape(-1, 3)

        num_samples = flat_inputs.shape[0]
        max_c_err = 0.0
        sum_c_err = 0.0
        max_lin_err = 0.0
        sum_lin_err = 0.0
        max_srgb_err = 0.0
        sum_srgb_err = 0.0
        max_sum_excess = 0.0

        for start in range(0, num_samples, batch_size):
            x_test = flat_inputs[start:start + batch_size]
            truth = flat_targets[start:start + batch_size]
            predictions = model(x_test)

            c_errors = torch.abs(predictions - truth)
            max_c_err = max(max_c_err, torch.max(c_errors).item())
            sum_c_err += torch.mean(c_errors).item() * x_test.shape[0]

            pred_lin = _mix_c3_torch(pg, predictions)
            gt_lin = _mix_c3_torch(pg, truth)
            lin_errors = torch.abs(pred_lin - gt_lin)
            max_lin_err = max(max_lin_err, torch.max(lin_errors).item())
            sum_lin_err += torch.mean(lin_errors).item() * x_test.shape[0]

            srgb_errors = torch.abs(_oetf_srgb_torch(pred_lin) - _oetf_srgb_torch(gt_lin))
            max_srgb_err = max(max_srgb_err, torch.max(srgb_errors).item())
            sum_srgb_err += torch.mean(srgb_errors).item() * x_test.shape[0]

            sum_excess = torch.relu(predictions.sum(dim=-1) - 1.0)
            max_sum_excess = max(max_sum_excess, torch.max(sum_excess).item())

        mean_c_err = sum_c_err / num_samples
        mean_lin_err = sum_lin_err / num_samples
        mean_srgb_err = sum_srgb_err / num_samples
        print(
            f"Forward Remainder (Linear RGB) Max Error: {max_lin_err:.6f} | Mean Error: {mean_lin_err:.6f}\n"
            f"Forward sRGB Max Error:                   {max_srgb_err:.6f} | Mean Error: {mean_srgb_err:.6f}\n"
            f"Simplex Sum Excess Max (sum(c) - 1):      {max_sum_excess:.6f}"
        )
        if was_training:
            model.train()
        return max_c_err, mean_c_err

def _train_forward_aware(model, dataset_tensor, pg, total_steps=55_000):
    t0 = time.time()
    device = dataset_tensor.device
    if pg is None:
        pg = pigments.Pigments(pigments.load_config_from_json('config.json'))

    coords = torch.arange(256, dtype=torch.float32, device=device) / 255.0
    grid_r, grid_g, grid_b = torch.meshgrid(coords, coords, coords, indexing='ij')
    flat_inputs = torch.stack([grid_r, grid_g, grid_b], dim=-1).reshape(-1, 3)
    flat_targets = dataset_tensor.reshape(-1, 3)
    num_samples = flat_inputs.shape[0]

    # Precompute forward Kubelka-Munk linear RGB targets across all 256^3 voxels
    flat_gt_lin = torch.empty_like(flat_targets)
    with torch.no_grad():
        for s in range(0, num_samples, 262_144):
            flat_gt_lin[s:s + 262_144] = _mix_c3_torch(pg, flat_targets[s:s + 262_144])

    corner_idx, edge_idx, face_idx, high_z_idx = _build_boundary_indices(device)

    w_c = 1.25
    w_lin = 1.40
    w_srgb = 0.85
    w_sum = 4.00

    optimizer = optim.Adam(model.parameters(), lr=3e-3)
    scheduler = optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=total_steps, eta_min=1e-5)

    def mine_subset(pool_size=1_048_576, k_out=8_192):
        with torch.no_grad():
            p_idx = torch.cat([
                torch.randint(0, num_samples, (pool_size,), device=device),
                corner_idx,
                edge_idx
            ], dim=0)
            xp = flat_inputs[p_idx]
            yp = flat_targets[p_idx]
            glin = flat_gt_lin[p_idx]
            raw = model(xp)
            pred = torch.clamp(raw, 0.0, 1.0)
            plin = _mix_c3_torch(pg, pred)
            ec = torch.abs(pred - yp).max(dim=-1).values
            elin = torch.abs(plin - glin).max(dim=-1).values
            esrgb = torch.abs(_oetf_srgb_torch(plin) - _oetf_srgb_torch(glin)).max(dim=-1).values
            esum = torch.relu(pred.sum(dim=-1) - 1.0)
            score = torch.maximum(
                torch.maximum(w_c * ec, w_lin * elin),
                torch.maximum(w_srgb * esrgb, w_sum * esum)
            )
            top = torch.topk(score, k_out).indices
            return p_idx[top]

    def evaluate_and_mine_full(k_out=8_192):
        with torch.no_grad():
            scores = torch.empty(num_samples, dtype=torch.float32, device=device)
            max_dc = 0.0
            max_dlin = 0.0
            max_dsrgb = 0.0
            max_dsum = 0.0
            for s in range(0, num_samples, 262_144):
                e = min(s + 262_144, num_samples)
                x = flat_inputs[s:e]
                y = flat_targets[s:e]
                gl = flat_gt_lin[s:e]
                pr = torch.clamp(model(x), 0.0, 1.0)
                pl = _mix_c3_torch(pg, pr)
                ec = torch.abs(pr - y).max(dim=-1).values
                el = torch.abs(pl - gl).max(dim=-1).values
                es = torch.abs(_oetf_srgb_torch(pl) - _oetf_srgb_torch(gl)).max(dim=-1).values
                esum = torch.relu(pr.sum(dim=-1) - 1.0)
                max_dc = max(max_dc, ec.max().item())
                max_dlin = max(max_dlin, el.max().item())
                max_dsrgb = max(max_dsrgb, es.max().item())
                max_dsum = max(max_dsum, esum.max().item())
                scores[s:e] = torch.maximum(
                    torch.maximum(w_c * ec, w_lin * el),
                    torch.maximum(w_srgb * es, w_sum * esum)
                )
            top_idx = torch.topk(scores, k_out).indices
            return top_idx, scores.max().item(), max_dc, max_dlin, max_dsrgb, max_dsum

    model.train()
    hard_idx = mine_subset(1_048_576, 8_192)
    best_loss = float('inf')
    best_global_score = float('inf')
    best_state_dict = {k: v.detach().clone() for k, v in model.state_dict().items()}

    for step in range(1, total_steps + 1):
        if step % 500 == 0 and step % 5_000 != 0:
            hard_idx = mine_subset(1_048_576, 8_192)

        i_uni = torch.randint(0, num_samples, (12_288,), device=device)
        i_face = face_idx[torch.randint(0, face_idx.numel(), (1_536,), device=device)]
        i_edge = edge_idx[torch.randint(0, edge_idx.numel(), (512,), device=device)]
        i_hz = high_z_idx[torch.randint(0, high_z_idx.numel(), (1_024,), device=device)]
        i_hard = hard_idx[torch.randint(0, hard_idx.numel(), (4_904,), device=device)]
        b_idx = torch.cat([i_uni, i_face, i_edge, i_hz, i_hard, corner_idx], dim=0)

        xb = flat_inputs[b_idx]
        yb = flat_targets[b_idx]
        glin = flat_gt_lin[b_idx]

        optimizer.zero_grad()
        raw = model(xb)
        pred = _ste_clamp_01(raw, yb)
        plin = _mix_c3_torch(pg, pred)

        dc = pred - yb
        dlin = plin - glin
        dsrgb = _oetf_srgb_torch(plin) - _oetf_srgb_torch(glin)
        dsum = torch.relu(pred.sum(dim=-1, keepdim=True) - 1.0)
        tether = torch.relu(-raw - 0.05) ** 2 + torch.relu(raw - 1.05) ** 2

        res = torch.cat([w_c * dc, w_lin * dlin, w_srgb * dsrgb, w_sum * dsum], dim=-1)
        sq_error = res * res
        mse_loss = torch.mean(sq_error)
        per_sample_sq_error = torch.max(sq_error, dim=-1).values
        topk_loss = torch.mean(torch.topk(per_sample_sq_error, k=64).values)
        l4_loss = torch.mean(sq_error * sq_error)
        loss = mse_loss + 0.15 * topk_loss + 12.0 * l4_loss + 1e-3 * torch.mean(tether)

        loss.backward()
        nn.utils.clip_grad_norm_(model.parameters(), max_norm=1.0)
        optimizer.step()
        scheduler.step()

        best_loss = min(best_loss, mse_loss.item())

        if step == 1 or step % 5_000 == 0 or step == total_steps:
            hard_idx, g_score, mdc, mdlin, mdsrgb, mdsum = evaluate_and_mine_full(8_192)
            if g_score < best_global_score:
                best_global_score = g_score
                best_state_dict = {k: v.detach().clone() for k, v in model.state_dict().items()}
            print(
                f"Step {step:6,d}/{total_steps:,d} | LR: {optimizer.param_groups[0]['lr']:.2e} | "
                f"Global Max dC: {mdc:.4f} | Max dLin: {mdlin:.4f} | "
                f"Max dSRGB: {mdsrgb:.4f} | Max sum-1: {mdsum:.4f} ({time.time() - t0:.1f}s)"
            )

    model.load_state_dict(best_state_dict)
    return best_loss

def train_full(model, dataset_tensor, pg=None):
    return _train_forward_aware(model, dataset_tensor, pg, total_steps=55_000)

def train(data_config, dataset):
    device = get_device()
    print(f"Using device: {device}")

    torch.manual_seed(42)
    model = PigmentsMLP().to(device)
    print(f"Model parameters: {sum(p.numel() for p in model.parameters())}")

    pg = pigments.Pigments(pigments.load_config_from_json('config.json'))
    dataset_tensor = torch.tensor(dataset, dtype=torch.float32, device=device)

    print("Training mode: Full dataset (forward Kubelka-Munk + concentration minimax)")
    best_loss = train_full(model, dataset_tensor, pg)

    max_error, mean_error = evaluate_global_accuracy(model, dataset_tensor, pg)
    print(f"Final Training Loss: {best_loss:.8f}")
    print(f"Global Concentration Max Error: {max_error:.6f} | Global Mean Error: {mean_error:.6f}")

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

                y_pred = _ste_clamp_01(model(x_train), y_target)

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
