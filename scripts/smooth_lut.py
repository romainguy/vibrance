import numpy as np
from scipy.ndimage import distance_transform_edt, gaussian_filter

import pigments

def repair_nonfinite_lut(lut, sigma=2.0):
    invalid_mask = ~np.isfinite(lut)
    if not np.any(invalid_mask):
        return lut

    invalid_count = int(np.count_nonzero(invalid_mask))
    print(f"Repairing {invalid_count} non-finite values in LUT...")

    repaired = lut.copy()

    for c in range(repaired.shape[-1]):
        ch_invalid = invalid_mask[..., c]
        if np.any(ch_invalid) and np.all(ch_invalid):
            repaired[..., c] = 0.0

    invalid_mask = ~np.isfinite(repaired)
    if not np.any(invalid_mask):
        return repaired

    first_mask = invalid_mask[..., 0]
    shared_mask = all(
        np.array_equal(first_mask, invalid_mask[..., c])
        for c in range(1, repaired.shape[-1])
    )

    if shared_mask:
        indices = distance_transform_edt(first_mask, return_distances=False, return_indices=True)
        repaired[first_mask] = repaired[tuple(idx[first_mask] for idx in indices)]
    else:
        for c in range(repaired.shape[-1]):
            ch_invalid = invalid_mask[..., c]
            if np.any(ch_invalid):
                indices = distance_transform_edt(ch_invalid, return_distances=False, return_indices=True)
                ch_data = repaired[..., c]
                ch_data[ch_invalid] = ch_data[tuple(idx[ch_invalid] for idx in indices)]

    any_invalid_3d = np.any(invalid_mask, axis=-1)
    coords = np.argwhere(any_invalid_3d)
    base_sigma = max(float(sigma), 1.0)
    relax_sigmas = (base_sigma * 2.0, base_sigma, base_sigma)
    pad = int(np.ceil(4.0 * relax_sigmas[0])) + 1

    mins = np.maximum(coords.min(axis=0) - pad, 0)
    maxs = np.minimum(coords.max(axis=0) + pad + 1, repaired.shape[:3])

    slices = (
        slice(mins[0], maxs[0]),
        slice(mins[1], maxs[1]),
        slice(mins[2], maxs[2]),
        slice(None)
    )
    sub_lut = repaired[slices]
    sub_invalid = invalid_mask[slices]

    for s in relax_sigmas:
        blurred_sub = gaussian_filter(sub_lut, sigma=(s, s, s, 0))
        sub_lut[sub_invalid] = blurred_sub[sub_invalid]

    repaired[slices] = sub_lut
    return repaired

def smooth_lut_cliff(input_path, output_path, sigma=2.0, z_blend_start=205, z_blend_full=220):
    print(f"Loading original LUT from {input_path}...")
    lut = np.load(input_path).astype(np.float32)
    lut = repair_nonfinite_lut(lut, sigma=sigma)
    
    print(f"Applying 3D Gaussian Blur (Sigma={sigma})...")
    smoothed_lut = gaussian_filter(lut, sigma=(sigma, sigma, sigma, 0))

    print(f"Building seamless Z-axis blending mask ({z_blend_start} to {z_blend_full})...")
    z_size = lut.shape[2]
    mask_1d = np.zeros(z_size, dtype=np.float32)
    
    for z in range(z_size):
        if z >= z_blend_full:
            mask_1d[z] = 1.0 # 100% Smoothed
        elif z > z_blend_start:
            # Linear cross-fade
            mask_1d[z] = (z - z_blend_start) / (z_blend_full - z_blend_start)
        else:
            mask_1d[z] = 0.0 # 100% Original
            
    mask_3d = mask_1d.reshape(1, 1, z_size, 1)

    print("Blending datasets...")
    final_lut = np.where(
        mask_3d <= 0.0,
        lut,
        np.where(
            mask_3d >= 1.0,
            smoothed_lut,
            (lut * (1.0 - mask_3d)) + (smoothed_lut * mask_3d)
        )
    )

    f16_info = np.finfo(np.float16)
    output_lut = np.clip(final_lut, f16_info.min, f16_info.max).astype(np.float16)
    if not np.all(np.isfinite(output_lut)):
        raise RuntimeError("Non-finite values detected in smoothed LUT output.")

    print(f"Saving smoothed LUT to {output_path}...")
    np.save(output_path, output_lut)
    
    print("Data conditioning complete!")

if __name__ == '__main__':
    data_config = pigments.load_data_config_from_json('data_config.json')

    smooth_lut_cliff(
        data_config.lut_path_npy,
        data_config.lut_path_npy.replace(".npy", "_smooth.npy"),
        sigma=2.0
    )
