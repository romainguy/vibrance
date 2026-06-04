import json
import os
import pigments
import sys

def format_float(val):
    return f"{val}f"

def array_1d_to_kt(arr, indent_level):
    indent = "    " * indent_level
    inner_indent = "    " * (indent_level + 1)
    
    floats = [format_float(x) for x in arr]

    chunk_size = 8
    chunks = [", ".join(floats[i:i+chunk_size]) for i in range(0, len(floats), chunk_size)]
    
    if len(chunks) == 1:
        return f"floatArrayOf({chunks[0]})"
    
    body = ",\n".join(f"{inner_indent}{chunk}" for chunk in chunks)
    return f"floatArrayOf(\n{body}\n{indent})"

def array_2d_to_kt(arr, indent_level):
    """Convert a 2D Python list to a Kotlin Array<FloatArray> string."""
    indent = "    " * indent_level
    inner_indent = "    " * (indent_level + 1)
    
    inner_arrays = [array_1d_to_kt(row, indent_level + 1) for row in arr]
    body = ",\n".join(f"{inner_indent}{row}" for row in inner_arrays)
    
    return f"arrayOf(\n{body}\n{indent})"

def to_camel_case(key):
    """Convert JSON keys like 'net.0.weight' to Kotlin camelCase like 'net0Weight'."""
    parts = key.replace('.', '_').split('_')
    return parts[0] + ''.join(p.capitalize() for p in parts[1:])

def convert_json_to_kotlin(
    input_json_path,
    output_kt_path,
    object_name="PigmentsModelWeights"
):
    if not os.path.exists(input_json_path):
        print(f"Error: Could not find {input_json_path}")
        sys.exit(1)

    with open(input_json_path, 'r') as f:
        data = json.load(f)

    with open(output_kt_path, 'w') as f:
        f.write(f"object {object_name} {{\n")

        for key, value in data.items():
            kt_name = to_camel_case(key)
            
            if isinstance(value, list):
                if len(value) > 0 and isinstance(value[0], list):
                    kt_val = array_2d_to_kt(value, 1)
                    f.write(f"    val {kt_name}: Array<FloatArray> = {kt_val}\n\n")
                else:
                    kt_val = array_1d_to_kt(value, 1)
                    f.write(f"    val {kt_name}: FloatArray = {kt_val}\n\n")
                    
        f.write("}\n")
        
    print(f"Successfully converted {input_json_path} to {output_kt_path}!")

if __name__ == "__main__":
    data_config = pigments.load_data_config_from_json('data_config.json')
    convert_json_to_kotlin(data_config.model_path_json, data_config.model_path_kotlin)
