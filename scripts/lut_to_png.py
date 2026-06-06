import numpy as np
import pigments
import sys
from PIL import Image

def convert_npy_to_png_lut(input_npy_path, output_png_path, grid_size=33):
    print(f"Loading original LUT from {input_npy_path}...")
    lut_256 = np.load(input_npy_path)
    
    print(f"Downsampling to {grid_size}x{grid_size}x{grid_size}...")
    indices = np.linspace(0, 255, grid_size).astype(int)
    
    lut_downsampled = lut_256[indices, :, :, :]
    lut_downsampled = lut_downsampled[:, indices, :, :]
    lut_downsampled = lut_downsampled[:, :, indices, :]
    
    lut_uint8 = np.clip(lut_downsampled * 255.0, 0, 255).astype(np.uint8)
    lut_uint8.transpose(1, 0, 2, 3)

    z_slices = [lut_uint8[:, :, z, :] for z in range(grid_size)]
    
    final_2d_image = np.concatenate(z_slices, axis=1)
    
    print(f"Saving 2D LUT strip to {output_png_path}...")
    img = Image.fromarray(final_2d_image, mode='RGB')
    img.save(output_png_path)

if __name__ == '__main__':
    data_config = pigments.load_data_config_from_json('data_config.json')

    size = 33
    if len(sys.argv) > 1:
        try:
            size = int(sys.argv[1])
            if size < 2 or size > 256:
                raise ValueError("Size must be between 2 and 256.")
        except ValueError as e:
            print(f"Invalid size argument, must be an integer between 2 and 256.")
            sys.exit(1)

    output_file = f"data/pigments_lut_{size}x{size}x{size}.png"
    
    convert_npy_to_png_lut(data_config.lut_path_npy, output_file)
    