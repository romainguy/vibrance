import math
import os
import re
import sys
import numpy as np
import pigments
import scipy.optimize as sp_opt
import torch
import torch.nn as nn
import torch.optim as optim

pg = pigments.Pigments(pigments.load_config_from_json("config.json"))

class PigmentsToColorMLP(nn.Module):
    def __init__(self, neurons=16):
        super().__init__()
        self.neurons = neurons
        # Learned half-saturation poles k_i > 0 for rational tinting features q_i = c_i / (c_i + k_i)
        self.log_k = nn.Parameter(torch.log(torch.tensor([0.032, 0.120, 0.250])))
        self.skip = nn.Linear(6, 3)
        self.hidden = nn.Linear(6, neurons)
        self.output = nn.Linear(neurons, 3, bias=False)

    def rational_k(self):
        return torch.clamp(torch.exp(self.log_k), min=1e-3)

    def features(self, x):
        k = self.rational_k()
        cx = torch.clamp(x, min=0.0)
        return torch.cat([x, cx / (cx + k)], dim=-1)

    def initialize_on_simplex(self, interior_points):
        with torch.no_grad():
            points = self.features(interior_points[:self.neurons])
            directions = torch.randn((self.neurons, 6), device=points.device, dtype=points.dtype)
            directions = directions / torch.norm(directions, dim=-1, keepdim=True) * 2.5
            self.hidden.weight.copy_(directions)
            self.hidden.bias.copy_(-(directions * points).sum(dim=-1))

    def forward(self, x):
        f = self.features(x)
        return self.skip(f) + self.output(torch.relu(self.hidden(f)))

def _simplex_neuron_bounds(b_in, w_in, rational_k):
    """Computes global min and max of b_in + w_c . c + w_q . (c / (c + k)) on the 3-simplex."""
    w_c = w_in[:3]
    w_q = w_in[3:]

    def eval_z(c):
        return float(b_in + np.dot(w_c, c) + np.dot(w_q, c / (c + rational_k)))

    def grad_z(c):
        return w_c + w_q * rational_k / ((c + rational_k) ** 2)

    candidates = [
        np.array([1.0, 0.0, 0.0], dtype=np.float64),
        np.array([0.0, 1.0, 0.0], dtype=np.float64),
        np.array([0.0, 0.0, 1.0], dtype=np.float64),
        np.array([0.0, 0.0, 0.0], dtype=np.float64),
        np.array([0.25, 0.25, 0.25], dtype=np.float64),
    ]

    # Add 1D stationary points where w_c and w_q have opposite signs
    crit_1d = np.zeros(3, dtype=np.float64)
    for j in range(3):
        if w_c[j] * w_q[j] < -1e-12:
            c_star = math.sqrt(-w_q[j] * rational_k[j] / w_c[j]) - rational_k[j]
            if 0.0 < c_star < 1.0:
                pt = np.zeros(3, dtype=np.float64)
                pt[j] = c_star
                candidates.append(pt)
                crit_1d[j] = c_star
    if np.sum(crit_1d) > 0.0:
        s = np.sum(crit_1d)
        candidates.append(crit_1d if s <= 1.0 else crit_1d / s)

    vals = [eval_z(pt) for pt in candidates]
    v_min = min(vals)
    v_max = max(vals)

    bounds = [(0.0, 1.0)] * 3
    constraints = [{"type": "ineq", "fun": lambda c: 1.0 - np.sum(c), "jac": lambda c: -np.ones(3)}]

    for sign in (1.0, -1.0):
        start_pt = candidates[int(np.argmin(vals) if sign > 0 else np.argmax(vals))]
        res = sp_opt.minimize(
            lambda c: sign * eval_z(c),
            start_pt,
            jac=lambda c: sign * grad_z(c),
            method="SLSQP",
            bounds=bounds,
            constraints=constraints,
            options={"ftol": 1e-12, "maxiter": 100}
        )
        if res.success:
            c_opt = np.clip(res.x, 0.0, 1.0)
            if np.sum(c_opt) > 1.0:
                c_opt /= np.sum(c_opt)
            val = eval_z(c_opt)
            v_min = min(v_min, val)
            v_max = max(v_max, val)

    return v_min, v_max

def extract_pruned_weights(model):
    model_cpu = model.cpu()
    with torch.no_grad():
        rational_k = model_cpu.rational_k().detach().numpy().astype(np.float64).copy()
        skip_bias = model_cpu.skip.bias.detach().numpy().astype(np.float64).copy()
        skip_weight = model_cpu.skip.weight.detach().numpy().astype(np.float64).copy()
        hidden_weight = model_cpu.hidden.weight.detach().numpy().astype(np.float64)
        hidden_bias = model_cpu.hidden.bias.detach().numpy().astype(np.float64)
        output_weight = model_cpu.output.weight.detach().numpy().astype(np.float64)

    active_neurons = []
    dead_count = 0
    folded_linear_count = 0

    for i in range(model_cpu.neurons):
        w_in = hidden_weight[i]
        b_in = hidden_bias[i]
        w_out = output_weight[:, i]

        v_min, v_max = _simplex_neuron_bounds(b_in, w_in, rational_k)

        if v_max <= -1e-6 or float(np.max(np.abs(w_out))) <= 1e-7:
            dead_count += 1
        elif v_min >= 1e-6:
            # Strictly >= 0 across the entire simplex: fold directly into the skip layer
            skip_bias += w_out * b_in
            skip_weight += np.outer(w_out, w_in)
            folded_linear_count += 1
        else:
            active_neurons.append((b_in, w_in, w_out))

    return rational_k, skip_bias, skip_weight, active_neurons, dead_count, folded_linear_count

def _format_linear_combination(bias, weights, var_names, suffix="", wrap_after=None, wrap_indent=""):
    first_part = f"{bias:.7f}{suffix}"
    terms = []
    for idx, (w, name) in enumerate(zip(weights, var_names)):
        if abs(w) >= 1e-7:
            sign = "+" if w >= 0.0 else "-"
            terms.append((idx, sign, f"{name} * {abs(w):.7f}{suffix}"))

    if not terms:
        return first_part

    if wrap_after is None:
        return first_part + "".join(f" {sign} {term}" for _, sign, term in terms)

    line1_terms = [(sign, term) for idx, sign, term in terms if idx < wrap_after]
    line2_terms = [(sign, term) for idx, sign, term in terms if idx >= wrap_after]

    if not line2_terms:
        return first_part + "".join(f" {sign} {term}" for sign, term in line1_terms)

    line1 = first_part + "".join(f" {sign} {term}" for sign, term in line1_terms)
    first_sign, first_term = line2_terms[0]
    rest_line2 = "".join(f" {sign} {term}" for sign, term in line2_terms[1:])
    return f"{line1} {first_sign}\n{wrap_indent}{first_term}{rest_line2}"

def generate_kotlin_neural_network(mlp_model):
    rational_k, skip_bias, skip_weight, active_neurons, _, _ = extract_pruned_weights(mlp_model)
    channels = ['r', 'g', 'b']
    inputs = ['c0', 'c1', 'c2', 'q0', 'q1', 'q2']
    decl = "var" if len(active_neurons) > 0 else "val"

    lines = [
        "    internal fun pigmentsMixLinear(",
        "        c0: Float,",
        "        c1: Float,",
        "        c2: Float,",
        "        color: FloatArray",
        "    ) {",
        "        // Mixing pigments to a linear sRGB color is implemented using a single hidden",
        f"        // layer, {mlp_model.neurons} neurons, neural network with rational tinting-strength features.",
        "        // The loops have been unrolled and the code re-organized to minimize register",
        "        // spilling and keep the instructions count low on arm64.",
        f"        val q0 = c0 / (c0 + {rational_k[0]:.7f}f)",
        f"        val q1 = c1 / (c1 + {rational_k[1]:.7f}f)",
        f"        val q2 = c2 / (c2 + {rational_k[2]:.7f}f)",
        "",
    ]

    for ch in range(3):
        init_expr = _format_linear_combination(
            skip_bias[ch],
            skip_weight[ch],
            inputs,
            suffix="f",
            wrap_after=3,
            wrap_indent="            "
        )
        align = " " if skip_bias[ch] >= 0.0 else ""
        lines.append(f"        {decl} {channels[ch]} = {align}{init_expr}")

    for idx, (b_in, w_in, w_out) in enumerate(active_neurons):
        lines.append("")
        hinge_expr = _format_linear_combination(
            b_in,
            w_in,
            inputs,
            suffix="f",
            wrap_after=3,
            wrap_indent="                "
        )
        lines.append("        val h" + str(idx) + " = max(\n            0f,\n            " + hinge_expr + "\n        )")
        for ch in range(3):
            weight = w_out[ch]
            if abs(weight) >= 1e-7:
                op = "+=" if weight >= 0.0 else "-="
                lines.append(f"        {channels[ch]} {op} h{idx} * {abs(weight):.7f}f")

    lines.extend([
        "",
        "        color[0] = r.fastCoerceIn(0.0f, 1.0f)",
        "        color[1] = g.fastCoerceIn(0.0f, 1.0f)",
        "        color[2] = b.fastCoerceIn(0.0f, 1.0f)",
        "    }",
    ])

    return "\n".join(lines)

def generate_python_neural_network(mlp_model):
    rational_k, skip_bias, skip_weight, active_neurons, _, _ = extract_pruned_weights(mlp_model)
    channels = ['r', 'g', 'b']
    inputs = ['c0', 'c1', 'c2', 'q0', 'q1', 'q2']

    lines = [
        "    def mix_as_nn(self, concentration):",
        "        c0, c1, c2, _ = concentration",
        f"        q0 = c0 / (c0 + {rational_k[0]:.7f})",
        f"        q1 = c1 / (c1 + {rational_k[1]:.7f})",
        f"        q2 = c2 / (c2 + {rational_k[2]:.7f})",
        "",
    ]

    for ch in range(3):
        init_expr = _format_linear_combination(skip_bias[ch], skip_weight[ch], inputs)
        align = " " if skip_bias[ch] >= 0.0 else ""
        lines.append(f"        {channels[ch]} = {align}{init_expr}")

    for idx, (b_in, w_in, w_out) in enumerate(active_neurons):
        lines.append("")
        hinge_expr = _format_linear_combination(b_in, w_in, inputs)
        lines.append(f"        h{idx} = max(0.0, {hinge_expr})")
        for ch in range(3):
            weight = w_out[ch]
            if abs(weight) >= 1e-7:
                op = "+=" if weight >= 0.0 else "-="
                lines.append(f"        {channels[ch]} {op} h{idx} * {abs(weight):.7f}")

    lines.extend([
        "",
        "        return [",
        "            max(0.0, min(1.0, r)),",
        "            max(0.0, min(1.0, g)),",
        "            max(0.0, min(1.0, b))",
        "        ]",
    ])

    return "\n".join(lines)

def _format_float(value):
    value = 0.0 if abs(value) < 5e-8 else float(value)
    return f"{value:.7f}"

def _format_float3(values):
    return f"float3({_format_float(values[0])}, {_format_float(values[1])}, {_format_float(values[2])})"

def generate_shader_neural_network(mlp_model):
    rational_k, skip_bias, skip_weight, active_neurons, _, _ = extract_pruned_weights(mlp_model)
    feature_components = ['c.x', 'c.y', 'c.z', 'q.x', 'q.y', 'q.z']

    skip_terms = [_format_float3(skip_bias)]
    for col_idx, comp in enumerate(feature_components):
        col = skip_weight[:, col_idx]
        if float(np.max(np.abs(col))) >= 1e-7:
            skip_terms.append(f"{comp} * {_format_float3(col)}")

    if len(skip_terms) == 1:
        rgb_init = f"    float3 rgb = {skip_terms[0]};"
    else:
        rgb_init = f"    float3 rgb = {skip_terms[0]}\n        + " + "\n        + ".join(skip_terms[1:]) + ";"

    lines = [
        "float3 mixPigments(float3 c) {",
        f"    float3 q = c / (c + {_format_float3(rational_k)});",
        "",
        rgb_init,
    ]

    for idx, (b_in, w_in, w_out) in enumerate(active_neurons):
        lines.append("")
        w_c = w_in[:3]
        w_q = w_in[3:]
        dot_terms = [_format_float(b_in)]
        if float(np.max(np.abs(w_c))) >= 1e-7:
            dot_terms.append(f"dot(c, {_format_float3(w_c)})")
        if float(np.max(np.abs(w_q))) >= 1e-7:
            dot_terms.append(f"dot(q, {_format_float3(w_q)})")
        hinge_sum = "\n            + ".join(dot_terms)
        lines.append(
            f"    float h{idx} = max(\n        0.0,\n        {hinge_sum}\n    );"
        )
        lines.append(f"    rgb += h{idx} * {_format_float3(w_out)};")

    lines.extend([
        "",
        "    return saturate(rgb);",
        "}",
    ])

    return "\n".join(lines)

def _replace_python_function(filepath, function_name, new_code):
    if not os.path.exists(filepath):
        raise FileNotFoundError(f"Could not find {filepath}")

    with open(filepath, "r", encoding="utf-8", newline="") as f:
        content = f.read()

    line_sep = "\r\n" if "\r\n" in content else "\n"
    trailing_newline = content.endswith("\n")
    lines = content.splitlines()

    def_pattern = re.compile(rf"^([ ]*)def\s+{re.escape(function_name)}\s*\(")
    matches = [idx for idx, line in enumerate(lines) if def_pattern.match(line)]
    if not matches:
        raise ValueError(f"Could not find 'def {function_name}' in {filepath}")
    if len(matches) > 1:
        raise ValueError(f"Found multiple definitions of 'def {function_name}' in {filepath}")

    start_idx = matches[0]
    base_indent = len(def_pattern.match(lines[start_idx]).group(1))

    paren_depth = 0
    header_end_idx = start_idx
    while header_end_idx < len(lines):
        code_part = lines[header_end_idx].split("#", 1)[0]
        for ch in code_part:
            if ch in "([{":
                paren_depth += 1
            elif ch in ")]}":
                paren_depth -= 1
        if paren_depth <= 0 and code_part.rstrip().endswith(":"):
            break
        header_end_idx += 1

    if header_end_idx >= len(lines):
        raise ValueError(f"Malformed definition of 'def {function_name}' in {filepath}")

    end_idx = header_end_idx + 1
    while end_idx < len(lines):
        line = lines[end_idx]
        if line.strip() == "":
            end_idx += 1
            continue
        indent = len(line) - len(line.lstrip(" "))
        if indent <= base_indent:
            break
        end_idx += 1

    while end_idx > header_end_idx + 1 and lines[end_idx - 1].strip() == "":
        end_idx -= 1

    replacement_lines = new_code.splitlines()
    if replacement_lines:
        gen_indent = len(replacement_lines[0]) - len(replacement_lines[0].lstrip(" "))
        if gen_indent != base_indent:
            adjusted = []
            for rl in replacement_lines:
                if rl.strip() == "":
                    adjusted.append("")
                elif base_indent > gen_indent:
                    adjusted.append((" " * (base_indent - gen_indent)) + rl)
                else:
                    remove = min(gen_indent - base_indent, len(rl) - len(rl.lstrip(" ")))
                    adjusted.append(rl[remove:])
            replacement_lines = adjusted

    updated_lines = lines[:start_idx] + replacement_lines + lines[end_idx:]
    updated = line_sep.join(updated_lines)
    if trailing_newline:
        updated += line_sep

    with open(filepath, "w", encoding="utf-8", newline="") as f:
        f.write(updated)

def _replace_brace_function(filepath, signature_pattern, new_code, function_name):
    if not os.path.exists(filepath):
        raise FileNotFoundError(f"Could not find {filepath}")

    with open(filepath, "r", encoding="utf-8", newline="") as f:
        content = f.read()

    matches = list(re.finditer(signature_pattern, content, flags=re.MULTILINE))
    if not matches:
        raise ValueError(f"Could not find function '{function_name}' in {filepath}")
    if len(matches) > 1:
        raise ValueError(f"Found multiple definitions of '{function_name}' in {filepath}")

    start = matches[0].start()
    i = matches[0].end()
    n = len(content)
    depth = 0
    found_open_brace = False
    end = None

    while i < n:
        if content.startswith("//", i):
            newline_pos = content.find("\n", i + 2)
            i = n if newline_pos == -1 else newline_pos + 1
            continue
        if content.startswith("/*", i):
            close_pos = content.find("*/", i + 2)
            if close_pos == -1:
                raise ValueError(f"Unterminated block comment in '{function_name}' in {filepath}")
            i = close_pos + 2
            continue
        ch = content[i]
        if ch in ('"', "'"):
            quote = ch
            i += 1
            while i < n:
                if content[i] == "\\":
                    i += 2
                    continue
                if content[i] == quote:
                    i += 1
                    break
                i += 1
            continue
        if ch == "{":
            depth += 1
            found_open_brace = True
        elif ch == "}":
            if not found_open_brace:
                raise ValueError(f"Unexpected '}}' before opening '{{' in '{function_name}' in {filepath}")
            depth -= 1
            if depth == 0:
                end = i + 1
                break
        i += 1

    if end is None:
        raise ValueError(f"Could not find closing '}}' for '{function_name}' in {filepath}")

    replacement = new_code.replace("\n", "\r\n") if "\r\n" in content else new_code
    updated = content[:start] + replacement + content[end:]

    with open(filepath, "w", encoding="utf-8", newline="") as f:
        f.write(updated)

def update_source_files(data_config, mlp_model):
    python_code = generate_python_neural_network(mlp_model)
    _replace_python_function(data_config.mix_path_python, "mix_as_nn", python_code)
    print(f"Updated Python implementation in: {data_config.mix_path_python}")

    kotlin_code = generate_kotlin_neural_network(mlp_model)
    _replace_brace_function(
        data_config.mix_path_kotlin,
        r"^[ \t]*internal\s+fun\s+pigmentsMixLinear\s*\(",
        kotlin_code,
        "pigmentsMixLinear"
    )
    print(f"Updated Kotlin implementation in: {data_config.mix_path_kotlin}")

    glsl_code = generate_shader_neural_network(mlp_model)
    _replace_brace_function(
        data_config.mix_path_glsl,
        r"^[ \t]*float3\s+mixPigments\s*\(",
        glsl_code,
        "mixPigments"
    )
    print(f"Updated GLSL implementation in: {data_config.mix_path_glsl}")

def _generate_simplex_training_samples(num_samples):
    n_uniform = max(1, int(num_samples * 0.70))
    n_boundary = max(1, int(num_samples * 0.18))
    n_corners_prior = max(1, int(num_samples * 0.08))
    remaining = max(120, num_samples - n_uniform - n_boundary - n_corners_prior)
    n_per_face = max(16, (remaining // 2) // 4)
    n_per_edge = max(16, (remaining - 4 * n_per_face) // 6)

    d_uniform = torch.distributions.Dirichlet(torch.ones(4)).sample((n_uniform,))
    d_boundary = torch.distributions.Dirichlet(torch.full((4,), 0.4)).sample((n_boundary,))
    d_corners = torch.distributions.Dirichlet(torch.full((4,), 0.15)).sample((n_corners_prior,))

    # Explicit 3-pigment boundary faces (each missing one pigment, especially c3 = 0 white)
    face_samples = torch.distributions.Dirichlet(torch.full((3,), 0.6)).sample((n_per_face,))
    faces = []
    for zero_col in range(4):
        face = torch.zeros((n_per_face, 4), dtype=torch.float32)
        active_cols = [c for c in range(4) if c != zero_col]
        face[:, active_cols] = face_samples
        faces.append(face)

    # Explicit 2-pigment edges (including the 4 pure-pigment vertices at t=0 and t=1)
    t = torch.linspace(0.0, 1.0, n_per_edge, dtype=torch.float32)
    edges = []
    for i in range(4):
        for j in range(i + 1, 4):
            edge = torch.zeros((n_per_edge, 4), dtype=torch.float32)
            edge[:, i] = t
            edge[:, j] = 1.0 - t
            edges.append(edge)

    X_all = torch.cat([d_uniform, d_boundary, d_corners] + faces + edges, dim=0)
    return X_all, n_uniform

def _generate_simplex_lattice(steps, power=1.0):
    coords = []
    for i in range(steps + 1):
        j_max = steps - i
        j = torch.arange(j_max + 1, dtype=torch.float32)
        J, K = torch.meshgrid(j, j, indexing='ij')
        mask = (J + K) <= j_max
        jv = J[mask]
        kv = K[mask]
        iv = torch.full_like(jv, float(i))
        lv = float(steps) - iv - jv - kv
        coords.append(torch.stack([iv, jv, kv, lv], dim=-1) / float(steps))
    X = torch.cat(coords, dim=0)
    if power != 1.0:
        X = X ** power
        X = X / X.sum(dim=-1, keepdim=True)
    return X

def _mix_linear_gpu(concentrations_4d, device, chunk_size=131_072):
    outputs = []
    with torch.no_grad():
        for start in range(0, concentrations_4d.shape[0], chunk_size):
            chunk = concentrations_4d[start:start + chunk_size].to(device)
            rgb = pg.mix_torch(chunk, pg.P_star_K, pg.P_star_S)
            outputs.append(torch.clamp(rgb, 0.0, 1.0))
    return torch.cat(outputs, dim=0)

def _ste_clamp_01(raw_pred, y_target):
    """
    Applies exact runtime [0, 1] clamping in the forward pass (so outlier losses see
    only true post-clamp errors), while passing gradients whenever nudging raw_pred
    back toward [0, 1] can reduce error.
    """
    clamped = torch.clamp(raw_pred, 0.0, 1.0)
    pass_grad = (
        ((raw_pred >= 0.0) & (raw_pred <= 1.0))
        | ((raw_pred < 0.0) & (y_target > 0.0))
        | ((raw_pred > 1.0) & (y_target < 1.0))
    )
    grad_proxy = torch.where(pass_grad, raw_pred, raw_pred.detach())
    return clamped.detach() + (grad_proxy - grad_proxy.detach())

def fit_mix_nn(data_config, neurons=16, sample_size=384):
    device = pg.device
    print(f"Using device: {device}")

    torch.manual_seed(42)

    # 1. Compact stratified training pool for high-step Stage-1 Adam optimization
    stage1_samples = 524_288
    print(f"Generating {stage1_samples:,} stratified training samples and dense minimax mining pool...")
    X_s1_4d, _ = _generate_simplex_training_samples(stage1_samples)
    y_s1_cpu = _mix_linear_gpu(X_s1_4d, device).cpu()
    X_s1_cpu = X_s1_4d[:, :3].contiguous()
    del X_s1_4d

    # 2. Dense + boundary-warped simplex lattice pool (~1.56M points) for Stage-2 Remez exchange
    pool_uniform = _generate_simplex_lattice(160, power=1.0)
    pool_boundary = _generate_simplex_lattice(140, power=2.5)
    pool_corners = _generate_simplex_lattice(100, power=4.5)
    torch.manual_seed(123)
    pool_faces_edges, _ = _generate_simplex_training_samples(200_000)
    X_pool_4d = torch.cat([pool_uniform, pool_boundary, pool_corners, pool_faces_edges], dim=0)
    pool_perm = torch.randperm(X_pool_4d.shape[0])
    X_pool_4d = X_pool_4d[pool_perm]
    y_pool_gpu = _mix_linear_gpu(X_pool_4d, device)
    y_pool_cpu = y_pool_gpu.cpu()
    X_pool_cpu = X_pool_4d[:, :3].contiguous()
    X_pool_gpu = X_pool_cpu.to(device)
    del X_pool_4d, pool_uniform, pool_boundary, pool_corners, pool_faces_edges

    # Run the tiny (~184-param) MLP parameter updates on CPU to avoid GPU kernel-launch overhead
    # on small batches and support L-BFGS strong-Wolfe line search, while evaluating 1.56M-point
    # mining passes on GPU.
    model_gpu = PigmentsToColorMLP(neurons=neurons).to(device)
    torch.manual_seed(42)
    model = PigmentsToColorMLP(neurons=neurons)
    model.initialize_on_simplex(X_s1_cpu[:max(neurons, 64)])

    stage1_steps = 100_000
    batch_size = 8_192
    stage1_log_interval = 10_000

    print(
        f"\nStage 1/3: Training neural network (1 hidden layer, {neurons} neurons, "
        f"3 inputs + 3 rational features + skip) for {stage1_steps:,} steps..."
    )

    opt1 = optim.Adam(model.parameters(), lr=5e-3)
    sched1 = optim.lr_scheduler.CosineAnnealingLR(opt1, T_max=stage1_steps, eta_min=2e-4)

    n_s1 = X_s1_cpu.shape[0]
    pos = n_s1
    for step in range(1, stage1_steps + 1):
        if pos + batch_size > n_s1:
            perm = torch.randperm(n_s1)
            xs = X_s1_cpu[perm]
            ys = y_s1_cpu[perm]
            pos = 0
        xb = xs[pos:pos + batch_size]
        yb = ys[pos:pos + batch_size]
        pos += batch_size

        opt1.zero_grad()
        eff_pred = _ste_clamp_01(model(xb), yb)
        sq_error = (eff_pred - yb) ** 2
        per_sample_sq = sq_error.max(dim=-1).values
        topk_loss = torch.topk(per_sample_sq, k=16).values.mean()
        l4_loss = (sq_error * sq_error).mean()
        loss = sq_error.mean() + 0.12 * topk_loss + 20.0 * l4_loss

        loss.backward()
        nn.utils.clip_grad_norm_(model.parameters(), max_norm=1.0)
        opt1.step()
        sched1.step()

        if step % stage1_log_interval == 0 or step == stage1_steps:
            with torch.no_grad():
                model_gpu.load_state_dict(model.state_dict())
                preds = torch.clamp(model_gpu(X_pool_gpu), 0.0, 1.0)
                diff_pool = preds - y_pool_gpu
                pool_mse = torch.mean(diff_pool * diff_pool).item()
                pool_max = torch.max(torch.abs(diff_pool)).item()
            print(
                f"  Step {step:7,d}/{stage1_steps:,d} | LR: {opt1.param_groups[0]['lr']:.2e} | "
                f"Pool MSE: {pool_mse:.8f} | Pool Max Error: {pool_max:.5f}"
            )

    del X_s1_cpu, y_s1_cpu

    # Stage 2: Persistent active-set (Remez-style) smooth-L_inf (LogSumExp) minimax refinement
    stage2_steps = 14_000
    print(f"\nStage 2/3: Persistent active-set smooth-L_inf refinement ({stage2_steps:,} steps)...")

    crit_indices = set(range(4_096))
    best_pool_max = float('inf')
    best_state_dict = {k: v.detach().clone() for k, v in model.state_dict().items()}

    opt2 = optim.Adam(model.parameters(), lr=4e-4)
    sched2 = optim.lr_scheduler.CosineAnnealingLR(opt2, T_max=stage2_steps, eta_min=2e-6)

    x_crit = None
    y_crit = None

    for step in range(stage2_steps):
        if step % 250 == 0:
            with torch.no_grad():
                model_gpu.load_state_dict(model.state_dict())
                preds = torch.clamp(model_gpu(X_pool_gpu), 0.0, 1.0)
                errs = torch.abs(preds - y_pool_gpu).max(dim=-1).values
                pool_max = errs.max().item()
                if pool_max < best_pool_max:
                    best_pool_max = pool_max
                    best_state_dict = {k: v.detach().clone() for k, v in model.state_dict().items()}

                top4k = torch.topk(errs, 4_096).indices.cpu().tolist()
                added = 0
                for idx in top4k:
                    if idx not in crit_indices:
                        crit_indices.add(idx)
                        added += 1
                        if added >= 512:
                            break
                if len(crit_indices) > 16_384:
                    non_anchors = [i for i in crit_indices if i >= 4_096]
                    na_t = torch.tensor(non_anchors, dtype=torch.long)
                    keep_na = na_t[torch.topk(errs.cpu()[na_t], 12_288).indices].tolist()
                    crit_indices = set(range(4_096)) | set(keep_na)

                crit_list = list(crit_indices)
                x_crit = X_pool_cpu[crit_list]
                y_crit = y_pool_cpu[crit_list]

            if step % 2_000 == 0:
                print(
                    f"  Step {step:5,d}/{stage2_steps:,d} | LR: {opt2.param_groups[0]['lr']:.2e} | "
                    f"Pool Max Error: {pool_max:.5f} | Best Max Error: {best_pool_max:.5f}"
                )

        opt2.zero_grad()
        eff_pred = _ste_clamp_01(model(x_crit), y_crit)
        diff = eff_pred - y_crit
        progress = step / float(stage2_steps)
        eps = 2e-5 * (0.2 ** progress)
        abs_err = torch.sqrt(diff * diff + eps * eps) - eps
        beta = 80.0 * (15.0 ** progress)  # 80 -> 1200
        max_det = abs_err.max().detach()
        lse_loss = max_det + (1.0 / beta) * torch.log(torch.mean(torch.exp(beta * (abs_err - max_det))))
        loss = lse_loss + 0.015 * torch.sqrt(torch.mean(diff * diff) + 1e-10)

        loss.backward()
        nn.utils.clip_grad_norm_(model.parameters(), max_norm=0.5)
        opt2.step()
        sched2.step()

    with torch.no_grad():
        model_gpu.load_state_dict(model.state_dict())
        pool_max = torch.abs(torch.clamp(model_gpu(X_pool_gpu), 0.0, 1.0) - y_pool_gpu).max().item()
        if pool_max < best_pool_max:
            best_pool_max = pool_max
            best_state_dict = {k: v.detach().clone() for k, v in model.state_dict().items()}

    # Stage 3: Quasi-Newton L-BFGS minimax polish on critical set
    print("\nStage 3/3: Quasi-Newton L-BFGS minimax polish...")
    model.load_state_dict(best_state_dict)

    for beta_lbfgs in (800.0, 1500.0):
        with torch.no_grad():
            model_gpu.load_state_dict(model.state_dict())
            errs = torch.abs(torch.clamp(model_gpu(X_pool_gpu), 0.0, 1.0) - y_pool_gpu).max(dim=-1).values
            top6k = torch.topk(errs, 6_144).indices.cpu().tolist()
            pol_idx = list(crit_indices | set(top6k))
            x_pol = X_pool_cpu[pol_idx]
            y_pol = y_pool_cpu[pol_idx]

        lbfgs = optim.LBFGS(
            model.parameters(),
            lr=0.25,
            max_iter=60,
            history_size=100,
            line_search_fn="strong_wolfe"
        )

        def closure():
            lbfgs.zero_grad()
            eff = _ste_clamp_01(model(x_pol), y_pol)
            d = eff - y_pol
            ae = torch.sqrt(d * d + 1e-10)
            md = ae.max().detach()
            lp = md + (1.0 / beta_lbfgs) * torch.log(torch.mean(torch.exp(beta_lbfgs * (ae - md)))) + 0.005 * torch.mean(d * d)
            lp.backward()
            return lp

        try:
            lbfgs.step(closure)
            with torch.no_grad():
                model_gpu.load_state_dict(model.state_dict())
                pool_max = torch.abs(torch.clamp(model_gpu(X_pool_gpu), 0.0, 1.0) - y_pool_gpu).max().item()
                if pool_max < best_pool_max:
                    best_pool_max = pool_max
                    best_state_dict = {k: v.detach().clone() for k, v in model.state_dict().items()}
                else:
                    model.load_state_dict(best_state_dict)
        except Exception:
            model.load_state_dict(best_state_dict)

    print(f"  Polished Pool Max Error: {best_pool_max:.6f}")

    del X_pool_cpu, y_pool_cpu, X_pool_gpu, y_pool_gpu

    model.load_state_dict(best_state_dict)
    model_gpu.load_state_dict(best_state_dict)
    model_gpu.eval()

    # Final comprehensive evaluation on full target_samples distribution + dense lattice
    num_inputs = 4
    target_samples = math.comb(sample_size + num_inputs - 1, num_inputs - 1)
    print(f"\nCalculating error across {target_samples:,} evaluation samples on {device}...")
    torch.manual_seed(42)
    X_eval_4d, num_uniform = _generate_simplex_training_samples(target_samples)
    y_eval = _mix_linear_gpu(X_eval_4d, device)
    X_eval = X_eval_4d[:, :3].contiguous().to(device)
    del X_eval_4d

    eval_chunk = 262_144
    abs_errors_list = []
    with torch.no_grad():
        for start in range(0, X_eval.shape[0], eval_chunk):
            preds = torch.clamp(model_gpu(X_eval[start:start + eval_chunk]), 0.0, 1.0)
            abs_errors_list.append(torch.abs(y_eval[start:start + eval_chunk] - preds).cpu())
    all_errors = torch.cat(abs_errors_list, dim=0).numpy()
    uniform_errors = all_errors[:num_uniform]

    rational_k, _, _, active_neurons, dead_count, folded_count = extract_pruned_weights(model)
    print(
        f"Active nonlinear neurons : {len(active_neurons)}/{neurons} "
        f"(folded always-on: {folded_count}, pruned dead: {dead_count})"
    )
    print(f"Rational poles (k0,k1,k2): ({rational_k[0]:.6f}, {rational_k[1]:.6f}, {rational_k[2]:.6f})")
    print(f"Mean Squared Error (MSE) : {np.mean(uniform_errors ** 2):.6f}")
    print(f"50.0th percentile error  : {np.percentile(uniform_errors, 50):.6f}")
    print(f"90.0th percentile error  : {np.percentile(uniform_errors, 90):.6f}")
    print(f"99.0th percentile error  : {np.percentile(uniform_errors, 99):.6f}")
    print(f"99.9th percentile error  : {np.percentile(uniform_errors, 99.9):.6f}")
    print(f"Maximum absolute error   : {max(float(np.max(all_errors)), best_pool_max):.6f}\n")

    update_source_files(data_config, model)

if __name__ == '__main__':
    if len(sys.argv) == 1:
        print("python3 pigments_to_color.py [nn]")
        print("  nn    : Fit a neural network model to the pigment mixing data")
        print("\nCommand help:")
        print("  python3 pigments_to_color.py nn [neurons] [sample_size]")
        sys.exit(0)

    command = sys.argv[1]
    data_config = pigments.load_data_config_from_json("data_config.json")

    if command == "nn":
        neurons = int(sys.argv[2]) if len(sys.argv) > 2 else 16
        sample_size = int(sys.argv[3]) if len(sys.argv) > 3 else 384
        fit_mix_nn(data_config, neurons=neurons, sample_size=sample_size)
