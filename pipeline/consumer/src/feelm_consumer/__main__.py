from feelm_consumer.runner import run
from feelm_consumer.settings import load_settings


def main() -> None:
    run(load_settings())
