import logging

from feelm_consumer.party_runner import run
from feelm_consumer.party_settings import load_party_settings


def main() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )
    run(load_party_settings())


if __name__ == "__main__":
    main()
