import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from infra.scripts.test_spark_code_delivery import job_block


ROOT = Path(__file__).resolve().parents[2]
DIGEST = "example/spark-ml@sha256:" + "b" * 64


class RuntimeDeliveryTests(unittest.TestCase):
    def test_ci_builds_runtime_before_code_and_history_waits_for_transaction(self):
        pipeline = (ROOT / ".gitlab/ci/pipeline.yml").read_text(encoding="utf-8")
        infra = (ROOT / ".gitlab/ci/infra.yml").read_text(encoding="utf-8")
        build = job_block(pipeline, "spark-code-image")
        self.assertLess(build.index("build-spark-runtime-image.sh"), build.index("build-spark-code-image.sh"))
        self.assertNotIn("infra/spark/runtime-image.env", build.split("  rules:")[0])
        for name in ("spark-code-image", "spark-code-notify"):
            self.assertIn("- job: " + name, job_block(infra, "deploy-spark-history"))
        for block in (build, job_block(pipeline, "spark-code-test"),
                      job_block(infra, ".spark-code-deploy"), job_block(infra, "deploy-spark-history")):
            self.assertIn("infra/scripts/build-spark-runtime-image.sh", block)

    def run_build(self, failure=""):
        bash = (str(Path(os.environ.get("ProgramFiles", "C:/Program Files")) / "Git/bin/bash.exe")
                if os.name == "nt" else shutil.which("bash"))
        if not bash or not Path(bash).exists():
            self.skipTest("POSIX bash is required")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fake = root / "docker"
            fake.write_text('''#!/usr/bin/env bash
set -eu
printf '%s\\n' "$*" >> "$EVENTS"
case "$1" in
  build) [[ "$FAILURE" != build ]] ;;
  run) [[ "$FAILURE" != test ]] ;;
  push) [[ "$FAILURE" != push ]] ;;
  pull) [[ "$FAILURE" != pull ]] ;;
  image)
    if [[ "$*" == *RepoDigests* ]]; then
      if [[ "$FAILURE" == digest ]]; then
        printf 'wrong/repository@sha256:%064d\\n' 0
      else
        printf '%s\\n' "$DIGEST"
      fi
    elif [[ "$FAILURE" == mismatch && "${!#}" == *@sha256:* ]]; then
      printf 'sha256:%064d\\n' 1
    else
      printf 'sha256:%064d\\n' 0
    fi ;;
  *) exit 1 ;;
esac
''', encoding="utf-8", newline="\n")
            fake.chmod(0o755)
            output = root / "runtime.env"
            output.write_text("SPARK_RUNTIME_IMAGE=stale\n", encoding="utf-8")
            events = root / "events"
            env = os.environ.copy()
            env.update(PATH=root.as_posix() + ":" + env["PATH"], FAILURE=failure,
                       EVENTS=events.as_posix(), DIGEST=DIGEST)
            result = subprocess.run([bash, str(ROOT / "infra/scripts/build-spark-runtime-image.sh"),
                                     "example/spark-ml:test", output.name],
                                    cwd=root, env=env, capture_output=True, text=True)
            return result, events.read_text(), output.read_text() if output.exists() else None

    def test_success_publishes_only_after_tests_and_verifies_published_digest(self):
        result, events, output = self.run_build()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(output, "SPARK_RUNTIME_IMAGE=" + DIGEST + "\n")
        self.assertLess(events.index("build "), events.index("run "))
        self.assertLess(events.index("/tmp/runtime-smoke.py"), events.index("push "))
        self.assertLess(events.index("push "), events.index("pull " + DIGEST))
        self.assertIn("--network none", events)

    def test_failures_never_emit_a_digest(self):
        for failure in ("build", "test", "push", "digest", "pull", "mismatch"):
            with self.subTest(failure=failure):
                result, events, output = self.run_build(failure)
                self.assertNotEqual(result.returncode, 0)
                self.assertIsNone(output)
                if failure in ("build", "test"):
                    self.assertNotIn("push ", events)


if __name__ == "__main__":
    unittest.main()
