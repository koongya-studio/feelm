"""Exercise the actual shell job against a stateful, isolated Docker double."""

import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "infra/scripts/ensure-kafka-topics.sh"
TOPICS = (
    "feelm.rating.v1", "feelm.dismiss.v1", "feelm.view.v1",
    "feelm.recommendation-click.v1", "feelm.ott-click.v1",
    "feelm.candidate-updated.v1", "feelm.party-refresh.v1",
    "feelm.party-candidate-updated.v1",
)
BASH = ("C:/Program Files/Git/bin/bash.exe" if os.name == "nt" else shutil.which("bash"))
FAKE = r'''
docker() {
  printf '%s\n' "$*" >> calls
  [[ "$1" == exec && "$3" == /opt/kafka/bin/kafka-topics.sh ]] || return 90
  shift 5
  local action="$1" topic="" partitions="" replicas=""
  shift
  while (($#)); do
    case "$1" in
      --topic) topic="$2"; shift 2 ;;
      --partitions) partitions="$2"; shift 2 ;;
      --replication-factor) replicas="$2"; shift 2 ;;
      --if-not-exists) shift ;;
      *) return 91 ;;
    esac
  done
  case "$action" in
    --list)
      [[ "${FAIL_LIST:-}" != 1 ]] || return 10
      cat topics ;;
    --describe)
      [[ "${FAIL_DESCRIBE:-}" != 1 ]] || return 11
      [[ "$topic" == ^* && "$topic" == *'$' ]] || return 92
      topic="${topic#^}"; topic="${topic%\$}"; topic="${topic//\\/}"
      cat "$topic" ;;
    --create)
      [[ "${FAIL_CREATE:-}" != 1 ]] || return 12
      if ! grep -Fxq "$topic" topics; then
        printf '%s\n' "$topic" >> topics
        [[ "${RACE_MISMATCH:-}" != 1 ]] || replicas=2
        printf 'Topic: %s\tTopicId: sample\tPartitionCount: %s\tReplicationFactor: %s\tConfigs: \n' "$topic" "$partitions" "$replicas" > "$topic"
        local i
        for ((i=0; i<partitions; i++)); do
          printf 'Topic: %s\tPartition: %s\tLeader: 1\tReplicas: 1\tIsr: 1\n' "$topic" "$i" >> "$topic"
        done
      fi ;;
    *) return 93 ;;
  esac
}
export -f docker
bash "$TOPIC_SCRIPT"
'''


def description(topic, partitions=3, replication=1):
    header = f"Topic: {topic}\tTopicId: sample\tPartitionCount: {partitions}\tReplicationFactor: {replication}\tConfigs: \n"
    return header + "".join(
        f"Topic: {topic}\tPartition: {i}\tLeader: 1\tReplicas: "
        + ",".join(str(j + 1) for j in range(replication)) + "\tIsr: 1\n"
        for i in range(partitions)
    )


@unittest.skipUnless(BASH, "bash is required")
class ProvisioningTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.directory = Path(self.tmp.name)
        (self.directory / "topics").write_text("", encoding="utf-8")
        (self.directory / "fake.sh").write_text(FAKE, encoding="utf-8", newline="\n")
        (self.directory / "messages").write_bytes(b"existing messages and offsets")

    def seed(self, topic, content=None):
        with (self.directory / "topics").open("a", encoding="utf-8") as stream:
            stream.write(topic + "\n")
        (self.directory / topic).write_text(content or description(topic), encoding="utf-8")

    def run_job(self, **overrides):
        env = dict(os.environ, TOPIC_SCRIPT=SCRIPT.as_posix(),
                   KAFKA_TOPIC_PARTITIONS="3", KAFKA_TOPIC_READY_ATTEMPTS="2",
                   KAFKA_TOPIC_READY_INTERVAL_SECONDS="0")
        env.update(overrides)
        result = subprocess.run([BASH, "fake.sh"], cwd=self.directory, env=env,
                                capture_output=True, text=True, timeout=20)
        self.assertEqual((self.directory / "messages").read_bytes(), b"existing messages and offsets")
        return result

    def calls(self):
        path = self.directory / "calls"
        return path.read_text() if path.exists() else ""

    def test_first_run_and_repeat_preserve_existing_state(self):
        self.seed("unmanaged.topic", description("unmanaged.topic", 2, 2))
        first = self.run_job()
        self.assertEqual(first.returncode, 0, first.stderr)
        self.assertEqual(self.calls().count("--create"), len(TOPICS))
        before = {p.name: p.read_bytes() for p in self.directory.iterdir() if p.name != "calls"}
        second = self.run_job()
        self.assertEqual(second.returncode, 0, second.stderr)
        self.assertEqual(self.calls().count("--create"), len(TOPICS))
        self.assertEqual(before, {p.name: p.read_bytes() for p in self.directory.iterdir() if p.name != "calls"})

    def test_partial_state_only_creates_missing_topics(self):
        self.seed(TOPICS[0])
        result = self.run_job()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.calls().count("--create"), len(TOPICS) - 1)

    def test_existing_mismatch_prevents_all_creation(self):
        for content in (description(TOPICS[-1], 2), description(TOPICS[-1], 4),
                        description(TOPICS[-1], replication=2)):
            with self.subTest(content=content):
                (self.directory / "topics").write_text(TOPICS[-1] + "\n")
                (self.directory / TOPICS[-1]).write_text(content)
                result = self.run_job()
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("configuration mismatch", result.stderr)
                self.assertNotIn("--create", self.calls())
                self.assertEqual((self.directory / TOPICS[-1]).read_text(), content)

    def test_each_replica_assignment_and_metadata_completeness_are_checked(self):
        valid = description(TOPICS[0])
        cases = (valid.replace("Replicas: 1", "Replicas: 1,2", 1),
                 valid.rsplit("Topic:", 1)[0],
                 valid.replace("Partition: 2", "Partition: 1"),
                 valid.replace(TOPICS[0], "feelmXratingXv1"), "unexpected output\n")
        self.seed(TOPICS[0])
        for content in cases:
            with self.subTest(content=content):
                (self.directory / TOPICS[0]).write_text(content)
                self.assertNotEqual(self.run_job().returncode, 0)
                self.assertNotIn("--create", self.calls())

    def test_list_failure_never_creates(self):
        self.assertNotEqual(self.run_job(FAIL_LIST="1").returncode, 0)
        self.assertEqual(self.calls().count("--list"), 2)
        self.assertNotIn("--create", self.calls())

    def test_describe_failure_never_means_missing(self):
        self.seed(TOPICS[0])
        self.assertNotEqual(self.run_job(FAIL_DESCRIBE="1").returncode, 0)
        self.assertNotIn("--create", self.calls())

    def test_create_failure_and_concurrent_creation_mismatch_fail(self):
        self.assertNotEqual(self.run_job(FAIL_CREATE="1").returncode, 0)
        self.assertEqual((self.directory / "topics").read_text(), "")
        self.assertNotEqual(self.run_job(RACE_MISMATCH="1").returncode, 0)

    def test_invalid_inputs_fail_before_docker(self):
        for name, value in (("KAFKA_TOPIC_PARTITIONS", "0"),
                            ("KAFKA_TOPIC_READY_ATTEMPTS", "0"),
                            ("KAFKA_TOPIC_READY_ATTEMPTS", "a[1]"),
                            ("KAFKA_TOPIC_READY_INTERVAL_SECONDS", "-1")):
            with self.subTest(name=name):
                self.assertEqual(self.run_job(**{name: value}).returncode, 2)
                self.assertEqual(self.calls(), "")

    def test_job_is_automatic_protected_and_serialized(self):
        import yaml
        config = yaml.safe_load((ROOT / ".gitlab/ci/infra.yml").read_text(encoding="utf-8"))
        job = config["provision-kafka-topics"]
        self.assertEqual(job["tags"], ["e106-distributed"])
        self.assertEqual(job["resource_group"], config["deploy-kafka-config"]["resource_group"])
        self.assertFalse(job["allow_failure"])
        self.assertIn("ci-deploy-current-commit.py", job["script"][0])
        self.assertEqual(job["script"][1], "bash infra/scripts/ensure-kafka-topics.sh")
        self.assertEqual(job["rules"][0]["if"], '$CI_COMMIT_BRANCH == "main" && $CI_COMMIT_REF_PROTECTED == "true" && $CI_PIPELINE_SOURCE == "push"')
        self.assertNotIn("when", job["rules"][0])


if __name__ == "__main__":
    unittest.main()
