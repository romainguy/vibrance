import math
import numpy as np
import os
import pigments
from concurrent.futures import ProcessPoolExecutor
from sklearn.preprocessing import PolynomialFeatures
from sklearn.linear_model import Ridge
from sklearn.pipeline import make_pipeline
from sklearn.metrics import mean_squared_error

pg = pigments.Pigments(pigments.load_config_from_json("config.json"))

def chunk_processor(chunk):
    return pg.mix_linear(chunk)

def generate_python_function(feature_names, intercepts, coefficients, func_name="predict_mix"):
    py_features = [name.replace(" ", " * ").replace("^", "**") for name in feature_names]

    if len(coefficients.shape) == 1:
        intercepts = [intercepts]
        coefficients = [coefficients]

    num_outputs = len(intercepts)
    
    lines = [
        f"def {func_name}self, concentration):",
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

    lines.append("    ]), 0, 1)")
    
    return "\n".join(lines)


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

    print("Calculating Error...")
    predictions = model.predict(X_train)
    errors = np.abs(y_train - predictions)
    
    mse = np.mean(errors ** 2)
    max_err = np.max(errors)
    p50_err = np.percentile(errors, 50)
    p90_err = np.percentile(errors, 90)
    p99_err = np.percentile(errors, 99)
    p99_9_err = np.percentile(errors, 99.9)

    print(f"Mean Squared Error (MSE) : {mse:.6f}")
    print(f"50.0th Percentile Error  : {p50_err:.6f}")
    print(f"90.0th Percentile Error  : {p90_err:.6f}")
    print(f"99.0th Percentile Error  : {p99_err:.6f}")
    print(f"99.9th Percentile Error  : {p99_9_err:.6f}")
    print(f"Maximum Absolute Error   : {max_err:.6f}")

    print("\n>> Fitted Polynomial Parameters")

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
    generated_code = generate_python_function(feature_names, intercepts, coefficients)
    print(generated_code)

if __name__ == '__main__':
    fit_mix()
