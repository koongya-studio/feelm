import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "infra" / "scripts" / "deploy-redis.sh"
BASH = os.environ.get("TEST_BASH", "bash")


class DeployRedisTests(unittest.TestCase):
    @staticmethod
    def _bash_path(path):
        value = Path(path).as_posix()
        if os.name == "nt" and len(value) >= 3 and value[1:3] == ":/":
            return f"/{value[0].lower()}/{value[3:]}"
        return value

    def setUp(self):
        self.temp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.temp, ignore_errors=True)
        self.bin = self.temp / "bin"
        self.bin.mkdir()
        self.previous = self.temp / "previous"
        self.previous.mkdir()
        shutil.copy(ROOT / "infra" / "redis" / "start.sh", self.previous / "start.sh")
        shutil.copy(ROOT / "infra" / "redis" / "readonly.acl", self.previous / "readonly.acl")
        shutil.copy(
            ROOT / "infra" / "redis" / "local-backend.acl",
            self.previous / "local-backend.acl",
        )
        self.state = self.temp / "state"
        self.state.write_text(self._bash_path(self.previous))
        self.events = self.temp / "events"
        self.release_root = self.temp / "releases-root"
        self.release_root.mkdir()
        self._write_fake_docker()
        self.config_verify = self._write_helper("config-verify.sh", 'exit "$CONFIG_VERIFY_EXIT"')
        self.runtime_verify = self._write_helper(
            "runtime-verify.sh",
            'count=0; test -f "$RUNTIME_COUNT" && count=$(cat "$RUNTIME_COUNT"); '
            'count=$((count + 1)); printf "%s" "$count" > "$RUNTIME_COUNT"; '
            'if test "$count" -eq 1; then exit "$RUNTIME_VERIFY_EXIT"; fi; '
            'exit "$ROLLBACK_VERIFY_EXIT"',
        )

    def _write_helper(self, name, body):
        path = self.bin / name
        path.write_text("#!/usr/bin/env bash\nset -eu\n" + body + "\n")
        path.chmod(0o755)
        return path

    def _write_fake_docker(self):
        path = self.bin / "docker"
        path.write_text(
            """#!/usr/bin/env bash
set -eu
printf '%s\\n' "$*" >>"$FAKE_EVENTS"
if [[ "$1" == "inspect" ]]; then
  if [[ "${*: -1}" == "feelm-backend" ]]; then
    printf '%s\\n' 'registry.example/feelm-backend:current'
  elif [[ "$*" == *State.Health* && "${*: -1}" == "feelm-redis" ]]; then
    count=0
    test -f "$HEALTH_COUNT" && count=$(cat "$HEALTH_COUNT")
    count=$((count + 1))
    printf '%s' "$count" >"$HEALTH_COUNT"
    if [[ "${FORCE_HEALTH_STATUS:-}" == "starting" ]]; then
      printf '%s\\n' starting
    elif [[ "${FORCE_HEALTH_STATUS:-}" == "unhealthy" ]]; then
      printf '%s\\n' unhealthy
    elif ((count <= ${STARTING_HEALTH_CHECKS:-0})); then
      printf '%s\\n' starting
    else
      printf '%s\\n' healthy
    fi
  elif [[ "$*" == *start.sh* ]]; then
    if [[ "${MISSING_PREVIOUS:-0}" == "1" && "$(cat "$FAKE_STATE")" == "$PREVIOUS_DIR" ]]; then
      printf '%s/start.sh\\n' "$PREVIOUS_DIR/missing"
    else
      printf '%s/start.sh\\n' "$(cat "$FAKE_STATE")"
    fi
  elif [[ "$*" == *readonly.acl* ]]; then
    if [[ "${MISSING_PREVIOUS:-0}" == "1" && "$(cat "$FAKE_STATE")" == "$PREVIOUS_DIR" ]]; then
      printf '%s/readonly.acl\\n' "$PREVIOUS_DIR/missing"
    else
      printf '%s/readonly.acl\\n' "$(cat "$FAKE_STATE")"
    fi
  elif [[ "$*" == *local-backend.acl* ]]; then
    if [[ ("${MISSING_PREVIOUS:-0}" == "1" || "${LEGACY_PREVIOUS:-0}" == "1") \
      && "$(cat "$FAKE_STATE")" == "$PREVIOUS_DIR" ]]; then
      printf '%s/local-backend.acl\\n' "$PREVIOUS_DIR/missing"
    else
      printf '%s/local-backend.acl\\n' "$(cat "$FAKE_STATE")"
    fi
  fi
  exit 0
fi
if [[ "$1" == "cp" ]]; then
  case "$2" in
    *start.sh) cp "$PREVIOUS_DIR/start.sh" "$3" ;;
    *readonly.acl) cp "$PREVIOUS_DIR/readonly.acl" "$3" ;;
    *local-backend.acl)
      if [[ "${LEGACY_PREVIOUS:-0}" == "1" ]]; then exit 1; fi
      cp "$PREVIOUS_DIR/local-backend.acl" "$3"
      ;;
    *) exit 2 ;;
  esac
  exit 0
fi
if [[ "$1" == "compose" ]]; then
  if [[ "$REDIS_CONFIG_DIR" == */releases/abcdef1234567890 \
    && "${FAIL_CANDIDATE_COMPOSE:-0}" == "1" ]]; then
    exit 1
  fi
  if [[ "$REDIS_CONFIG_DIR" != */releases/abcdef1234567890 \
    && "${FAIL_ROLLBACK_COMPOSE:-0}" == "1" ]]; then
    exit 1
  fi
  printf '%s' "$REDIS_CONFIG_DIR" >"$FAKE_STATE"
  printf '0' >"$HEALTH_COUNT"
  exit 0
fi
exit 2
"""
        )
        path.chmod(0o755)

    def run_deploy(self, **overrides):
        environment = os.environ.copy()
        environment.update(
            {
                "PATH": self._bash_path(self.bin) + ":" + environment["PATH"],
                "FAKE_STATE": self._bash_path(self.state),
                "FAKE_EVENTS": self._bash_path(self.events),
                "PREVIOUS_DIR": self._bash_path(self.previous),
                "REDIS_CONFIG_RELEASE_ROOT": self._bash_path(self.release_root),
                "REDIS_CONFIG_VERIFY_SCRIPT": self._bash_path(self.config_verify),
                "REDIS_RUNTIME_VERIFY_SCRIPT": self._bash_path(self.runtime_verify),
                "CONFIG_VERIFY_EXIT": "0",
                "RUNTIME_VERIFY_EXIT": "0",
                "ROLLBACK_VERIFY_EXIT": "0",
                "RUNTIME_COUNT": self._bash_path(self.temp / "runtime-count"),
                "HEALTH_COUNT": self._bash_path(self.temp / "health-count"),
                "DEPLOY_HEALTH_INTERVAL_SECONDS": "0",
            }
        )
        environment.update(overrides)
        return subprocess.run(
            [BASH, self._bash_path(SCRIPT), "abcdef1234567890"],
            cwd=ROOT,
            env=environment,
            text=True,
            encoding="utf-8",
            errors="replace",
            capture_output=True,
        )

    def test_installs_immutable_config_and_recreates_only_redis(self):
        result = self.run_deploy()
        self.assertEqual(result.returncode, 0, result.stderr)
        candidate = self.release_root / "releases" / "abcdef1234567890"
        self.assertEqual(self.state.read_text(), self._bash_path(candidate))
        self.assertEqual((candidate / "start.sh").read_bytes(), (ROOT / "infra/redis/start.sh").read_bytes())
        self.assertEqual(
            (candidate / "local-backend.acl").read_bytes(),
            (ROOT / "infra/redis/local-backend.acl").read_bytes(),
        )
        self.assertFalse((candidate / "deploy-trigger.txt").exists())
        events = self.events.read_text()
        self.assertIn("up -d --no-deps --force-recreate redis", events)
        self.assertNotIn(" down", events)
        self.assertNotIn("-v", events)

    def test_runtime_failure_restores_previous_config(self):
        result = self.run_deploy(RUNTIME_VERIFY_EXIT="1")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.state.read_text(), self._bash_path(self.previous))
        self.assertIn("previous configuration was restored", result.stderr)

    def test_waits_for_candidate_and_rollback_to_become_healthy(self):
        result = self.run_deploy(RUNTIME_VERIFY_EXIT="1", STARTING_HEALTH_CHECKS="2")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.state.read_text(), self._bash_path(self.previous))
        health_inspects = [
            event for event in self.events.read_text().splitlines() if "State.Health" in event
        ]
        self.assertEqual(len(health_inspects), 6)
        self.assertIn("previous configuration was restored", result.stderr)

    def test_health_timeout_fails_before_runtime_verification(self):
        result = self.run_deploy(
            FORCE_HEALTH_STATUS="starting",
            DEPLOY_HEALTH_ATTEMPTS="2",
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.temp / "runtime-count").exists())
        self.assertIn("did not become healthy: starting", result.stderr)
        self.assertIn("manual recovery is required", result.stderr)

    def test_missing_mount_sources_are_snapshotted_for_rollback(self):
        result = self.run_deploy(MISSING_PREVIOUS="1")
        self.assertEqual(result.returncode, 0, result.stderr)
        snapshot = self.release_root / "releases" / "bootstrap-abcdef1234567890"
        self.assertEqual(
            (snapshot / "start.sh").read_bytes(),
            (self.previous / "start.sh").read_bytes(),
        )
        self.assertEqual(
            (snapshot / "readonly.acl").read_bytes(),
            (self.previous / "readonly.acl").read_bytes(),
        )
        self.assertEqual(
            (snapshot / "local-backend.acl").read_bytes(),
            (self.previous / "local-backend.acl").read_bytes(),
        )
        events = self.events.read_text()
        self.assertIn("cp feelm-redis:/usr/local/etc/redis/start.sh", events)
        self.assertIn("cp feelm-redis:/usr/local/etc/redis/readonly.acl", events)
        self.assertIn("cp feelm-redis:/usr/local/etc/redis/local-backend.acl", events)

    def test_missing_mount_sources_rollback_to_snapshot(self):
        result = self.run_deploy(MISSING_PREVIOUS="1", RUNTIME_VERIFY_EXIT="1")
        self.assertNotEqual(result.returncode, 0)
        snapshot = self.release_root / "releases" / "bootstrap-abcdef1234567890"
        self.assertEqual(self.state.read_text(), self._bash_path(snapshot))
        self.assertIn("previous configuration was restored", result.stderr)

    def test_legacy_two_file_config_gets_rollback_compatible_snapshot(self):
        result = self.run_deploy(LEGACY_PREVIOUS="1")
        self.assertEqual(result.returncode, 0, result.stderr)
        snapshot = self.release_root / "releases" / "bootstrap-abcdef1234567890"
        self.assertEqual(
            (snapshot / "local-backend.acl").read_bytes(),
            (ROOT / "infra/redis/local-backend.acl").read_bytes(),
        )
        self.assertEqual(
            self.state.read_text(),
            self._bash_path(self.release_root / "releases" / "abcdef1234567890"),
        )

    def test_candidate_compose_failure_restores_previous_config(self):
        result = self.run_deploy(FAIL_CANDIDATE_COMPOSE="1")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.state.read_text(), self._bash_path(self.previous))
        self.assertIn("previous configuration was restored", result.stderr)

    def test_preflight_failure_does_not_recreate_redis(self):
        result = self.run_deploy(CONFIG_VERIFY_EXIT="1")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.state.read_text(), self._bash_path(self.previous))
        self.assertNotIn("compose", self.events.read_text())

    def test_rollback_failure_requires_manual_recovery(self):
        result = self.run_deploy(RUNTIME_VERIFY_EXIT="1", FAIL_ROLLBACK_COMPOSE="1")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("manual recovery is required", result.stderr)

    def test_invalid_revision_fails_before_docker(self):
        environment = os.environ.copy()
        environment["PATH"] = self._bash_path(self.bin) + ":" + environment["PATH"]
        result = subprocess.run(
            [BASH, self._bash_path(SCRIPT), "not-a-revision"],
            cwd=ROOT,
            env=environment,
            text=True,
            encoding="utf-8",
            errors="replace",
            capture_output=True,
        )
        self.assertEqual(result.returncode, 2)
        self.assertFalse(self.events.exists())


if __name__ == "__main__":
    unittest.main()
