import math
import sys
import numpy as np
import os
import pigments
import torch
import torch.nn as nn
import torch.optim as optim
from concurrent.futures import ProcessPoolExecutor
from sklearn.linear_model import Ridge, RidgeCV
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import PolynomialFeatures

pg = pigments.Pigments(pigments.load_config_from_json("config.json"))

def chunk_processor(chunk):
    return pg.mix_linear(chunk)

def generate_python_polynomial(feature_names, intercepts, coefficients, func_name="mix_as_curve"):
    py_features = [name.replace(" ", " * ").replace("^", "**") for name in feature_names]

    num_outputs = len(intercepts)
    
    lines = [
        f"def {func_name}(self, concentration):",
        "    c0 = concentration[0]  # blue",
        "    c1 = concentration[1]  # magenta",
        "    c2 = concentration[2]  # yellow",
        "    c3 = concentration[3]  # white",
        "    return np.clip(np.array(["
    ]

    for i in range(num_outputs):
        terms = [f"{intercepts[i]:.7f}"]
        for j, expr in enumerate(py_features):
            coef = coefficients[i][j]
            if coef != 0.0:
                sign = "+" if coef >= 0 else "-"
                terms.append(f"{sign} {abs(coef):.7f} * {expr}")
        
        chunk_size = 4
        equation_chunks = []
        for k in range(0, len(terms), chunk_size):
            equation_chunks.append(" ".join(terms[k:k+chunk_size]))
            
        formatted_equation = " \n      ".join(equation_chunks)
        lines.append(f"        {formatted_equation},")

    lines.append("    ]), 0.0, 1.0)")
    
    return "\n".join(lines)

def generate_kotlin_polynomial(feature_names, intercepts, coefficients):
    def to_kotlin_expr(name):
        terms = name.split(" ")
        expanded = []
        for t in terms:
            if "^" in t:
                base, exp = t.split("^")
                expanded.extend([base] * int(exp))
            else:
                expanded.append(t)
        return " * ".join(expanded)

    kotlin_features = [to_kotlin_expr(name) for name in feature_names]
    channels = ['r', 'g', 'b']

    lines = [
        "import kotlin.math.max",
        "import kotlin.math.min",
        "",
        "fun pigmentsToColor(concentration: FloatArray): FloatArray {",
        "    val c0 = concentration[0]",
        "    val c1 = concentration[1]",
        "    val c2 = concentration[2]",
        "    val c3 = concentration[3]",
        ""
    ]

    for i in range(3):
        terms = [f"{intercepts[i]:.7f}f"]
        for j, expr in enumerate(kotlin_features):
            coef = coefficients[i][j]
            if abs(coef) > 1e-8:
                sign = "+" if coef >= 0 else "-"
                terms.append(f"{sign} {abs(coef):.7f}f * {expr}")
        
        chunk_size = 4
        equation_chunks = []
        for k in range(0, len(terms), chunk_size):
            equation_chunks.append(" ".join(terms[k:k+chunk_size]))
            
        formatted_equation = " \n            ".join(equation_chunks)
        lines.append(f"    val {channels[i]} = {formatted_equation}")
        lines.append("")

    lines.extend([
        "    return floatArrayOf(",
        "        max(0f, min(1f, r)),",
        "        max(0f, min(1f, g)),",
        "        max(0f, min(1f, b))",
        "    )",
        "}"
    ])
    
    return "\n".join(lines)

def fit3_mix(degree=3, sample_size=384):
    num_inputs = 4
    num_samples = math.comb(sample_size + num_inputs - 1, num_inputs - 1)

    print(f"Generating {num_samples:,} samples...")
    X_train = np.random.dirichlet(alpha=np.ones(num_inputs), size=num_samples)

    num_cores = max(1, os.cpu_count() - 1)
    print(f"Using {num_cores} cores for parallel processing...")

    chunks = np.array_split(X_train, num_cores)
    with ProcessPoolExecutor(max_workers=num_cores) as executor:
        results = list(executor.map(chunk_processor, chunks))
    y_train = np.vstack(results)

    print(f"Generating polynomial features (degree-{degree})...")
    polynomial = PolynomialFeatures(degree=degree, include_bias=False)
    X_train_poly = polynomial.fit_transform(X_train)

    alphas = [1e-8, 1e-7, 1e-6, 1e-5, 1e-4, 1e-3, 1e-2, 0.1, 1.0, 10.0]

    final_intercepts = np.zeros(3)
    final_coefs = np.zeros((3, X_train_poly.shape[1]))
    predictions_combined = np.zeros_like(y_train)

    channel_names = ['red', 'green', 'blue']

    for i in range(3):
        print(f"\n>> Training {channel_names[i]} channel")
        y_single_channel = y_train[:, i]
        
        model = RidgeCV(alphas=alphas)
        model.fit(X_train_poly, y_single_channel)
        
        preds_pass1 = model.predict(X_train_poly)
        sample_errors = np.abs(y_single_channel - preds_pass1)
        p90_threshold = np.percentile(sample_errors, 90)

        weights = np.ones(len(y_single_channel))
        weights[sample_errors >= p90_threshold] = 10.0 

        print(f"Refitting with focus on >90th percentile errors...")
        model.fit(X_train_poly, y_single_channel, sample_weight=weights)
        print(f"Chosen ridge penalty (alpha): {model.alpha_}")

        final_intercepts[i] = model.intercept_
        final_coefs[i] = model.coef_
        predictions_combined[:, i] = model.predict(X_train_poly)

    print("\nCalculating final error...")

    predictions_clipped = np.clip(predictions_combined, 0.0, 1.0)
    errors = np.abs(y_train - predictions_clipped)
    
    mse = np.mean(errors ** 2)
    max_err = np.max(errors)
    p50_err = np.percentile(errors, 50)
    p90_err = np.percentile(errors, 90)
    p99_err = np.percentile(errors, 99)
    p99_9_err = np.percentile(errors, 99.9)

    print(f"Mean Squared Error (MSE) : {mse:.6f}")
    print(f"50.0th percentile error  : {p50_err:.6f}")
    print(f"90.0th percentile error  : {p90_err:.6f}")
    print(f"99.0th percentile error  : {p99_err:.6f}")
    print(f"99.9th percentile error  : {p99_9_err:.6f}")
    print(f"Maximum absolute error   : {max_err:.6f}")

    feature_names = polynomial.get_feature_names_out(['c0', 'c1', 'c2', 'c3'])

    print("\n>> Executable Python function")
    print(generate_python_polynomial(feature_names, final_intercepts, final_coefs))
    
    print("\n>> Executable Kotlin function")
    print(generate_kotlin_polynomial(feature_names, final_intercepts, final_coefs))

def fit_mix(degree=2, sample_size=384):
    num_inputs = 4
    num_samples = math.comb(sample_size + num_inputs - 1, num_inputs - 1)

    print(f"Generating {num_samples:,} samples...")
    X_train = np.random.dirichlet(alpha=np.ones(num_inputs), size=num_samples)

    num_cores = os.cpu_count() - 1
    print(f"Generating data for all samples...")
    print(f"Using {num_cores} cores for parallel processing...")

    chunks = np.array_split(X_train, num_cores)
    with ProcessPoolExecutor(max_workers=num_cores) as executor:
        results = list(executor.map(chunk_processor, chunks))
    y_train = np.vstack(results)

    polynomial = PolynomialFeatures(degree=degree, include_bias=False)
    model = make_pipeline(
        polynomial,
        Ridge(alpha=1e-6, solver='cholesky')
    )

    print(f"Fitting degree-{degree} polynomial...")
    model.fit(X_train, y_train)

    print("Calculating error...")
    predictions = model.predict(X_train)
    errors = np.abs(y_train - predictions)
    
    mse = np.mean(errors ** 2)
    max_err = np.max(errors)
    p50_err = np.percentile(errors, 50)
    p90_err = np.percentile(errors, 90)
    p99_err = np.percentile(errors, 99)
    p99_9_err = np.percentile(errors, 99.9)

    print(f"Mean Squared Error (MSE) : {mse:.6f}")
    print(f"50.0th percentile error  : {p50_err:.6f}")
    print(f"90.0th percentile error  : {p90_err:.6f}")
    print(f"99.0th percentile error  : {p99_err:.6f}")
    print(f"99.9th percentile error  : {p99_9_err:.6f}")
    print(f"Maximum absolute error   : {max_err:.6f}")

    print("\n>> Fitted polynomial parameters")

    poly_step = model.named_steps['polynomialfeatures']
    linear_step = model.named_steps['ridge']

    feature_names = poly_step.get_feature_names_out(['c0', 'c1', 'c2', 'c3'])
    intercepts = linear_step.intercept_
    coefficients = linear_step.coef_

    formatter = {'float_kind': lambda x: f"{x: .7f}"}

    title = "constant"
    print(f"{title:>10} : {np.array2string(intercepts.T, formatter=formatter)}")
    for name, c in zip(feature_names, coefficients.T):
        print(f"{name:>10} : {np.array2string(c, formatter=formatter)}")

    print("\n>> Executable Python Function")
    generated_code = generate_python_polynomial(feature_names, intercepts, coefficients)
    print(generated_code)

    print("\n>> Executable Kotlin Function")
    generated_code = generate_kotlin_polynomial(feature_names, intercepts, coefficients)
    print(generated_code)

class PigmentsToColorMLP(nn.Module):
    def __init__(self, neurons=16):
        super().__init__()
        self.neurons = neurons
        self.skip = nn.Linear(3, 3)
        self.hidden = nn.Linear(3, neurons)
        self.output = nn.Linear(neurons, 3, bias=False)

    def initialize_on_simplex(self, interior_points):
        with torch.no_grad():
            points = interior_points[:self.neurons]
            directions = torch.randn((self.neurons, 3), device=points.device, dtype=points.dtype)
            directions = directions / torch.norm(directions, dim=-1, keepdim=True) * 2.5
            self.hidden.weight.copy_(directions)
            self.hidden.bias.copy_(-(directions * points).sum(dim=-1))

    def forward(self, x):
        return self.skip(x) + self.output(torch.relu(self.hidden(x)))

def extract_pruned_weights(model):
    model_cpu = model.cpu()
    with torch.no_grad():
        skip_bias = model_cpu.skip.bias.detach().numpy().astype(np.float64).copy()
        skip_weight = model_cpu.skip.weight.detach().numpy().astype(np.float64).copy()
        hidden_weight = model_cpu.hidden.weight.detach().numpy().astype(np.float64)
        hidden_bias = model_cpu.hidden.bias.detach().numpy().astype(np.float64)
        output_weight = model_cpu.output.weight.detach().numpy().astype(np.float64)

    # Exact 3-simplex vertices for (c0, c1, c2) with c3 = 1 - (c0 + c1 + c2)
    vertices = np.array([
        [1.0, 0.0, 0.0],
        [0.0, 1.0, 0.0],
        [0.0, 0.0, 1.0],
        [0.0, 0.0, 0.0],
    ], dtype=np.float64)

    active_neurons = []
    dead_count = 0
    folded_linear_count = 0

    for i in range(model_cpu.neurons):
        w_in = hidden_weight[i]
        b_in = hidden_bias[i]
        w_out = output_weight[:, i]

        vertex_vals = vertices @ w_in + b_in
        v_min = float(np.min(vertex_vals))
        v_max = float(np.max(vertex_vals))

        if v_max <= 1e-7 or float(np.max(np.abs(w_out))) <= 1e-7:
            dead_count += 1
        elif v_min >= -1e-7:
            # Always >= 0 across the entire simplex: fold directly into the linear skip
            skip_bias += w_out * b_in
            skip_weight += np.outer(w_out, w_in)
            folded_linear_count += 1
        else:
            active_neurons.append((b_in, w_in, w_out))

    return skip_bias, skip_weight, active_neurons, dead_count, folded_linear_count

def _format_linear_combination(bias, weights, var_names, suffix=""):
    expr = f"{bias:.7f}{suffix}"
    for w, name in zip(weights, var_names):
        if abs(w) >= 1e-7:
            sign = "+" if w >= 0.0 else "-"
            expr += f" {sign} {name} * {abs(w):.7f}{suffix}"
    return expr

def generate_kotlin_neural_network(mlp_model):
    skip_bias, skip_weight, active_neurons, _, _ = extract_pruned_weights(mlp_model)
    channels = ['r', 'g', 'b']
    inputs = ['c0', 'c1', 'c2']
    decl = "var" if len(active_neurons) > 0 else "val"

    lines = [
        "    internal fun pigmentsMixLinear(",
        "        c0: Float,",
        "        c1: Float,",
        "        c2: Float,",
        "        color: FloatArray",
        "    ) {",
        "        // Mixing pigments to a linear sRGB color is implemented using a single hidden",
        f"        // layer, {mlp_model.neurons} neurons, neural network. The loops have been unrolled and the code",
        "        // re-organized to minimize register spilling and keep the instructions count",
        "        // low on arm64.",
    ]

    for ch in range(3):
        init_expr = _format_linear_combination(skip_bias[ch], skip_weight[ch], inputs, suffix="f")
        align = " " if skip_bias[ch] >= 0.0 else ""
        lines.append(f"        {decl} {channels[ch]} = {align}{init_expr}")

    for idx, (b_in, w_in, w_out) in enumerate(active_neurons):
        lines.append("")
        hinge_expr = _format_linear_combination(b_in, w_in, inputs, suffix="f")
        lines.append(f"        val h{idx} = max(0f, {hinge_expr})")
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
    skip_bias, skip_weight, active_neurons, _, _ = extract_pruned_weights(mlp_model)
    channels = ['r', 'g', 'b']
    inputs = ['c0', 'c1', 'c2']

    lines = [
        "    def mix_as_nn(self, concentration):",
        "        c0, c1, c2, _ = concentration",
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
    skip_bias, skip_weight, active_neurons, _, _ = extract_pruned_weights(mlp_model)
    components = ['x', 'y', 'z']

    skip_terms = [_format_float3(skip_bias)]
    for col_idx, comp in enumerate(components):
        col = skip_weight[:, col_idx]
        if float(np.max(np.abs(col))) >= 1e-7:
            skip_terms.append(f"c.{comp} * {_format_float3(col)}")

    if len(skip_terms) == 1:
        rgb_init = f"    float3 rgb = {skip_terms[0]};"
    else:
        rgb_init = f"    float3 rgb = {skip_terms[0]}\n        + " + "\n        + ".join(skip_terms[1:]) + ";"

    lines = [
        "float3 mixPigments(float3 c) {",
        rgb_init,
    ]

    for idx, (b_in, w_in, w_out) in enumerate(active_neurons):
        lines.append("")
        lines.append(f"    float h{idx} = max(0.0, {_format_float(b_in)} + dot(c, {_format_float3(w_in)}));")
        lines.append(f"    rgb += h{idx} * {_format_float3(w_out)};")

    lines.extend([
        "",
        "    return saturate(rgb);",
        "}",
    ])

    return "\n".join(lines)

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

def _mix_linear_gpu(concentrations_4d, device, chunk_size=131_072):
    outputs = []
    with torch.no_grad():
        for start in range(0, concentrations_4d.shape[0], chunk_size):
            chunk = concentrations_4d[start:start + chunk_size].to(device)
            rgb = pg.mix_torch(chunk, pg.P_star_K, pg.P_star_S)
            outputs.append(torch.clamp(rgb, 0.0, 1.0))
    return torch.cat(outputs, dim=0)

def fit_mix_nn(neurons=16, sample_size=384):
    device = pg.device
    print(f"Using device: {device}")

    num_inputs = 4
    target_samples = math.comb(sample_size + num_inputs - 1, num_inputs - 1)
    print(f"Generating {target_samples:,} training samples on {device}...")

    torch.manual_seed(42)
    X_all_4d, num_uniform = _generate_simplex_training_samples(target_samples)
    y_train = _mix_linear_gpu(X_all_4d, device)
    X_train = X_all_4d[:, :3].contiguous().to(device)
    del X_all_4d

    num_samples = X_train.shape[0]
    batch_size = min(131_072, max(8_192, num_samples // 64), num_samples)
    topk_count = min(64, batch_size)
    num_batches = (num_samples + batch_size - 1) // batch_size
    epochs = 500
    log_interval = max(1, epochs // 25)

    print(
        f"Training neural network (1 hidden layer, {neurons} neurons, 3 inputs + linear skip) "
        f"for {epochs} epochs ({epochs * num_batches:,} steps)..."
    )

    model = PigmentsToColorMLP(neurons=neurons).to(device)
    model.initialize_on_simplex(X_train[:max(neurons, 64)])

    optimizer = optim.Adam(model.parameters(), lr=5e-3)
    scheduler = optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=epochs * num_batches)

    best_mse = torch.tensor(float('inf'), device=device)
    best_max_error = float('inf')
    best_state_dict = None

    for epoch in range(epochs):
        permutation = torch.randperm(num_samples, device=device)
        shuffled_x = X_train[permutation]
        shuffled_y = y_train[permutation]
        epoch_mse = torch.zeros((), device=device)
        max_error_sq = torch.zeros((), device=device)

        for start in range(0, num_samples, batch_size):
            optimizer.zero_grad()

            x_batch = shuffled_x[start:start + batch_size]
            y_batch = shuffled_y[start:start + batch_size]

            raw_pred = model(x_batch)

            # Zero penalty when prediction overshoots <0 where target==0 or >1 where target==1
            # (since runtime clamps to [0, 1]), while keeping full gradients everywhere else.
            effective_pred = torch.where(y_batch <= 1e-6, torch.clamp(raw_pred, min=0.0), raw_pred)
            effective_pred = torch.where(y_batch >= 1.0 - 1e-6, torch.clamp(effective_pred, max=1.0), effective_pred)

            diff = effective_pred - y_batch
            sq_error = diff * diff
            mse_loss = torch.mean(sq_error)
            per_sample_sq_error = torch.max(sq_error, dim=-1).values
            k = min(topk_count, per_sample_sq_error.shape[0])
            topk_loss = torch.mean(torch.topk(per_sample_sq_error, k=k).values)
            l4_loss = torch.mean(sq_error * sq_error)
            loss = mse_loss + 0.15 * topk_loss + 5.0 * l4_loss

            loss.backward()
            nn.utils.clip_grad_norm_(model.parameters(), max_norm=1.0)
            optimizer.step()
            scheduler.step()

            with torch.no_grad():
                clamped_diff = torch.clamp(raw_pred, 0.0, 1.0) - y_batch
                clamped_sq = clamped_diff * clamped_diff
                epoch_mse += torch.mean(clamped_sq)
                max_error_sq = torch.maximum(max_error_sq, torch.max(clamped_sq))

        current_mse = epoch_mse / num_batches
        best_mse = torch.minimum(best_mse, current_mse)

        epoch_max_error = torch.sqrt(max_error_sq).item()
        if epoch_max_error < best_max_error:
            best_max_error = epoch_max_error
            best_state_dict = {k: v.detach().clone() for k, v in model.state_dict().items()}

        if epoch % log_interval == 0 or epoch == epochs - 1:
            print(
                f"Epoch {epoch} | LR: {optimizer.param_groups[0]['lr']:.2e} | "
                f"Best MSE: {best_mse.item():.8f} | Max Error: {epoch_max_error:.4f} | "
                f"Best Max Error: {best_max_error:.4f}"
            )

    if best_state_dict is not None:
        model.load_state_dict(best_state_dict)

    print("\nCalculating error...")
    model.eval()
    eval_chunk = 262_144
    abs_errors_list = []
    with torch.no_grad():
        for start in range(0, num_samples, eval_chunk):
            preds = torch.clamp(model(X_train[start:start + eval_chunk]), 0.0, 1.0)
            abs_errors_list.append(torch.abs(y_train[start:start + eval_chunk] - preds).cpu())
    all_errors = torch.cat(abs_errors_list, dim=0).numpy()
    uniform_errors = all_errors[:num_uniform]

    _, _, active_neurons, dead_count, folded_count = extract_pruned_weights(model)
    print(
        f"Active nonlinear neurons : {len(active_neurons)}/{neurons} "
        f"(folded always-on linear: {folded_count}, pruned dead: {dead_count})"
    )
    print(f"Mean Squared Error (MSE) : {np.mean(uniform_errors ** 2):.6f}")
    print(f"50.0th percentile error  : {np.percentile(uniform_errors, 50):.6f}")
    print(f"90.0th percentile error  : {np.percentile(uniform_errors, 90):.6f}")
    print(f"99.0th percentile error  : {np.percentile(uniform_errors, 99):.6f}")
    print(f"99.9th percentile error  : {np.percentile(uniform_errors, 99.9):.6f}")
    print(f"Maximum absolute error   : {np.max(all_errors):.6f}")

    print("\n>> Executable Python Neural Network Function")
    print(generate_python_neural_network(model))

    print("\n>> Executable Kotlin Neural Network Function")
    print(generate_kotlin_neural_network(model))

    print("\n>> Executable GLSL Neural Network Function")
    print(generate_shader_neural_network(model))

if __name__ == '__main__':
    if len(sys.argv) == 1:
        print("python3 pigments_to_color.py [poly|poly3|nn]")
        print("  poly  : Fit a polynomial regression model to the pigment mixing data")
        print("  poly3 : Fit a polynomial regression model for each channel")
        print("  nn    : Fit a neural network model to the pigment mixing data")
        print("\nCommand help:")
        print("  python3 pigments_to_color.py poly  [degree]  [sample_size]")
        print("  python3 pigments_to_color.py poly3 [degree]  [sample_size]")
        print("  python3 pigments_to_color.py nn    [neurons] [sample_size]")
        sys.exit(0)

    command = sys.argv[1]

    if command == "poly":
        degree = int(sys.argv[2]) if len(sys.argv) > 2 else 2
        sample_size = int(sys.argv[3]) if len(sys.argv) > 3 else 384
        fit_mix(degree=degree, sample_size=sample_size)
    elif command == "poly3":
        degree = int(sys.argv[2]) if len(sys.argv) > 2 else 2
        sample_size = int(sys.argv[3]) if len(sys.argv) > 3 else 384
        fit3_mix(degree=degree, sample_size=sample_size)
    elif command == "nn":
        neurons = int(sys.argv[2]) if len(sys.argv) > 2 else 16
        sample_size = int(sys.argv[3]) if len(sys.argv) > 3 else 384
        fit_mix_nn(neurons=neurons, sample_size=sample_size)
