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
        model_path_onnx,
        model_path_json,
        model_path_kotlin,
        lut_path_npy,
        mix_path_python,
        mix_path_kotlin,
        mix_path_glsl,
        use_ohem
    ):
        self.model_path_onnx = model_path_onnx
        self.model_path_json = model_path_json
        self.model_path_kotlin = model_path_kotlin
        self.mix_path_python = mix_path_python
        self.mix_path_kotlin = mix_path_kotlin
        self.mix_path_glsl = mix_path_glsl
        self.lut_path_npy = lut_path_npy
        self.use_ohem = use_ohem

class PigmentsConfig:
    def __init__(
        self,
        use_optimized_pigments=True,
        use_substrate=True,
        paint_thickness=1.0,
        canvas_reflectance=1.0,
        optimization_sample_count=384
    ):
        """
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
        color = OETF_sRGB(rgb.T)
        if rgb.ndim == 1:
            return color.flatten()
        else:
            return color.T

    def mix_as_nn(self, concentration):
        c0, c1, c2, _ = concentration
        r =  0.1539308 - c0 * 1.5850313 + c1 * 0.0049084 - c2 * 0.1980831
        g = -0.2101102 - c0 * 0.4561743 - c1 * 0.1060945 + c2 * 0.0193492
        b =  0.1130296 - c0 * 0.2899411 + c1 * 0.0961012 - c2 * 2.8117707

        h0 = max(0.0, -0.5732316 + c0 * 2.4183984 + c1 * 0.1553283 + c2 * 0.0646514)
        r -= h0 * 0.4831170
        g -= h0 * 0.1122841
        b -= h0 * 0.0794509

        h1 = max(0.0, 0.5309075 - c0 * 6.0326037 - c1 * 0.0136373 + c2 * 0.0213502)
        r += h1 * 0.4891092
        g -= h1 * 0.0002045
        b -= h1 * 0.0159511

        h2 = max(0.0, -0.1674344 + c0 * 0.1293259 + c1 * 0.3301166 + c2 * 2.7173810)
        r -= h2 * 0.1710407
        g += h2 * 0.1534036
        b += h2 * 0.8488734

        h3 = max(0.0, 0.0287062 + c0 * 2.4400022 + c1 * 0.0027635 - c2 * 0.1977382)
        r -= h3 * 0.5497317
        g += h3 * 0.0361791
        b += h3 * 0.1983951

        h4 = max(0.0, 0.4402751 - c0 * 3.0921938 - c1 * 4.6753030 + c2 * 0.0586780)
        r += h4 * 0.1203473
        g += h4 * 0.4039454
        b -= h4 * 0.0212024

        h5 = max(0.0, 0.0922123 - c0 * 8.8464346 - c1 * 0.1681033 + c2 * 0.1123352)
        r += h5 * 1.0090508
        g += h5 * 0.1335829
        b -= h5 * 0.0686533

        h6 = max(0.0, -0.6625637 + c0 * 0.1312097 + c1 * 0.1675960 + c2 * 1.9033443)
        r -= h6 * 0.0016896
        g += h6 * 0.0071924
        b += h6 * 0.4053358

        h7 = max(0.0, 0.4586772 - c0 * 0.0998359 - c1 * 0.2719077 - c2 * 3.2554240)
        r -= h7 * 0.0355527
        g += h7 * 0.0159359
        b += h7 * 0.5412980

        h8 = max(0.0, 1.4274933 - c0 * 1.3719325 - c1 * 1.0621926 - c2 * 1.4547547)
        r += h8 * 0.1521681
        g += h8 * 0.3157202
        b += h8 * 0.4113466

        h9 = max(0.0, 0.6399117 - c0 * 1.0160148 - c1 * 2.0311494 - c2 * 0.0227793)
        r -= h9 * 0.0102088
        g += h9 * 0.3475253
        b -= h9 * 0.0262109

        h10 = max(0.0, 0.8562082 + c0 * 3.4214549 - c1 * 0.8499470 - c2 * 0.8562099)
        r -= h10 * 0.5840385
        g += h10 * 0.2226422
        b -= h10 * 0.0267674

        h11 = max(0.0, 0.9718049 - c0 * 4.0978346 - c1 * 0.2651648 - c2 * 0.1055439)
        r += h11 * 0.5092035
        g += h11 * 0.0837957
        b += h11 * 0.0589227

        h12 = max(0.0, -0.1236852 + c0 * 2.4840240 - c1 * 0.0313387 + c2 * 0.6974269)
        r += h12 * 0.3274774
        g += h12 * 0.0674772
        b += h12 * 0.0903832

        h13 = max(0.0, 0.2737229 - c0 * 7.6579404 - c1 * 0.0605621 + c2 * 0.0917635)
        r += h13 * 0.8107759
        g += h13 * 0.0513009
        b -= h13 * 0.0689548

        h14 = max(0.0, -0.5286661 + c0 * 1.7521223 + c1 * 2.2740808 + c2 * 0.6740580)
        r -= h14 * 0.0910447
        g += h14 * 0.0987217
        b -= h14 * 0.0352297

        h15 = max(0.0, -0.0743285 + c0 * 4.1528034 - c1 * 0.0005895 + c2 * 0.0491316)
        r += h15 * 1.3558975
        g -= h15 * 0.0600876
        b += h15 * 0.0716275

        return [
            max(0.0, min(1.0, r)),
            max(0.0, min(1.0, g)),
            max(0.0, min(1.0, b))
        ]

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

    def unmix(self, target_rgb):
        c0 = np.array([0.25, 0.25, 0.25, 0.25])
        bounds = [(0.0, 1.0) for _ in range(4)]

        def objective(c):
            predicted_rgb = self.mix_linear(c)
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

    def lerp(self, rgb1, rgb2, t):
        c1 = self.unmix(rgb1)
        r1 = np.asarray(rgb1) - self.mix_linear(c1)

        c2 = self.unmix(rgb2)
        r2 = np.asarray(rgb2) - self.mix_linear(c2)

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
        # nan_mask = linear_rgb < 0.0
        # safe_linear_rgb = torch.where(
        #     nan_mask, torch.ones_like(linear_rgb, device=self.device), linear_rgb)
        
        # mask = linear_rgb <= 0.0031308
        # srgb = torch.where(
        #     mask,
        #     12.92 * linear_rgb,
        #     1.055 * torch.pow(safe_linear_rgb, 1.0 / 2.4) - 0.055
        # )

        return linear_rgb

    def __optimize_pigments_adam(self, P_star_K, P_star_S, boundary_concentrations):
        print(f"Total: {boundary_concentrations.shape[0]} samples")

        # Initialize variables with log values for unconstrained optimization
        K_log = torch.nn.Parameter(torch.log(torch.clamp(P_star_K, 1e-6, None)))
        S_log = torch.nn.Parameter(torch.log(torch.clamp(P_star_S, 1e-6, None)))

        optimizer = torch.optim.Adam([K_log, S_log], lr=0.01)
        
        # Target
        with torch.no_grad():
            rgb = self.mix_torch(boundary_concentrations, P_star_K, P_star_S)
            psi_original = _psi_oklab(rgb)

        alpha = 100_000.0

        for epoch in range(50_000):
            optimizer.zero_grad()
            
            # Get physical parameters
            K_surrogate = torch.exp(torch.clamp(K_log, -10.0, 5.0))
            S_surrogate = torch.exp(torch.clamp(S_log, -10.0, 5.0))

            surrogate_rgb = self.mix_torch(boundary_concentrations, K_surrogate, S_surrogate)

            # E_push
            q = _phi_signed_distance(surrogate_rgb)
            q_outside = torch.maximum(q, torch.zeros_like(q))
            E_push = torch.mean(q_outside ** 2)

            # E_pull
            psi_surrogate = _psi_oklab(surrogate_rgb)
            E_pull = torch.mean(torch.linalg.norm(psi_surrogate - psi_original, dim=-1) ** 2)
            
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
            self.config.optimization_sample_count
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
    # Clamp to keep differentiation valid
    # rgb_clipped = torch.clamp(rgb, 0.0, 1.0)
    # mask = rgb_clipped <= 0.04045
    # linear_rgb = torch.where(
    #     mask,
    #     rgb_clipped / 12.92,
    #     torch.pow((rgb_clipped + 0.055) / 1.055, 2.4)
    # )
    linear_rgb = torch.clamp(rgb, 0.0, 1.0)

    m1 = torch.tensor([
        [0.4122214708, 0.5363325363, 0.0514459929],
        [0.2119034982, 0.6806995451, 0.1073969566],
        [0.0883024619, 0.2817188376, 0.6299787005]
    ], dtype=linear_rgb.dtype, device=linear_rgb.device)
    
    lms = torch.matmul(linear_rgb, m1.T)
    
    lms_cubed = torch.sign(lms) * torch.pow(torch.abs(lms) + 1e-8, 1.0 / 3.0)

    m2 = torch.tensor([
        [ 0.2104542553,  0.7936177850, -0.0040720468],
        [ 1.9779984951, -2.4285922050,  0.4505937099],
        [ 0.0259040371,  0.7827717662, -0.8086757660]
    ], dtype=lms_cubed.dtype, device=lms_cubed.device)

    oklab = torch.matmul(lms_cubed, m2.T)

    return oklab

