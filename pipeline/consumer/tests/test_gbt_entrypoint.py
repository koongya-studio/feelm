import sys

import pytest

from feelm_consumer import __main__ as entry
from feelm_consumer import gbt_personal_runner
from feelm_consumer.gbt_snapshot import valid_revision


@pytest.mark.parametrize("args", [["--gbt-bundle", "fixture"], ["--gbt-bundle=fixture"]])
def test_explicit_gbt_never_starts_legacy_runner(monkeypatch, args):
    calls = []
    monkeypatch.setattr(sys, "argv", ["feelm_consumer", *args])
    monkeypatch.setattr(gbt_personal_runner, "main", lambda: calls.append("GBT"))
    monkeypatch.setattr(entry, "load_settings", lambda: pytest.fail("GBT must not require ALS settings"))
    monkeypatch.setattr(entry, "run", lambda _: pytest.fail("GBT must not start ALS"))
    entry.main()
    assert calls == ["GBT"]


def test_revision_matches_java_utf16_limit():
    assert valid_revision("😀" * 50)
    assert not valid_revision("😀" * 51)
    assert not valid_revision("\ud800")
