import numpy as np
import pigments

def convert_to_uint8(input_file, output_file):
    try:
        arr = np.load(input_file)
        arr_uint8 = (arr * 255).astype(np.uint8)
        np.save(output_file, arr_uint8)
        print(f"Success!\nArray of shape {arr.shape} converted and saved to '{output_file}'.")
        return arr_uint8
    except FileNotFoundError:
        print(f"Error: The file '{input_file}' was not found.")
    except Exception as e:
        print(f"An error occurred: {e}")

if __name__ == "__main__":
    data_config = pigments.load_data_config_from_json('data_config.json')
    convert_to_uint8(
        data_config.lut_path_npy,
        data_config.lut_uint8_path_npy
    )
