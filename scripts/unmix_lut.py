import multiprocessing as mp
import numpy as np
import pigments
from tqdm import tqdm

pg = pigments.Pigments(pigments.load_config_from_json("config.json"))

def process_red_slice(r):
    # Store in float16 to save memory
    slice_data = np.zeros((256, 256, 3), dtype=np.float16)
    
    for g in range(256):
        for b in range(256):
            target_rgb = np.array([r, g, b], dtype=np.float64) / 255.0            
            concentrations = pg.unmix(target_rgb)
            # Only store the first 3 concentrations since they sum to 1
            slice_data[g, b] = concentrations[:3].astype(np.float16)

    return r, slice_data

def generate_3d_lut(output_filename="data/pigments_lut_256_fp16.npy"):
    print(f"Generating 256x256x256 LUT.")

    lut_3d = np.zeros((256, 256, 256, 3), dtype=np.float16)

    num_cores = max(1, mp.cpu_count() - 1)
    print(f"Distributing workload across {num_cores} cores.")

    with mp.Pool(processes=num_cores) as pool:
        r_values = list(range(256))
        results = tqdm(
            pool.imap_unordered(process_red_slice, r_values),
            total=256, 
            desc="Processing red slices"
        )
        for r, slice_data in results:
            lut_3d[r] = slice_data

    print(f"\nSaving LUT to {output_filename}...")
    np.save(output_filename, lut_3d)

    print(f"LUT successfully generated.")

if __name__ == '__main__':
    generate_3d_lut()
