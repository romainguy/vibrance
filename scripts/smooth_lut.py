import numpy as np
from scipy.ndimage import gaussian_filter

import pigments

def smooth_lut_cliff(input_path, output_path, sigma=2.0, z_blend_start=205, z_blend_full=220):
    print(f"Loading original LUT from {input_path}...")
    lut = np.load(input_path).astype(np.float32) 
    
    print(f"Applying 3D Gaussian Blur (Sigma={sigma})...")
    smoothed_lut = gaussian_filter(lut, sigma=(sigma, sigma, sigma, 0))

    print(f"Building seamless Z-axis blending mask ({z_blend_start} to {z_blend_full})...")
    mask_1d = np.zeros(256, dtype=np.float32)
    
    for z in range(256):
        if z >= z_blend_full:
            mask_1d[z] = 1.0 # 100% Smoothed
        elif z > z_blend_start:
            # Linear cross-fade
            mask_1d[z] = (z - z_blend_start) / (z_blend_full - z_blend_start)
        else:
            mask_1d[z] = 0.0 # 100% Original
            
    mask_3d = mask_1d.reshape(1, 1, 256, 1)

    print("Blending datasets...")
    final_lut = (lut * (1.0 - mask_3d)) + (smoothed_lut * mask_3d)

    print(f"Saving smoothed LUT to {output_path}...")
    np.save(output_path, final_lut.astype(np.float16))
    
    print("Data conditioning complete!")

if __name__ == '__main__':
    data_config = pigments.load_data_config_from_json('data_config.json')

    smooth_lut_cliff(
        data_config.lut_path_npy,
        data_config.lut_path_npy.replace(".npy", "_smooth.npy"),
        sigma=2.0
    )
