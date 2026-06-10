import math
import numpy as np
import os
import pigments
from concurrent.futures import ProcessPoolExecutor
from sklearn.preprocessing import PolynomialFeatures
from sklearn.linear_model import LinearRegression
from sklearn.pipeline import make_pipeline
from sklearn.metrics import mean_squared_error

pg = pigments.Pigments(pigments.load_config_from_json("config.json"))

def chunk_processor(chunk):
    return pg.mix(chunk)

def fit_mix(sample_size=512):
    grid_points = []
    num_inputs = 4
    num_samples = math.comb(sample_size + num_inputs - 1, num_inputs - 1)

    grid_points = np.empty((num_samples, num_inputs), dtype=int)

    index = 0
    for i in range(sample_size + 1):
        for j in range(sample_size + 1 - i):
            for k in range(sample_size + 1 - i - j):
                l = sample_size - i - j - k
                grid_points[index] = [i, j, k, l]
                index += 1

    print(f"Generating data for {num_samples:,} samples...")
    X_train = grid_points / sample_size

    num_cores = os.cpu_count() - 1
    print(f"Using {num_cores} cores for parallel processing...")

    chunks = np.array_split(X_train, num_cores)
    with ProcessPoolExecutor(max_workers=num_cores) as executor:
        results = list(executor.map(chunk_processor, chunks))
    y_train = np.vstack(results)

    degree = 3
    model = make_pipeline(
        PolynomialFeatures(degree=degree, include_bias=False),
        LinearRegression()
    )

    print(f"Fitting degree-{degree} polynomial...")
    model.fit(X_train, y_train)

    print("Calculating Mean Squared Error...")
    mse = mean_squared_error(y_train, model.predict(X_train))
    print(f"Mean Squared Error (MSE): {mse:.6f}")

    print("\n>> Fitted Polynomial Parameters")

    poly_step = model.named_steps['polynomialfeatures']
    linear_step = model.named_steps['linearregression']

    feature_names = poly_step.get_feature_names_out(['c0', 'c1', 'c2', 'c3'])
    intercepts = linear_step.intercept_
    coefficients = linear_step.coef_

    formatter = {'float_kind': lambda x: f"{x: .7f}"}

    title = "constant"
    print(f"{title:>10} : {np.array2string(intercepts.T, formatter=formatter)}")
    for name, c in zip(feature_names, coefficients.T):
        print(f"{name:>10} : {np.array2string(c, formatter=formatter)}")

if __name__ == '__main__':
    fit_mix()
