import math
import sys
import numpy as np
import os
import pigments
from concurrent.futures import ProcessPoolExecutor
from sklearn.linear_model import Ridge, RidgeCV
from sklearn.neural_network import MLPRegressor
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
        lines.append(f"        val {channels[i]} = {formatted_equation}")
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

def generate_kotlin_neural_network(mlp_model, class_name="PigmentsToColorModel"):
    # Extract weights and biases
    W1, W2 = mlp_model.coefs_
    b1, b2 = mlp_model.intercepts_
    
    hidden_size = W1.shape[1]
    
    lines = [
        "import kotlin.math.max",
        "import kotlin.math.min",
        "",
        f"class {class_name} {{",
        "    // Layer 1 (Hidden) Weights and Biases",
        f"    private val W1 = arrayOf("
    ]
    
    # Write W1
    for i in range(W1.shape[0]):
        row = ", ".join([f"{w}f" for w in W1[i]])
        lines.append(f"        floatArrayOf({row}),")
    lines.append("    )")
    lines.append(f"    private val b1 = floatArrayOf({', '.join([f'{b}f' for b in b1])})")
    lines.append("")
    
    # Write W2
    lines.append("    // Layer 2 (Output) Weights and Biases")
    lines.append("    private val W2 = arrayOf(")
    for i in range(W2.shape[0]):
        row = ", ".join([f"{w}f" for w in W2[i]])
        lines.append(f"        floatArrayOf({row}),")
    lines.append("    )")
    lines.append(f"    private val b2 = floatArrayOf({', '.join([f'{b}f' for b in b2])})")
    lines.append("")
    
    # Write the Prediction Function
    lines.extend([
        "    fun predict(c0: Float, c1: Float, c2: Float, c3: Float): FloatArray {",
        "        val inputs = floatArrayOf(c0, c1, c2, c3)",
        f"        val hidden = FloatArray({hidden_size})",
        "",
        "        // Feed-forward Layer 1 with ReLU activation",
        f"        for (i in 0 until {hidden_size}) {{",
        "            var sum = b1[i]",
        "            for (j in 0 until 4) {",
        "                sum += inputs[j] * W1[j][i]",
        "            }",
        "            hidden[i] = max(0f, sum) // ReLU",
        "        }",
        "",
        "        // Feed-forward Layer 2 (Output)",
        "        val outputs = FloatArray(3)",
        "        for (i in 0 until 3) {",
        "            var sum = b2[i]",
        f"            for (j in 0 until {hidden_size}) {{",
        "                sum += hidden[j] * W2[j][i]",
        "            }",
        "            // Clamp between 0.0 and 1.0",
        "            outputs[i] = max(0f, min(1f, sum))",
        "        }",
        "",
        "        return outputs",
        "    }",
        "}"
    ])
    
    return "\n".join(lines)

def generate_python_neural_network(mlp_model):
    W1, W2 = mlp_model.coefs_
    b1, b2 = mlp_model.intercepts_
    hidden_size = W1.shape[1]

    lines = [
        "    def mix_as_nn(self, concentration):",
        "        # Layer 1 (Hidden) Weights and Biases",
        "        W1 = ["
    ]
    for i in range(W1.shape[0]):
        row = ", ".join([f"{w:.7f}" for w in W1[i]])
        lines.append(f"            [{row}],")
    lines.append("        ]")
    lines.append(f"        b1 = [{', '.join([f'{b:.7f}' for b in b1])}]")
    lines.append("")
    
    lines.append("        # Layer 2 (Output) Weights and Biases")
    lines.append("        W2 = [")
    for i in range(W2.shape[0]):
        row = ", ".join([f"{w:.7f}" for w in W2[i]])
        lines.append(f"            [{row}],")
    lines.append("        ]")
    lines.append(f"        b2 = [{', '.join([f'{b:.7f}' for b in b2])}]")
    lines.append("")
    
    lines.extend([
        "        c0 = concentration[0]  # blue",
        "        c1 = concentration[1]  # magenta",
        "        c2 = concentration[2]  # yellow",
        "        c3 = concentration[3]  # white",
        f"        hidden = [0.0] * {hidden_size}",
        "",
        "        # Feed-forward Layer 1 with ReLU activation",
        f"        for i in range({hidden_size}):",
        "            val = b1[i] + \\",
        "                  (c0 * W1[0][i]) + \\",
        "                  (c1 * W1[1][i]) + \\",
        "                  (c2 * W1[2][i]) + \\",
        "                  (c3 * W1[3][i])",
        "            hidden[i] = max(0.0, val) # ReLU",
        "",
        "        # Feed-forward Layer 2 (Output)",
        "        outputs = [0.0] * 3",
        "        for i in range(3):",
        "            val = b2[i]",
        f"            for j in range({hidden_size}):",
        "                val += hidden[j] * W2[j][i]",
        "            outputs[i] = max(0.0, min(1.0, val))",
        "",
        "        return outputs"
    ])
    
    return "\n".join(lines)

def fit_mix_nn(neurons=16, sample_size=384):
    print("Generating training data...")
    num_inputs = 4
    num_samples = math.comb(sample_size + num_inputs - 1, num_inputs - 1)

    X_train = np.random.dirichlet(alpha=np.ones(num_inputs), size=num_samples)

    num_cores = os.cpu_count() - 1
    chunks = np.array_split(X_train, num_cores)
    with ProcessPoolExecutor(max_workers=num_cores) as executor:
        results = list(executor.map(chunk_processor, chunks))
    y_train = np.vstack(results)

    print(f"Training neural network (1 hidden layer, {neurons} neurons)...")
    # A single hidden layer with specified number of neurons
    mlp = MLPRegressor(
        hidden_layer_sizes=(neurons,), 
        activation='relu', 
        solver='adam', 
        max_iter=2000, 
        random_state=42
    )
    mlp.fit(X_train, y_train)

    print("Calculating error...")
    predictions = mlp.predict(X_train)
    errors = np.abs(y_train - predictions)
    
    print(f"Mean Squared Error (MSE) : {np.mean(errors ** 2):.6f}")
    print(f"50.0th percentile error  : {np.percentile(errors, 50):.6f}")
    print(f"90.0th percentile error  : {np.percentile(errors, 90):.6f}")
    print(f"99.0th percentile error  : {np.percentile(errors, 99):.6f}")

    print("\n>> Executable Python Neural Network Class")
    print(generate_python_neural_network(mlp))

    print("\n>> Executable Kotlin Neural Network Class")
    print(generate_kotlin_neural_network(mlp))

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
