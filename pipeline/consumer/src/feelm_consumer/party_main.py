import logging
import sys

from feelm_consumer.party_runner import run
from feelm_consumer.party_settings import load_party_settings


def main() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )
    if any(value == "--gbt-bundle" or value.startswith("--gbt-bundle=") for value in sys.argv[1:]):
        from feelm_consumer.gbt_party_runner import main as gbt_main
        gbt_main()
    else:
        run(load_party_settings())


if __name__ == "__main__":
    main()
