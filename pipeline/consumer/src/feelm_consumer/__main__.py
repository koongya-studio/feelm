import logging
import sys

from feelm_consumer.deployment import gbt_deployment_args
from feelm_consumer.runner import run
from feelm_consumer.settings import load_settings


def configure_logging() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )


def main() -> None:
    configure_logging()
    if any(arg == "--gbt-bundle" or arg.startswith("--gbt-bundle=") for arg in sys.argv[1:]):
        from feelm_consumer.gbt_personal_runner import main as gbt_main
        gbt_main()
        return
    try:
        args = gbt_deployment_args("personal")
    except ValueError as exc:
        raise SystemExit(str(exc)) from None
    logging.getLogger(__name__).info("consumer runtime selected role=personal mode=%s", "GBT" if args else "legacy")
    if args is not None:
        from feelm_consumer.gbt_personal_runner import main as gbt_main
        gbt_main(args)
        return
    run(load_settings())
