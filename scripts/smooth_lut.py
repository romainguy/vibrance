import os
import sys
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

def smooth_lut_cliff(input_path, output_path, steps=160, lam_base=1.5e-6, lam_oog=1.35e-2, **_ignored_kwargs):
    print(f"Loading LUT from {input_path}...")
    lut = np.load(input_path).astype(np.float32)
    lut = repair_nonfinite_lut(lut)

    print("Running 3D Dirichlet regularization...")
    pg = pigments.Pigments(pigments.load_config_from_json('config.json'))
    output_lut = pg.regularize_lut_3d(lut, steps=steps, lam_base=lam_base, lam_oog=lam_oog)

    print(f"Saving regularized LUT to {output_path}...")
    np.save(output_path, output_lut)

    print("Data conditioning complete!")

if __name__ == '__main__':
    data_config = pigments.load_data_config_from_json('data_config.json')
    default_output = data_config.lut_path_npy
    unsmooth_candidate = (
        default_output[:-4] + "_unsmooth.npy"
        if default_output.endswith(".npy")
        else default_output + "_unsmooth.npy"
    )
    default_input = unsmooth_candidate if os.path.exists(unsmooth_candidate) else default_output

    input_path = sys.argv[1] if len(sys.argv) > 1 else default_input
    output_path = sys.argv[2] if len(sys.argv) > 2 else default_output
    print(f"Input LUT : {input_path}")
    print(f"Output LUT: {output_path}")

    smooth_lut_cliff(input_path, output_path)
