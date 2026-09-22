"""datastore-config-check가 비밀값 없이 compose를 해석할 수 있는지 검사한다.

이 테스트는 `infra-config-test`의 격리 러너에서 돌기 때문에 `.gitlab/`과 `infra/` 밖의
파일을 읽지 않는다(`ci-infra-mr-boundary.py`).
"""

from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]
COMPOSE = ROOT / "infra" / "compose" / "service.yml"
DATASTORE_CONFIG_CHECK = ROOT / "infra" / "scripts" / "verify-datastore-config.sh"
REQUIRED_COMPOSE_VARIABLE = re.compile(r"\$\{([A-Z][A-Z0-9_]*):\?")
PLACEHOLDER_EXPORT = re.compile(r'^export ([A-Z][A-Z0-9_]*)="([^"]*)"$', re.M)


class DatastoreConfigPlaceholderTest(unittest.TestCase):
    def test_every_required_compose_variable_has_a_placeholder(self):
        """필수 변수마다 고정 placeholder가 있어야 한다. 하나라도 빠지면 main
        Pipeline의 verify 단계가 멈추고 이후 test·build·deploy가 모두 skip된다
        (S15P21E106-803)."""
        required = set(REQUIRED_COMPOSE_VARIABLE.findall(
            COMPOSE.read_text(encoding="utf-8")))
        placeholders = dict(PLACEHOLDER_EXPORT.findall(
            DATASTORE_CONFIG_CHECK.read_text(encoding="utf-8")))
        self.assertTrue(required)
        for key in sorted(required):
            with self.subTest(key=key):
                self.assertIn(key, placeholders)
                self.assertNotEqual(placeholders[key], "")
                # export X="${X}"처럼 CI 변수를 그대로 끌어오는 우회를 막는다.
                self.assertNotIn(key, placeholders[key])


if __name__ == "__main__":
    unittest.main()
