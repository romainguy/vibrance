import numpy as np
import pigments
import torch
from tqdm import tqdm

pg = pigments.Pigments(pigments.load_config_from_json("config.json"))

def generate_3d_lut(output_filename):
    print(f"Generating 256x256x256 LUT.")

    lut_3d = np.zeros((256, 256, 256, 3), dtype=np.float16)

    for r in tqdm(range(256), desc="Processing Red Slices"):
        g_index, b_index = torch.meshgrid(
            torch.arange(256), torch.arange(256), indexing='ij'
        )
        red_index = torch.full_like(g_index, r)

        target_rgb = torch.stack([red_index, g_index, b_index], dim=-1).float().to(pg.device) / 255.0
        mask = target_rgb <= 0.04045
        linear_rgb = torch.where(
            mask,
            target_rgb / 12.92,
            torch.pow((target_rgb + 0.055) / 1.055, 2.4)
        )
        concentrations = pg.unmix_torch(linear_rgb)

        slice_data = concentrations[..., :3].cpu().numpy().astype(np.float16)
        lut_3d[r] = slice_data

    print(f"\nSaving LUT to {output_filename}...")
    np.save(output_filename, lut_3d)

if __name__ == '__main__':
    data_config = pigments.load_data_config_from_json('data_config.json')
    generate_3d_lut(data_config.lut_path_npy)
