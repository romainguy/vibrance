import pigments

config = pigments.load_config_from_json('config.json')
if config.use_optimized_pigments:
    print("Pigments are already optimized.")
    print("To re-run optimization, set 'use_optimized_pigments' to False in config.json.")
    exit(0)

pg = pigments.Pigments(config)

data_config = pigments.load_data_config_from_json('data_config.json')
pg.optimize_pigments(
    data_config.pigments_K,
    data_config.pigments_S
)
