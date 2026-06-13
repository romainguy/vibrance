import json
import math
import numpy as np
import scipy as sp
import torch

def load_data_config_from_json(filepath):
    with open(filepath, 'r') as file:
        data = json.load(file)
    return DataConfig(**data)

def load_config_from_json(filepath):
    with open(filepath, 'r') as file:
        data = json.load(file)
    return PigmentsConfig(**data)

class DataConfig:
    def __init__(
        self,
        pigments_K,
        pigments_S,
        model_path_onnx,
        model_path_json,
        model_path_kotlin,
        lut_path_npy,
        lut_uint8_path_npy
    ):
        self.pigments_K = pigments_K
        self.pigments_S = pigments_S
        self.model_path_onnx = model_path_onnx
        self.model_path_json = model_path_json
        self.model_path_kotlin = model_path_kotlin
        self.lut_path_npy = lut_path_npy
        self.lut_uint8_path_npy = lut_uint8_path_npy

class PigmentsConfig:
    def __init__(
        self,
        use_optimized_pigments=True,
        use_substrate=True,
        paint_thickness=1.0,
        canvas_reflectance=1.0,
        optimization_sample_count=384
    ):
        """Calculate the final price with tax.
        Args:
            use_optimized_pigments (bool, optional): When set to false, we load the original
                pigments and run an optimization steps to produce RGB colors that are in-gamut.
                Otherwise, we load the optimized pigments that were produced by the optimization
                step.
            use_substrate (bool, optional): Whether to account for the substrate in the mixing
                model.
            paint_thickness (float, optional): Thickness of the paint layer in mm. This is used only
                when use_substrate is True.
            canvas_reflectance (float, optional): Reflectance of the canvas substrate, between 0 and 1.
            optimization_sample_count (int, optional): Number of boundary samples to use during the
                optimization of the pigments.                
        """
        self.use_optimized_pigments = use_optimized_pigments
        self.use_substrate = use_substrate
        self.paint_thickness = paint_thickness
        self.canvas_reflectance = canvas_reflectance
        self.optimization_sample_count = optimization_sample_count

class Pigments():
    # Saunderson correction coefficients
    # "Developing a spectral and colorimetric database of artist paint materials", Okumura, 2005
    K1 = 0.030
    K2 = 0.650

    def __init__(self, config):
        self.config = config
        if torch.backends.mps.is_available():
            self.device = torch.device("mps")
        elif torch.cuda.is_available():
            self.device = torch.device("cuda")
        else:
            self.device = torch.device("cpu")
            print("⚠️ Running PyTorch on CPU")
        self.__loadData()
        self.__loadPigmentData()

    def __loadData(self):
        dataStart = 1

        self.wavelengths = np.genfromtxt(
            'data/cie_illuminant_d65.csv',
            delimiter=',',
            usecols=(0),
            skip_header=dataStart
        )[::2]

        # Spectral power distribution for the CIE illuminant D65
        D65 = np.genfromtxt(
            'data/cie_illuminant_d65.csv',
            delimiter=',',
            usecols=(1),
            skip_header=dataStart
        )[::2]

        # Color matching function for the CIE 1964 Standard Observer 10 degrees
        # Transpose to 3 vectors for the x/y/z sets of values
        observer = np.transpose(
            np.genfromtxt('data/cie_cmf_10deg.csv',
                delimiter=',',
                usecols=(1, 2, 3),
                skip_header=dataStart
            )[::2]
        )

        # Normalization factor for the reflectance to XYZ step
        Y65 = 1 / sp.integrate.trapezoid((observer * D65)[1], self.wavelengths)

        self.normalized_observer_D65 = observer * D65 * Y65

        # For PyTorch optimization
        self.XYZ_CMF_D65 = torch.tensor(
            self.normalized_observer_D65, dtype=torch.float32, device=self.device).T
        self.L = torch.tensor(self.wavelengths, dtype=torch.float32, device=self.device)

    def __loadPigmentData(self):
        # Absorption (K) and scattering (S) coefficients from 380nm to 730nm
        # every 10nm, for each of the base pigments
        # We skip the last two entries because the data goes to 750nm
        self.K = np.transpose(
            np.genfromtxt(
                'data/golden_paints_optimized_K.csv' if self.config.use_optimized_pigments else 'data/golden_paints_K.csv',
                delimiter=',',
                usecols=(1, 2, 3, 4),
                skip_header=1,
                skip_footer=0 if self.config.use_optimized_pigments else 2
            )
        )

        self.S = np.transpose(
            np.genfromtxt(
                'data/golden_paints_optimized_S.csv' if self.config.use_optimized_pigments else 'data/golden_paints_S.csv',
                delimiter=',',
                usecols=(1, 2, 3, 4),
                skip_header=1,
                skip_footer=0 if self.config.use_optimized_pigments else 2
            )
        )

        self.P_star_K = torch.tensor(self.K, dtype=torch.float32, device=self.device)
        self.P_star_S = torch.tensor(self.S, dtype=torch.float32, device=self.device)

    def mix_linear(self, concentration):
        concentration = np.asarray(concentration)
        concentration2d = np.atleast_2d(concentration)

        Kmix = np.dot(concentration2d, self.K)
        Smix = np.dot(concentration2d, self.S)
        Smix = np.maximum(Smix, 1e-7)

        if not self.config.use_substrate:
            KSmix = Kmix / Smix
            Rmix = 1.0 + KSmix - np.sqrt(KSmix ** 2.0 + 2.0 * KSmix)
        else:
            a = 1.0 + (Kmix / Smix)
            b = np.sqrt(a * a - 1.0)
            bSX = b * Smix * self.config.paint_thickness
            Rmix = _substrate(a, b, bSX, self.config.canvas_reflectance)

        # Saunderson Correction
        R = ((1.0 - self.K1) * (1.0 - self.K2) * Rmix) / (1.0 - self.K2 * Rmix)
        R = np.clip(R, 0, 1)

        # Integration and conversion to sRGB
        observer = self.normalized_observer_D65[:, np.newaxis, :]
        R_exp = R[np.newaxis, :, :]

        xyz = sp.integrate.trapezoid(observer * R_exp, x=self.wavelengths, axis=-1)
        rgb = np.clip(np.array(_xyz_to_rgb(*xyz)), 0, 1)

        if concentration.ndim == 1:
            return rgb.flatten()
        else:
            return rgb.T

    def mix(self, concentration):
        rgb = self.mix_linear(concentration)
        color = rgb.T
        color = OETF_sRGB(color)
        if rgb.ndim == 1:
            return color.flatten()
        else:
            return color.T

    def unmix_torch(self, target_rgb_tensor, steps=500, lr=0.05):
        device = target_rgb_tensor.device
        spatial_shape = target_rgb_tensor.shape[:-1] 
        c_raw = torch.zeros((*spatial_shape, 4), device=device, requires_grad=True)
        
        optimizer = torch.optim.Adam([c_raw], lr=lr)
        
        for _ in range(steps):
            optimizer.zero_grad()
            c_valid = torch.nn.functional.softmax(c_raw, dim=-1)
            predicted_rgb = self.mix_torch(c_valid, self.P_star_K, self.P_star_S)
            loss = torch.nn.functional.mse_loss(predicted_rgb, target_rgb_tensor)
            loss.backward()
            optimizer.step()
            
        return torch.nn.functional.softmax(c_raw, dim=-1).detach()

    def unmix_lbfgs(self, target_rgb):
        c0 = np.array([0.25, 0.25, 0.25, 0.25])
        bounds = [(0.0, 1.0) for _ in range(4)]

        def objective(c):
            predicted_rgb = self.mix(c)
            mse_loss = np.linalg.norm(predicted_rgb - target_rgb) ** 2
            sum_penalty = 1000.0 * (np.sum(c) - 1.0) ** 2
            return mse_loss + sum_penalty

        result = sp.optimize.minimize(
            objective,
            c0,
            method='L-BFGS-B', 
            bounds=bounds
        )

        final_c = result.x / np.sum(result.x)
        return final_c if result.success else None

    def unmix(self, target_rgb):
        c0 = np.array([0.25, 0.25, 0.25, 0.25])

        def objective(c):
            predicted_rgb = self.mix(c)
            return np.linalg.norm(predicted_rgb - target_rgb) ** 2

        bounds = [(0.0, 1.0) for _ in range(4)]
        constraints = {'type': 'eq', 'fun': lambda c: np.sum(c) - 1.0}

        result = sp.optimize.minimize(
            objective,
            c0,
            method='SLSQP',
            bounds=bounds,
            constraints=constraints
        )

        return result.x if result.success else None

    def lerp(self, rgb1, rgb2, t):
        c1 = self.unmix(rgb1)
        r1 = EOTF_sRGB(np.asarray(rgb1)) - self.mix_linear(c1)

        c2 = self.unmix(rgb2)
        r2 = EOTF_sRGB(np.asarray(rgb2)) - self.mix_linear(c2)

        c = (1 - t) * c1 + t * c2
        r = (1 - t) * r1 + t * r2

        return np.clip(OETF_sRGB(self.mix_linear(c) + r), 0, 1)

    def latent_lerp(self, c1, r1, c2, r2, t):
        c = (1 - t) * c1 + t * c2
        r = (1 - t) * r1 + t * r2

        return np.clip(OETF_sRGB(self.mix_linear(c) + r), 0, 1)

    def __generate_boundary_samples(self, steps=256):
        x = torch.arange(steps + 1, device=self.device, dtype=torch.float32)
        y = torch.arange(steps + 1, device=self.device, dtype=torch.float32)
        
        X, Y = torch.meshgrid(x, y, indexing='ij')

        mask = (X + Y) <= steps
        
        c_a = X[mask]
        c_b = Y[mask]
        c_c = steps - c_a - c_b
        
        c_zero = torch.zeros_like(c_a)

        face_1 = torch.stack((c_zero, c_a, c_b, c_c), dim=1)
        face_2 = torch.stack((c_a, c_zero, c_b, c_c), dim=1)
        face_3 = torch.stack((c_a, c_b, c_zero, c_c), dim=1)
        face_4 = torch.stack((c_a, c_b, c_c, c_zero), dim=1)
        all_faces = torch.cat((face_1, face_2, face_3, face_4), dim=0)
        
        unique_samples = torch.unique(all_faces, dim=0)    
        return unique_samples.to(torch.float32) / float(steps)

    def mix_torch(self, concentrations, K, S):
        # Kubelka-Munk Mixing
        K_mix = torch.matmul(concentrations, K)
        S_mix = torch.matmul(concentrations, S)

        if not self.config.use_substrate:
            # Safe division: add 1e-7 to avoid dividing by 0 if S_mix becomes 0
            KSmix = torch.abs(K_mix) / (torch.abs(S_mix) + 1e-7)
            # We add 1e-12 inside the root to ensure gradients remain stable
            inside_root = (KSmix ** 2) + (2.0 * KSmix) + 1e-12
            R_mix = 1.0 + KSmix - torch.sqrt(inside_root)
        else:
            # Substrate-aware mixing:
            a = 1.0 + (K_mix / (S_mix + 1e-7))
            b = torch.sqrt(torch.clip(a * a - 1.0, 1e-12, None))

            bSX = b * S_mix * self.config.paint_thickness
            safe_bSX = torch.clip(bSX, max=20.0)

            coth = torch.cosh(safe_bSX) / (torch.sinh(safe_bSX) + 1e-7)

            num = 1.0 - self.config.canvas_reflectance * (a - b * coth)
            den = a - self.config.canvas_reflectance + b * coth

            mask = bSX > 20
            R_mix = torch.where(
                mask,
                a - b,
                torch.clip(num / (den + 1e-7), 0.0, 1.0)
            )

        # Saunderson Correction
        numerator = (1.0 - self.K1) * (1.0 - self.K2) * R_mix
        denominator = 1.0 - (self.K2 * R_mix)
        R = numerator / denominator

        # Integration and conversion to sRGB
        integrand = R.unsqueeze(-1) * self.XYZ_CMF_D65
        XYZ = torch.trapezoid(integrand, x=self.L, dim=-2)

        M_XYZ_to_sRGB = torch.tensor([
            [ 3.2404542, -1.5371385, -0.4985314],
            [-0.9692660,  1.8760108,  0.0415560],
            [ 0.0556434, -0.2040259,  1.0572252]
        ], dtype=torch.float32, device=concentrations.device)

        linear_rgb = torch.matmul(XYZ, M_XYZ_to_sRGB.T)
        
        # Mask out negaative values to keep pow differentiable
        nan_mask = linear_rgb < 0.0
        safe_linear_rgb = torch.where(
            nan_mask, torch.ones_like(linear_rgb, device=self.device), linear_rgb)
        
        mask = linear_rgb <= 0.0031308
        srgb = torch.where(
            mask,
            12.92 * linear_rgb,
            1.055 * torch.pow(safe_linear_rgb, 1.0 / 2.4) - 0.055
        )

        return srgb

    def __optimize_pigments_adam(self, P_star_K, P_star_S, boundary_concentrations):
        print(f"Total: {boundary_concentrations.shape[0]} samples")

        # Initialize variables with log values for unconstrained optimization
        K_log = torch.nn.Parameter(torch.log(torch.clamp(P_star_K, 1e-6, None)))
        S_log = torch.nn.Parameter(torch.log(torch.clamp(P_star_S, 1e-6, None)))

        optimizer = torch.optim.Adam([K_log, S_log], lr=0.01)
        
        # Target
        with torch.no_grad():
            orig_rgb = self.mix_torch(boundary_concentrations, P_star_K, P_star_S)
            psi_orig = _psi_oklab(orig_rgb)

        alpha = 100_000.0

        for epoch in range(50_000):
            optimizer.zero_grad()
            
            # Get physical parameters
            K_surr = torch.exp(torch.clamp(K_log, -10.0, 5.0))
            S_surr = torch.exp(torch.clamp(S_log, -10.0, 5.0))
            
            surr_rgb = self.mix_torch(boundary_concentrations, K_surr, S_surr)
            
            # E_push
            q = _phi_signed_distance(surr_rgb)
            q_outside = torch.maximum(q, torch.zeros_like(q))
            E_push = torch.mean(torch.maximum(torch.zeros_like(q_outside), q_outside) ** 2)

            # E_pull
            psi_surr = _psi_oklab(surr_rgb)
            E_pull = torch.mean(torch.linalg.norm(psi_surr - psi_orig, dim=-1) ** 2)
            
            loss = E_push + alpha * E_pull
            loss.backward()
            
            optimizer.step()
            
            # Adjust alpha and check for convergence
            with torch.no_grad():
                max_violation = torch.max(q_outside).item()
                if math.isnan(max_violation):
                    print("NaN detected in optimization. Stopping.")
                    print(q_outside)
                    break

                if epoch % 100 == 0:
                    print(f"👨‍💻 Optimizing with α = {alpha:.5e}")
                    print(f"  Outside of RGB cube: {torch.count_nonzero(q_outside > 0.0).item()}")
                    print(f"  Maximum distance: {max_violation:.6f}")

                if max_violation < 1.0 / 255.0:
                    print("🏆 Gamut successfully contained within RGB cube!")
                    print(f"  Outside of RGB cube: {torch.count_nonzero(q_outside > 0.0).item()}")
                    print(f"  Maximum distance: {max_violation:.6f}")
                    break

                if epoch % 400 == 0:
                    alpha *= 0.5

        return torch.exp(K_log).detach(), torch.exp(S_log).detach()

    def optimize_pigments(
        self,
        output_filename_K="data/golden_paints_optimized_K.csv",
        output_filename_S="data/golden_paints_optimized_S.csv"
    ):
        if self.config.use_optimized_pigments:
            raise ValueError(
                "Pigments are already optimized. "
                "Set use_optimized_pigments to False to run optimization again."
            )

        print(f"Optimizing with {self.config.optimization_sample_count} samples per pigment")

        boundary_concentrations = self.__generate_boundary_samples(
            self.config.optimization_sample_count, self.device
        )

        K_surrogate, S_surrogate = self.__optimize_pigments_adam(
            self.P_star_K, self.P_star_S, boundary_concentrations
        )

        Q_star_K = K_surrogate.T.cpu().detach().numpy()
        Q_star_S = S_surrogate.T.cpu().detach().numpy()

        Q_star_K = np.insert(Q_star_K, 0, self.wavelengths, axis=1)
        Q_star_S = np.insert(Q_star_S, 0, self.wavelengths, axis=1)

        header = "Wavelength,Phthalo Blue,Quinacridone Magenta,Arylide (Hansa) yellow,Titanium White"
        np.savetxt(output_filename_K, Q_star_K, delimiter=",", header=header, fmt='%.15f')
        np.savetxt(output_filename_S, Q_star_S, delimiter=",", header=header, fmt='%.15f')

def _xyz_to_rgb(x, y, z):
    r =  3.2406 * x + -1.5372 * y + -0.4986 * z
    g = -0.9689 * x +  1.8758 * y +  0.0415 * z
    b =  0.0557 * x + -0.2040 * y +  1.0570 * z

    return [r, g, b]

def EOTF_sRGB(x):
    return np.where(
        x <= 0.04045,
        (1.0 / 12.92) * x,
        ((x + 0.055) / 1.055) ** 2.4
    )

def OETF_sRGB(x):
    return np.where(
        x <= 0.0031308,
        12.92 * x,
        1.055 * (x ** (1.0 / 2.4)) - 0.055
    )

def _substrate(a, b, bSX, canvas_reflectance):
    result = np.empty_like(a)
    
    large_mask = bSX > 20
    small_mask = ~large_mask
    
    # Branch 1: bSX > 20
    result[large_mask] = a[large_mask] - b[large_mask]
    
    # Branch 2: bSX <= 20
    if np.any(small_mask):
        a_s = a[small_mask]
        b_s = b[small_mask]
        bSX_s = np.maximum(bSX[small_mask], 1e-12)
        
        coth = np.cosh(bSX_s) / np.sinh(bSX_s)
        num = 1.0 - canvas_reflectance * (a_s - b_s * coth)
        den = a_s - canvas_reflectance + b_s * coth
        result[small_mask] = np.clip(num / den, 0.0, 1.0)
        
    return result

def _phi_signed_distance(rgb):
    # Signed distance of the rgb parameter inside the unit RGB cube
    q = torch.abs(rgb - 0.5) - 0.5
    outside_dist = torch.linalg.norm(torch.maximum(q, torch.zeros_like(q)), dim=-1)
    inside_dist = torch.minimum(torch.max(q, dim=-1).values, torch.zeros_like(q[..., 0]))
    return outside_dist + inside_dist

def _psi_oklab(rgb):
    # 1. sRGB to Linear sRGB
    # Clamp to keep differentiation valid
    rgb_clipped = torch.clamp(rgb, 0.0, 1.0)
    mask = rgb_clipped <= 0.04045
    linear_rgb = torch.where(
        mask,
        rgb_clipped / 12.92,
        torch.pow((rgb_clipped + 0.055) / 1.055, 2.4)
    )
    
    # 2. Linear sRGB to LMS
    m1 = torch.tensor([
        [0.4122214708, 0.5363325363, 0.0514459929],
        [0.2119034982, 0.6806995451, 0.1073969566],
        [0.0883024619, 0.2817188376, 0.6299787005]
    ], dtype=linear_rgb.dtype, device=linear_rgb.device)
    
    lms = torch.matmul(linear_rgb, m1.T)
    
    # 3. Non-linear response
    # We add 1e-8 to keep differentiation stable
    lms_cubed = torch.sign(lms) * torch.pow(torch.abs(lms) + 1e-8, 1.0 / 3.0)
    
    # 4. LMS to Oklab
    m2 = torch.tensor([
        [ 0.2104542553,  0.7936177850, -0.0040720468],
        [ 1.9779984951, -2.4285922050,  0.4505937099],
        [ 0.0259040371,  0.7827717662, -0.8086757660]
    ], dtype=lms_cubed.dtype, device=lms_cubed.device)

    oklab = torch.matmul(lms_cubed, m2.T)

    return oklab

def _ndarray_to_kotlin(arr: np.ndarray, val_name: str = "myArray") -> str:
    if np.issubdtype(arr.dtype, np.floating):
        if arr.dtype == np.float32:
            kt_type = "Float"
            kt_array_func = "floatArrayOf"
            suffix = "f"
        else:
            kt_type = "Double"
            kt_array_func = "doubleArrayOf"
            suffix = ""
    elif np.issubdtype(arr.dtype, np.integer):
        kt_type = "Int"
        kt_array_func = "intArrayOf"
        suffix = ""
    elif np.issubdtype(arr.dtype, np.bool_):
        kt_type = "Boolean"
        kt_array_func = "booleanArrayOf"
        suffix = ""
    else:
        raise ValueError(f"Unsupported dtype: {arr.dtype}")

    def format_element(x):
        if kt_type == "Boolean":
            return str(x).lower()
        elif kt_type in ["Double", "Float"]:
            s = str(x)
            # Ensure floating points always have a decimal so Kotlin parses them correctly
            if "." not in s and "e" not in s.lower() and "nan" not in s.lower():
                s += ".0"
            return s + suffix
        return str(x)

    def build_string(current_arr, depth):
        indent = "    " * depth
        if current_arr.ndim == 1:
            elements = ", ".join(format_element(x) for x in current_arr)
            return f"{indent}{kt_array_func}({elements})"
        else:
            inner_strings = [build_string(sub, depth + 1) for sub in current_arr]
            joined_inner = ",\n".join(inner_strings)
            return f"{indent}arrayOf(\n{joined_inner}\n{indent})"

    type_signature = f"{kt_type}Array" if arr.ndim == 1 else "Array<" * (arr.ndim - 1) + f"{kt_type}Array" + ">" * (arr.ndim - 1)

    array_body = build_string(arr, 0).lstrip()
    return f"val {val_name}: {type_signature} = {array_body}"
