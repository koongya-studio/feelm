from pathlib import Path
from unittest.mock import patch

from feelm_consumer.__main__ import configure_logging, main


def test_configure_logging_enables_non_sensitive_info_output() -> None:
    with patch("feelm_consumer.__main__.logging.basicConfig") as basic_config:
        configure_logging()

    basic_config.assert_called_once_with(
        level=20,
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )


def test_main_configures_logging_before_loading_and_running() -> None:
    calls: list[str] = []
    settings = object()

    with (
        patch(
            "feelm_consumer.__main__.configure_logging",
            side_effect=lambda: calls.append("logging"),
        ),
        patch(
            "feelm_consumer.__main__.load_settings",
            side_effect=lambda: calls.append("settings") or settings,
        ),
        patch(
            "feelm_consumer.__main__.run",
            side_effect=lambda actual: calls.append("run") if actual is settings else None,
        ),
    ):
        main()

    assert calls == ["logging", "settings", "run"]


def test_operational_log_templates_exclude_user_and_generation_identifiers() -> None:
    source = (Path(__file__).resolve().parents[1] / "src" / "feelm_consumer" / "runner.py").read_text(
        encoding="utf-8"
    )

    assert "user_id=%s" not in source
    assert "generation_id=%s" not in source
