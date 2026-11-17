import json
import math
import numpy as np
import scipy as sp
from scipy.ndimage import distance_transform_edt, gaussian_filter
import torch
from tqdm import tqdm

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
        q0 = c0 / (c0 + 0.0398001)
        q1 = c1 / (c1 + 0.2109625)
        q2 = c2 / (c2 + 0.1513699)

        r =  0.0942057 + c0 * 0.1703877 + c1 * 0.2319316 - c2 * 0.1716009 + q0 * 0.0394091 - q1 * 0.0489831 + q2 * 0.1606205
        g =  0.3998840 - c0 * 0.6139438 - c1 * 0.0244454 - c2 * 0.0807311 - q0 * 0.1587674 - q1 * 0.0588993 + q2 * 0.2137057
        b =  0.0436599 + c0 * 0.1173228 + c1 * 0.0383668 - c2 * 0.0129965 + q0 * 0.2922518 + q1 * 0.3375300 - q2 * 0.6318032

        u0 = 0.7617934 - c0 * 2.0405555 + c1 * 0.3112379 - c2 * 0.8018587 + q0 * 2.3255994 - q1 * 0.4363129 + q2 * 0.0858946
        s0 = 0.0 if u0 < 0.0 else (1.0 if u0 > 1.0 else u0)
        s0_sq = s0 * s0
        h0 = (u0 - 1.0 if u0 > 1.0 else 0.0) + s0_sq * (s0 - 0.5 * s0_sq)
        r -= h0 * 0.0846060
        g -= h0 * 0.0050965
        b += h0 * 0.0108343

        u1 = -0.3669254 + c0 * 0.5272365 + c1 * 0.0513481 + c2 * 0.1808509 + q0 * 0.7708003 + q1 * 0.0586200 + q2 * 0.2390250
        s1 = 0.0 if u1 < 0.0 else (1.0 if u1 > 1.0 else u1)
        s1_sq = s1 * s1
        h1 = (u1 - 1.0 if u1 > 1.0 else 0.0) + s1_sq * (s1 - 0.5 * s1_sq)
        r -= h1 * 0.7024046
        g -= h1 * 0.0563102
        b += h1 * 0.0369187

        u2 = 1.3967189 - c0 * 0.9044641 - c1 * 0.0164963 - c2 * 0.0648650 - q0 * 0.4116434 - q1 * 0.2583996 - q2 * 0.1407897
        s2 = 0.0 if u2 < 0.0 else (1.0 if u2 > 1.0 else u2)
        s2_sq = s2 * s2
        h2 = (u2 - 1.0 if u2 > 1.0 else 0.0) + s2_sq * (s2 - 0.5 * s2_sq)
        r += h2 * 0.0530008
        g += h2 * 0.2685580
        b += h2 * 0.2063591

        u3 = 1.9257392 + c0 * 0.4095860 + c1 * 0.2479234 + c2 * 0.4018383 - q0 * 1.7728392 - q1 * 0.2889820 - q2 * 0.3477753
        s3 = 0.0 if u3 < 0.0 else (1.0 if u3 > 1.0 else u3)
        s3_sq = s3 * s3
        h3 = (u3 - 1.0 if u3 > 1.0 else 0.0) + s3_sq * (s3 - 0.5 * s3_sq)
        r += h3 * 0.2273123
        g += h3 * 0.0223245
        b -= h3 * 0.0063244

        u4 = 0.5804944 + c0 * 0.1197487 - c1 * 0.3406990 + c2 * 0.1528339 - q0 * 0.7473584 - q1 * 0.1581982 - q2 * 0.0572037
        s4 = 0.0 if u4 < 0.0 else (1.0 if u4 > 1.0 else u4)
        s4_sq = s4 * s4
        h4 = (u4 - 1.0 if u4 > 1.0 else 0.0) + s4_sq * (s4 - 0.5 * s4_sq)
        r += h4 * 0.9123240
        g += h4 * 0.2034202
        b -= h4 * 0.1264191

        u5 = -0.0325220 + c0 * 0.3890758 - c1 * 0.4171871 + c2 * 0.0243509 + q0 * 0.3468475 + q1 * 0.1799036 - q2 * 0.3129576
        s5 = 0.0 if u5 < 0.0 else (1.0 if u5 > 1.0 else u5)
        s5_sq = s5 * s5
        h5 = (u5 - 1.0 if u5 > 1.0 else 0.0) + s5_sq * (s5 - 0.5 * s5_sq)
        r -= h5 * 0.3006504
        g -= h5 * 0.0019110
        b -= h5 * 0.5820184

        u6 = 0.1023628 - c0 * 0.0347458 + c1 * 0.6043723 + c2 * 0.0326402 - q0 * 0.0679549 + q1 * 0.2249385 + q2 * 0.2909658
        s6 = 0.0 if u6 < 0.0 else (1.0 if u6 > 1.0 else u6)
        s6_sq = s6 * s6
        h6 = (u6 - 1.0 if u6 > 1.0 else 0.0) + s6_sq * (s6 - 0.5 * s6_sq)
        r -= h6 * 0.3818003
        g -= h6 * 0.3901738
        b -= h6 * 0.0220673

        u7 = 0.4741918 - c0 * 1.0170648 + c1 * 0.1637024 - c2 * 0.0065886 - q0 * 0.0713866 - q1 * 0.6906144 - q2 * 0.0780988
        s7 = 0.0 if u7 < 0.0 else (1.0 if u7 > 1.0 else u7)
        s7_sq = s7 * s7
        h7 = (u7 - 1.0 if u7 > 1.0 else 0.0) + s7_sq * (s7 - 0.5 * s7_sq)
        r += h7 * 0.7496945
        g += h7 * 1.4022825
        b += h7 * 0.1176288

        u8 = 0.6980488 + c0 * 0.4319992 - c1 * 0.0626281 - c2 * 0.6592367 + q0 * 0.3713518 - q1 * 0.1318354 - q2 * 0.3573421
        s8 = 0.0 if u8 < 0.0 else (1.0 if u8 > 1.0 else u8)
        s8_sq = s8 * s8
        h8 = (u8 - 1.0 if u8 > 1.0 else 0.0) + s8_sq * (s8 - 0.5 * s8_sq)
        r -= h8 * 0.3760870
        g += h8 * 0.0907319
        b += h8 * 0.0117416

        u9 = 0.0680660 + c0 * 0.0858009 + c1 * 0.2646287 + c2 * 0.2600586 + q0 * 0.0216613 + q1 * 0.3207402 - q2 * 0.4917397
        s9 = 0.0 if u9 < 0.0 else (1.0 if u9 > 1.0 else u9)
        s9_sq = s9 * s9
        h9 = (u9 - 1.0 if u9 > 1.0 else 0.0) + s9_sq * (s9 - 0.5 * s9_sq)
        r -= h9 * 0.0526490
        g += h9 * 0.0008695
        b -= h9 * 1.1715037

        u10 = 0.4834386 - c0 * 0.5719878 - c1 * 1.1263524 - c2 * 0.2889980 - q0 * 0.0203989 - q1 * 0.0477446 - q2 * 0.5009462
        s10 = 0.0 if u10 < 0.0 else (1.0 if u10 > 1.0 else u10)
        s10_sq = s10 * s10
        h10 = (u10 - 1.0 if u10 > 1.0 else 0.0) + s10_sq * (s10 - 0.5 * s10_sq)
        r -= h10 * 0.4328068
        g += h10 * 0.2370650
        b += h10 * 0.9636616

        u11 = 3.3638372 + c0 * 1.7292200 - c1 * 1.5350319 - c2 * 0.0993377 - q0 * 1.8210709 + q1 * 0.7025958 - q2 * 1.3914435
        s11 = 0.0 if u11 < 0.0 else (1.0 if u11 > 1.0 else u11)
        s11_sq = s11 * s11
        h11 = (u11 - 1.0 if u11 > 1.0 else 0.0) + s11_sq * (s11 - 0.5 * s11_sq)
        r += h11 * 0.1149270
        g += h11 * 0.0251914
        b += h11 * 0.0850134

        u12 = 0.8389716 - c0 * 0.4684559 - c1 * 0.2579706 + c2 * 0.0343448 - q0 * 0.6279241 + q1 * 0.7005695 - q2 * 0.0209734
        s12 = 0.0 if u12 < 0.0 else (1.0 if u12 > 1.0 else u12)
        s12_sq = s12 * s12
        h12 = (u12 - 1.0 if u12 > 1.0 else 0.0) + s12_sq * (s12 - 0.5 * s12_sq)
        r += h12 * 0.1540918
        g -= h12 * 0.5343750
        b += h12 * 0.1404618

        u13 = 1.1363839 + c0 * 1.8413706 + c1 * 1.1003418 - c2 * 0.2236535 - q0 * 1.8478841 - q1 * 0.3094627 + q2 * 1.6426561
        s13 = 0.0 if u13 < 0.0 else (1.0 if u13 > 1.0 else u13)
        s13_sq = s13 * s13
        h13 = (u13 - 1.0 if u13 > 1.0 else 0.0) + s13_sq * (s13 - 0.5 * s13_sq)
        r += h13 * 0.0273948
        g -= h13 * 0.0074183
        b -= h13 * 0.0093523

        u14 = -0.6866699 + c0 * 1.0747178 + c1 * 0.0307113 + c2 * 0.0335598 + q0 * 0.9366120 + q1 * 0.4623602 + q2 * 0.0181216
        s14 = 0.0 if u14 < 0.0 else (1.0 if u14 > 1.0 else u14)
        s14_sq = s14 * s14
        h14 = (u14 - 1.0 if u14 > 1.0 else 0.0) + s14_sq * (s14 - 0.5 * s14_sq)
        r += h14 * 0.0695353
        g += h14 * 0.3202189
        b -= h14 * 0.1638511

        u15 = 1.1973850 - c0 * 0.0920046 - c1 * 0.8114011 + c2 * 0.0703730 - q0 * 0.0336330 - q1 * 0.4128532 - q2 * 0.3697207
        s15 = 0.0 if u15 < 0.0 else (1.0 if u15 > 1.0 else u15)
        s15_sq = s15 * s15
        h15 = (u15 - 1.0 if u15 > 1.0 else 0.0) + s15_sq * (s15 - 0.5 * s15_sq)
        r += h15 * 0.0843518
        g += h15 * 0.3336647
        b += h15 * 0.5230504

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

    @staticmethod
    def _repair_nonfinite_lut(lut, sigma=2.0):
        invalid_mask = ~np.isfinite(lut)
        if not np.any(invalid_mask):
            return lut
        repaired = lut.copy()
        for c in range(repaired.shape[-1]):
            ch_invalid = invalid_mask[..., c]
            if np.any(ch_invalid) and np.all(ch_invalid):
                repaired[..., c] = 0.0
        invalid_mask = ~np.isfinite(repaired)
        if not np.any(invalid_mask):
            return repaired
        for c in range(repaired.shape[-1]):
            ch_invalid = invalid_mask[..., c]
            if np.any(ch_invalid):
                indices = distance_transform_edt(ch_invalid, return_distances=False, return_indices=True)
                ch_data = repaired[..., c]
                ch_data[ch_invalid] = ch_data[tuple(idx[ch_invalid] for idx in indices)]
        any_invalid_3d = np.any(invalid_mask, axis=-1)
        coords = np.argwhere(any_invalid_3d)
        base_sigma = max(float(sigma), 1.0)
        pad = int(np.ceil(8.0 * base_sigma)) + 1
        mins = np.maximum(coords.min(axis=0) - pad, 0)
        maxs = np.minimum(coords.max(axis=0) + pad + 1, repaired.shape[:3])
        slices = (slice(mins[0], maxs[0]), slice(mins[1], maxs[1]), slice(mins[2], maxs[2]), slice(None))
        sub_lut = repaired[slices]
        sub_invalid = invalid_mask[slices]
        for s in (base_sigma * 2.0, base_sigma):
            blurred_sub = gaussian_filter(sub_lut, sigma=(s, s, s, 0.0))
            sub_lut[sub_invalid] = blurred_sub[sub_invalid]
        repaired[slices] = sub_lut
        return repaired

    @torch.no_grad()
    def regularize_lut_3d(
        self,
        lut_3d,
        steps=160,
        lam_base=1.5e-6,
        lam_oog=1.35e-2,
        corner_sigma=0.06,
        chunk_slices=16
    ):
        lut_f32 = np.asarray(lut_3d, dtype=np.float32)
        if lut_f32.ndim != 4 or lut_f32.shape[3] != 3:
            raise ValueError(f"Expected 4D LUT of shape (D0, D1, D2, 3), got {lut_f32.shape}")

        lut_f32 = np.clip(self._repair_nonfinite_lut(lut_f32), 0.0, 1.0)
        D0, D1, D2, _ = lut_f32.shape
        raw_max_dr = float(np.linalg.norm(lut_f32[1:, :, :] - lut_f32[:-1, :, :], axis=-1).max()) if D0 > 1 else 0.0
        raw_max_dg = float(np.linalg.norm(lut_f32[:, 1:, :] - lut_f32[:, :-1, :], axis=-1).max()) if D1 > 1 else 0.0
        raw_max_db = float(np.linalg.norm(lut_f32[:, :, 1:] - lut_f32[:, :, :-1], axis=-1).max()) if D2 > 1 else 0.0

        r_coords = np.linspace(0.0, 1.0, D0, dtype=np.float32)
        g_coords = np.linspace(0.0, 1.0, D1, dtype=np.float32)
        b_coords = np.linspace(0.0, 1.0, D2, dtype=np.float32)
        gr, gg, gb = np.meshgrid(r_coords, g_coords, b_coords, indexing='ij')
        srgb = np.stack([gr, gg, gb], axis=-1)
        lin_target_np = EOTF_sRGB(srgb).astype(np.float32)

        # Taper regularization near the 8 RGB cube corners to keep pure pigment anchors sharp
        dr2 = np.minimum(r_coords, 1.0 - r_coords) ** 2
        dg2 = np.minimum(g_coords, 1.0 - g_coords) ** 2
        db2 = np.minimum(b_coords, 1.0 - b_coords) ** 2
        dist_corner_sq = dr2[:, None, None] + dg2[None, :, None] + db2[None, None, :]
        corner_taper = (1.0 - 0.99 * np.exp(-dist_corner_sq / (corner_sigma ** 2))).astype(np.float32)

        c4_raw = np.empty((D0, D1, D2, 4), dtype=np.float32)
        c4_raw[..., :3] = lut_f32
        c4_raw[..., 3] = np.maximum(0.0, 1.0 - lut_f32.sum(axis=-1))

        oog_sq = np.empty((D0, D1, D2), dtype=np.float32)
        for s in range(0, D0, chunk_slices):
            e = min(s + chunk_slices, D0)
            c_chunk = self._project_simplex(
                torch.tensor(c4_raw[s:e], dtype=torch.float32, device=self.device)
            )
            lin_chunk = torch.tensor(lin_target_np[s:e], dtype=torch.float32, device=self.device)
            pred = torch.clamp(self.mix_torch(c_chunk, self.P_star_K, self.P_star_S), 0.0, 1.0)
            oog_sq[s:e] = torch.sum((pred - lin_chunk) ** 2, dim=-1).cpu().numpy()

        sig_r = max(1.0, 3.0 * (D0 - 1) / 255.0)
        sig_g = max(1.0, 3.0 * (D1 - 1) / 255.0)
        sig_b = max(1.0, 3.0 * (D2 - 1) / 255.0)
        oog_smooth = gaussian_filter(oog_sq, sigma=(sig_r, sig_g, sig_b))

        # Tear-free warm start across out-of-gamut medial-axis discontinuities
        blur_sig_r = 14.0 * (D0 - 1) / 255.0
        blur_sig_g = 14.0 * (D1 - 1) / 255.0
        blur_sig_b = 7.0 * (D2 - 1) / 255.0
        lut_blur = gaussian_filter(lut_f32, sigma=(blur_sig_r, blur_sig_g, blur_sig_b, 0.0))
        alpha_init = (np.clip((oog_smooth - 0.002) / 0.015, 0.0, 1.0) * corner_taper)[..., None]
        c3_start = (1.0 - alpha_init) * lut_f32 + alpha_init * lut_blur
        c4_start = np.empty((D0, D1, D2, 4), dtype=np.float32)
        c4_start[..., :3] = c3_start
        c4_start[..., 3] = np.maximum(0.0, 1.0 - c3_start.sum(axis=-1))

        inv_h2_r = float((D0 - 1) ** 2)
        inv_h2_g = float((D1 - 1) ** 2)
        inv_h2_b = float((D2 - 1) ** 2)
        lam_field = torch.tensor(
            (lam_base + lam_oog * oog_smooth * corner_taper).astype(np.float32),
            dtype=torch.float32,
            device=self.device
        )
        wx = (0.5 * inv_h2_r * (lam_field[1:, :, :] + lam_field[:-1, :, :])).unsqueeze(-1)
        wy = (0.5 * inv_h2_g * (lam_field[:, 1:, :] + lam_field[:, :-1, :])).unsqueeze(-1)
        wz = (0.5 * inv_h2_b * (lam_field[:, :, 1:] + lam_field[:, :, :-1])).unsqueeze(-1)

        diag_dir = torch.zeros((D0, D1, D2, 1), dtype=torch.float32, device=self.device)
        diag_dir[1:, :, :] += 2.0 * wx
        diag_dir[:-1, :, :] += 2.0 * wx
        diag_dir[:, 1:, :] += 2.0 * wy
        diag_dir[:, :-1, :] += 2.0 * wy
        diag_dir[:, :, 1:] += 2.0 * wz
        diag_dir[:, :, :-1] += 2.0 * wz
        del lam_field

        lin_target = torch.tensor(lin_target_np, dtype=torch.float32, device=self.device)
        c = self._project_simplex(torch.tensor(c4_start, dtype=torch.float32, device=self.device))

        # Estimate initial per-voxel data Lipschitz bound via one small interior probe
        L_data = torch.empty((D0, D1, D2, 1), dtype=torch.float32, device=self.device)
        for s in range(0, D0, chunk_slices):
            e = min(s + chunk_slices, D0)
            c_s = c[s:e].reshape(-1, 4)
            t_s = lin_target[s:e].reshape(-1, 3)
            _, g0 = self._mix_val_and_grad(c_s, t_s)
            c_probe = 0.999 * c_s + 0.001 * 0.25
            _, g_probe = self._mix_val_and_grad(c_probe, t_s)
            lip = torch.norm(g_probe - g0, dim=-1, keepdim=True) / torch.clamp(
                torch.norm(c_probe - c_s, dim=-1, keepdim=True), min=1e-6
            )
            L_data[s:e] = torch.clamp(1.25 * lip, min=10.0, max=25000.0).reshape(e - s, D1, D2, 1)

        y = c.clone()
        c_next = torch.empty_like(c)
        y_prev = torch.empty_like(c)
        g_data_prev = torch.empty_like(c)
        grad = torch.empty_like(c)

        for it in tqdm(range(steps), desc="Regularizing 3D LUT"):
            for s in range(0, D0, chunk_slices):
                e = min(s + chunk_slices, D0)
                y_s = y[s:e]
                _, g_chunk = self._mix_val_and_grad(
                    y_s.reshape(-1, 4),
                    lin_target[s:e].reshape(-1, 3)
                )
                g_chunk = g_chunk.reshape(e - s, D1, D2, 4)
                grad[s:e] = g_chunk
                if it > 0:
                    dy_norm = torch.norm(y_s - y_prev[s:e], dim=-1, keepdim=True)
                    dg_norm = torch.norm(g_chunk - g_data_prev[s:e], dim=-1, keepdim=True)
                    secant_L = torch.where(dy_norm > 1e-5, dg_norm / torch.clamp(dy_norm, min=1e-5), L_data[s:e])
                    L_data[s:e] = torch.maximum(
                        0.95 * L_data[s:e],
                        torch.clamp(1.25 * secant_L, min=10.0, max=25000.0)
                    )
                y_prev[s:e] = y_s
                g_data_prev[s:e] = g_chunk

            dx = wx * (y[1:, :, :] - y[:-1, :, :])
            grad[1:, :, :] += dx
            grad[:-1, :, :] -= dx
            del dx

            dy = wy * (y[:, 1:, :] - y[:, :-1, :])
            grad[:, 1:, :] += dy
            grad[:, :-1, :] -= dy
            del dy

            dz = wz * (y[:, :, 1:] - y[:, :, :-1])
            grad[:, :, 1:] += dz
            grad[:, :, :-1] -= dz
            del dz

            beta = it / (it + 4.0)
            for s in range(0, D0, chunk_slices):
                e = min(s + chunk_slices, D0)
                inv_L_s = 0.85 / (L_data[s:e] + diag_dir[s:e])
                c_next_s = self._project_simplex(y[s:e] - inv_L_s * grad[s:e])
                c_next[s:e] = c_next_s
                y[s:e] = self._project_simplex(c_next_s + beta * (c_next_s - c[s:e]))
            c, c_next = c_next, c

        out_f32 = np.clip(c[..., :3].cpu().numpy(), 0.0, 1.0)
        reg_max_dr = float(np.linalg.norm(out_f32[1:, :, :] - out_f32[:-1, :, :], axis=-1).max()) if D0 > 1 else 0.0
        reg_max_dg = float(np.linalg.norm(out_f32[:, 1:, :] - out_f32[:, :-1, :], axis=-1).max()) if D1 > 1 else 0.0
        reg_max_db = float(np.linalg.norm(out_f32[:, :, 1:] - out_f32[:, :, :-1], axis=-1).max()) if D2 > 1 else 0.0
        print(
            f"3D LUT Dirichlet regularization max voxel step: "
            f"dR {raw_max_dr:.4f}->{reg_max_dr:.4f}, "
            f"dG {raw_max_dg:.4f}->{reg_max_dg:.4f}, "
            f"dB {raw_max_db:.4f}->{reg_max_db:.4f}"
        )

        f16_info = np.finfo(np.float16)
        out_f16 = np.clip(out_f32, f16_info.min, f16_info.max).astype(np.float16)
        if not np.all(np.isfinite(out_f16)):
            raise RuntimeError("Non-finite values detected in regularized LUT output.")
        return out_f16

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
        r1 = encode_remainder(rgb1, self.mix_linear(c1))

        c2 = self.unmix(rgb2)
        r2 = encode_remainder(rgb2, self.mix_linear(c2))

        c = (1 - t) * c1 + t * c2
        r = (1 - t) * r1 + t * r2

        return np.clip(OETF_sRGB(apply_remainder(self.mix_linear(c), r)), 0, 1)

    def latent_lerp(self, c1, r1, c2, r2, t):
        c = (1 - t) * c1 + t * c2
        r = (1 - t) * r1 + t * r2

        return np.clip(OETF_sRGB(apply_remainder(self.mix_linear(c), r)), 0, 1)

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

def encode_remainder(target_rgb, mixed_rgb, eps=0.01):
    mp = eps + (1.0 - 2.0 * eps) * np.asarray(mixed_rgb)
    target = np.clip(np.asarray(target_rgb), 0.0, 1.0)
    num = target * (1.0 - mp)
    return num / (num + (1.0 - target) * mp)

def apply_remainder(mixed_rgb, remainder, eps=0.01):
    b = np.clip(np.asarray(remainder), 0.0, 1.0)
    mp = eps + (1.0 - 2.0 * eps) * np.asarray(mixed_rgb)
    num = mp * b
    return np.clip(num / (num + (1.0 - mp) * (1.0 - b)), 0.0, 1.0)

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

