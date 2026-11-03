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
        mix_path_glsl
    ):
        self.model_path_onnx = model_path_onnx
        self.model_path_json = model_path_json
        self.model_path_kotlin = model_path_kotlin
        self.mix_path_python = mix_path_python
        self.mix_path_kotlin = mix_path_kotlin
        self.mix_path_glsl = mix_path_glsl
        self.lut_path_npy = lut_path_npy

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

        dL = self.L[1:] - self.L[:-1]
        trap_w = torch.zeros_like(self.L)
        trap_w[:-1] += 0.5 * dL
        trap_w[1:] += 0.5 * dL
        M_XYZ_to_sRGB = torch.tensor([
            [ 3.2404542, -1.5371385, -0.4985314],
            [-0.9692660,  1.8760108,  0.0415560],
            [ 0.0556434, -0.2040259,  1.0572252]
        ], dtype=torch.float32, device=self.device)
        self.W_XYZ_to_sRGB = (self.XYZ_CMF_D65 * trap_w.unsqueeze(-1)) @ M_XYZ_to_sRGB.T

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
        q0 = c0 / (c0 + 0.0332745)
        q1 = c1 / (c1 + 0.1608746)
        q2 = c2 / (c2 + 0.1454633)

        r = -0.0129390 - c0 * 0.2120745 + c1 * 0.1423155 - c2 * 0.0327026 - q0 * 0.2147055 + q1 * 0.0014953 + q2 * 0.2047531
        g =  0.5305084 - c0 * 0.2843616 + c1 * 0.0290159 + c2 * 0.0523934 + q0 * 0.0007384 - q1 * 0.0022861 + q2 * 0.1423233
        b =  0.2569463 - c0 * 0.1744376 + c1 * 0.1079934 - c2 * 0.1422383 + q0 * 0.4219884 + q1 * 0.1455865 - q2 * 0.5431989

        h0 = max(0.0, 0.6575376 - c0 * 0.1624863 - c1 * 0.6449268 - c2 * 0.0052772 - q0 * 0.9485637 + q1 * 0.8710425 + q2 * 0.0061951)
        r += h0 * 0.0004355
        g -= h0 * 0.3014401
        b += h0 * 0.0294964

        h1 = max(0.0, -0.0042518 - c0 * 0.7684307 - c1 * 0.7733518 + c2 * 0.0044685 + q0 * 1.2524637 - q1 * 0.4744847 - q2 * 0.0076850)
        r -= h1 * 0.0499180
        g -= h1 * 0.0992915
        b -= h1 * 0.0012425

        h2 = max(0.0, -0.1788690 - c0 * 10.2090120 + c1 * 0.3642335 + c2 * 0.3557712 + q0 * 0.9909798 - q1 * 0.2126132 + q2 * 0.0214562)
        r += h2 * 0.1223768
        g -= h2 * 0.0077781
        b += h2 * 0.0060544

        h3 = max(0.0, -0.4557841 + c0 * 1.7087454 - c1 * 1.5985008 - c2 * 0.0139364 + q0 * 1.1168557 - q1 * 0.8197804 + q2 * 0.0737546)
        r += h3 * 0.0173154
        g -= h3 * 0.1492429
        b += h3 * 0.0216028

        h4 = max(0.0, 0.4302132 - c0 * 0.3540720 - c1 * 0.3396386 - c2 * 0.5612465 + q0 * 0.3263996 - q1 * 0.1404756 - q2 * 0.3299613)
        r -= h4 * 0.1025737
        g += h4 * 0.0235529
        b += h4 * 0.0308535

        h5 = max(0.0, 0.3587933 - c0 * 0.5608867 + c1 * 0.2945967 + c2 * 0.3569975 - q0 * 0.6758091 - q1 * 0.7032198 - q2 * 0.2544450)
        r += h5 * 0.1576087
        g += h5 * 0.0427019
        b += h5 * 0.0064689

        h6 = max(0.0, -0.6312193 + c0 * 1.4375213 + c1 * 1.9843206 - c2 * 0.6810503 + q0 * 0.1351266 - q1 * 0.0018242 + q2 * 1.5063683)
        r -= h6 * 0.1429056
        g -= h6 * 0.2263351
        b -= h6 * 0.1760660

        h7 = max(0.0, 1.6864694 + c0 * 0.8341602 + c1 * 0.0076333 - c2 * 0.7323461 - q0 * 1.2207905 - q1 * 0.9399308 + q2 * 0.2519864)
        r += h7 * 0.3703996
        g += h7 * 0.3478388
        b += h7 * 0.2377814

        h8 = max(0.0, -0.3494144 + c0 * 0.1696419 + c1 * 0.5067902 + c2 * 0.9021403 + q0 * 0.0778709 + q1 * 0.3719376 - q2 * 1.2122540)
        r -= h8 * 0.0058903
        g += h8 * 0.0035372
        b -= h8 * 0.1794676

        h9 = max(0.0, 0.2770979 + c0 * 0.1162788 - c1 * 1.1381509 + c2 * 0.5613435 + q0 * 0.3927891 + q1 * 0.1347073 - q2 * 1.0353149)
        r -= h9 * 0.0514020
        g -= h9 * 0.0051886
        b += h9 * 0.0303437

        h10 = max(0.0, -0.5527982 + c0 * 1.4122236 - c1 * 2.0394118 + c2 * 0.0352515 - q0 * 0.2328004 + q1 * 1.5217073 + q2 * 0.0426745)
        r -= h10 * 0.0399628
        g += h10 * 0.1283439
        b -= h10 * 0.0257132

        h11 = max(0.0, -1.0271721 + c0 * 1.2173704 + c1 * 1.3166165 + c2 * 0.8916770 + q0 * 0.0843863 + q1 * 0.0612218 + q2 * 0.9171246)
        r -= h11 * 0.0063686
        g += h11 * 0.0218381
        b += h11 * 0.0754636

        h12 = max(0.0, 1.1078291 + c0 * 0.2253718 - c1 * 0.1754768 + c2 * 0.4863618 - q0 * 1.0067890 + q1 * 1.2782205 - q2 * 0.6253537)
        r += h12 * 0.2591344
        g -= h12 * 0.1280557
        b += h12 * 0.0653370

        h13 = max(0.0, 0.0454005 - c0 * 0.3138996 - c1 * 0.2991522 - c2 * 0.4458051 - q0 * 0.1513797 - q1 * 0.6885449 + q2 * 1.2212912)
        r += h13 * 0.0546763
        g -= h13 * 0.0083258
        b -= h13 * 0.0980978

        h14 = max(0.0, -0.6238379 + c0 * 1.1830186 + c1 * 0.0061143 - c2 * 0.3207612 + q0 * 0.3984092 + q1 * 0.7408799 + q2 * 0.2368786)
        r += h14 * 0.1118720
        g += h14 * 0.0648990
        b -= h14 * 0.0045212

        h15 = max(0.0, 0.5407425 - c0 * 1.2993785 - c1 * 1.5102947 + c2 * 0.4130842 - q0 * 0.0953034 - q1 * 0.0718980 - q2 * 1.2532613)
        r += h15 * 0.1177613
        g += h15 * 0.3038324
        b += h15 * 0.3250189

        return [
            max(0.0, min(1.0, r)),
            max(0.0, min(1.0, g)),
            max(0.0, min(1.0, b))
        ]

    @staticmethod
    def _project_simplex(v):
        u, _ = torch.sort(v, dim=-1, descending=True)
        cssv = torch.cumsum(u, dim=-1) - 1.0
        ind = torch.arange(1, v.shape[-1] + 1, device=v.device, dtype=v.dtype)
        rho = (u - cssv / ind > 0).sum(dim=-1, keepdim=True)
        theta = torch.gather(cssv, -1, rho - 1) / rho.to(v.dtype)
        return torch.clamp(v - theta, min=0.0)

    @torch.no_grad()
    def _mix_val_and_grad(self, c, target):
        K = self.P_star_K.to(device=c.device, dtype=c.dtype)
        S = self.P_star_S.to(device=c.device, dtype=c.dtype)
        W_rgb = self.W_XYZ_to_sRGB.to(device=c.device, dtype=c.dtype)

        K_mix = torch.clamp(c @ K, min=0.0)
        S_mix = torch.clamp(c @ S, min=1e-7)
        invS = 1.0 / S_mix
        u = K_mix * invS
        a = 1.0 + u
        b = torch.sqrt(u * (u + 2.0) + 1e-12)
        R_inf = 1.0 / (a + b)

        if not self.config.use_substrate:
            R_mix = R_inf
            dRmix_du = -R_inf / b
            dRmix_dS = torch.zeros_like(u)
        else:
            X = self.config.paint_thickness
            Rg = self.config.canvas_reflectance
            SX = S_mix * X
            bSX = b * SX
            small = bSX < 1e-3
            safe = torch.clamp(bSX, min=1e-3, max=20.0)
            e2 = torch.exp(-2.0 * safe)
            om_e2 = 1.0 - e2
            coth_s = (1.0 + e2) / om_e2
            xcoth = torch.where(small, 1.0 + (bSX * bSX) / 3.0, safe * coth_s)
            d_xcoth = torch.where(
                small,
                (2.0 / 3.0) * bSX,
                coth_s - safe * (4.0 * e2) / (om_e2 * om_e2)
            )
            invSX = 1.0 / SX
            bcoth = xcoth * invSX

            num = 1.0 - Rg * (a - bcoth)
            den = a - Rg + bcoth
            inv_den = 1.0 / den
            R_raw = num * inv_den
            R_sub = torch.clamp(R_raw, 0.0, 1.0)
            big = bSX > 20.0
            R_mix = torch.where(big, R_inf, R_sub)

            in_01 = (R_raw >= 0.0).to(c.dtype) * (R_raw <= 1.0).to(c.dtype)
            inv_den2 = inv_den * inv_den
            dRsub_da = in_01 * (-Rg * den - num) * inv_den2
            dRsub_dbc = in_01 * (Rg * den - num) * inv_den2
            dRsub_du = dRsub_da + dRsub_dbc * (d_xcoth * (a / b))
            dRsub_dS = dRsub_dbc * (d_xcoth * bSX - xcoth) * (invS * invSX)
            dRmix_du = torch.where(big, -R_inf / b, dRsub_du)
            dRmix_dS = torch.where(big, torch.zeros_like(dRsub_dS), dRsub_dS)

        c_s = (1.0 - self.K1) * (1.0 - self.K2)
        om = 1.0 - self.K2 * R_mix
        R = c_s * R_mix / om
        rgb_raw = R @ W_rgb
        rgb = torch.clamp(rgb_raw, 0.0, 1.0)

        diff = rgb - target
        loss = 0.5 * torch.sum(diff * diff, dim=-1)
        d_rgb = diff * (rgb_raw >= 0.0).to(c.dtype) * (rgb_raw <= 1.0).to(c.dtype)
        d_Rmix = (d_rgb @ W_rgb.T) * (c_s / (om * om))

        d_Kmix = d_Rmix * dRmix_du * invS
        d_Smix = d_Rmix * (-dRmix_du * u * invS + dRmix_dS)
        grad_c = (d_Kmix @ K.T) + (d_Smix @ S.T)
        return loss, grad_c

    @torch.no_grad()
    def polish_unmix_simplex(self, c_init, target_rgb_tensor, steps=20):
        spatial_shape = target_rgb_tensor.shape[:-1]
        c = self._project_simplex(c_init.reshape(-1, 4))
        target = target_rgb_tensor.reshape(-1, 3)

        loss, g = self._mix_val_and_grad(c, target)
        alpha = torch.full((c.shape[0], 1), 0.15, device=c.device, dtype=c.dtype)
        c_prev = c.clone()
        g_prev = g.clone()

        for step in range(steps):
            if step > 0:
                active = torch.clamp((c > 1e-7).to(c.dtype) + (c_prev > 1e-7).to(c.dtype), max=1.0)
                s = (c - c_prev) * active
                n_act = torch.clamp(active.sum(dim=-1, keepdim=True), min=1.0)
                y_raw = (g - g_prev) * active
                y = y_raw - (y_raw.sum(dim=-1, keepdim=True) / n_act) * active
                sy = torch.sum(s * y, dim=-1, keepdim=True)
                ss = torch.sum(s * s, dim=-1, keepdim=True)
                yy = torch.sum(y * y, dim=-1, keepdim=True)
                bb = ss / torch.clamp(sy, min=1e-12) if (step % 2 == 0) else sy / torch.clamp(yy, min=1e-12)
                alpha = torch.where(sy > 1e-12, torch.clamp(bb, 1e-4, 20.0), alpha * 0.5)

            c_cand = self._project_simplex(c - alpha * g)
            loss_cand, g_cand = self._mix_val_and_grad(c_cand, target)
            improved = loss_cand <= loss
            m_imp = improved.unsqueeze(-1)
            c_prev = torch.where(m_imp, c, c_prev)
            g_prev = torch.where(m_imp, g, g_prev)
            c = torch.where(m_imp, c_cand, c)
            g = torch.where(m_imp, g_cand, g)
            loss = torch.where(improved, loss_cand, loss)
            alpha = torch.where(m_imp, alpha, torch.clamp(alpha * 0.25, min=1e-5))

        return c.reshape(*spatial_shape, 4)

    def unmix_torch(self, target_rgb_tensor, steps=500, lr=0.05, polish_steps=25):
        device = target_rgb_tensor.device
        spatial_shape = target_rgb_tensor.shape[:-1] 
        c_raw = torch.zeros((*spatial_shape, 4), device=device, requires_grad=True)
        
        optimizer = torch.optim.Adam([c_raw], lr=lr)
        K = self.P_star_K.to(device=device, dtype=target_rgb_tensor.dtype)
        S = self.P_star_S.to(device=device, dtype=target_rgb_tensor.dtype)
        
        for _ in range(steps):
            optimizer.zero_grad()
            c_valid = torch.nn.functional.softmax(c_raw, dim=-1)
            predicted_rgb = torch.clamp(self.mix_torch(c_valid, K, S), 0.0, 1.0)
            loss = torch.nn.functional.mse_loss(predicted_rgb, target_rgb_tensor)
            loss.backward()
            optimizer.step()
            
        c_init = torch.nn.functional.softmax(c_raw, dim=-1).detach()
        if polish_steps > 0:
            return self.polish_unmix_simplex(c_init, target_rgb_tensor, steps=polish_steps)
        return c_init

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
        K_mix = torch.clamp(torch.matmul(concentrations, K), min=0.0)
        S_mix = torch.clamp(torch.matmul(concentrations, S), min=1e-7)

        if not self.config.use_substrate:
            # Safe division: S_mix is clamped >= 1e-7
            KSmix = K_mix / S_mix
            # We add 1e-12 inside the root to ensure gradients remain stable
            b = torch.sqrt((KSmix ** 2) + (2.0 * KSmix) + 1e-12)
            R_mix = 1.0 / (1.0 + KSmix + b)
        else:
            # Substrate-aware mixing:
            u = K_mix / S_mix
            a = 1.0 + u
            b = torch.sqrt(u * (u + 2.0) + 1e-12)

            SX = S_mix * self.config.paint_thickness
            bSX = b * SX
            safe_bSX = torch.clamp(bSX, min=1e-3, max=20.0)
            e2 = torch.exp(-2.0 * safe_bSX)
            xcoth = torch.where(
                bSX < 1e-3,
                1.0 + (bSX * bSX) / 3.0,
                safe_bSX * (1.0 + e2) / (1.0 - e2)
            )
            bcoth = xcoth / SX

            num = 1.0 - self.config.canvas_reflectance * (a - bcoth)
            den = a - self.config.canvas_reflectance + bcoth

            mask = bSX > 20.0
            R_mix = torch.where(
                mask,
                1.0 / (a + b),
                torch.clamp(num / den, 0.0, 1.0)
            )

        # Saunderson Correction
        numerator = (1.0 - self.K1) * (1.0 - self.K2) * R_mix
        denominator = 1.0 - (self.K2 * R_mix)
        R = numerator / denominator

        # Integration and conversion to sRGB
        W_rgb = self.W_XYZ_to_sRGB.to(device=concentrations.device, dtype=concentrations.dtype)
        linear_rgb = torch.matmul(R, W_rgb)
        
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

