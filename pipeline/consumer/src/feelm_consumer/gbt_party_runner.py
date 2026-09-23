"""Explicit pinned GBT CLI, using existing party transport settings and topics."""

from __future__ import annotations

import argparse
import json
from dataclasses import dataclass

import redis

from feelm_consumer.gbt.errors import GbtError
from feelm_consumer.gbt.inputs import movie_id
from feelm_consumer.gbt_party_service import ProcessingReceipt, RecordSource, process_record
from feelm_consumer.gbt_party_store import PartyStore, PublishRejected
from feelm_consumer.gbt_snapshot import decode_snapshot, safe_identifier


def model_is_gbt(client) -> bool:
    try:
        current = json.loads(client.get("feelm:model:current") or "null")
    except (ValueError, TypeError):
        return False
    return isinstance(current, dict) and current.get("modelKind") == "GBT"


class RedisSnapshotReader:
    def __init__(self, client, max_snapshot_bytes: int):
        if type(max_snapshot_bytes) is not int or not 1 <= max_snapshot_bytes <= 64 * 1024**2:
            raise ValueError("explicit snapshot budget must be between1 and64MiB")
        self.client, self.max_snapshot_bytes = client, max_snapshot_bytes

    def read_snapshot(self, user_id, generation_id, **expected):
        key = f"feelm:user:{movie_id(user_id)}:input:{safe_identifier(generation_id)}"
        return decode_snapshot(self.client.get(key), user_id=user_id, generation_id=generation_id,
                               max_bytes=self.max_snapshot_bytes, **expected)


@dataclass
class GbtPartyRuntime:
    client: object
    scorer: object
    snapshot_reader: object
    legacy_bundle: object = None

    def process(self, message, fence, *, notify, requeue_member, is_still_owned):
        store = PartyStore(self.client)
        try:
            timestamp_ms = message.timestamp()[1]
            source = RecordSource(message.topic(), message.partition(), message.offset(), timestamp_ms)
        except (GbtError, TypeError, IndexError):
            source = RecordSource(message.topic(), message.partition(), message.offset(), 0)
            try:
                store.quarantine({"source": source.to_payload(), "status": "QUARANTINED",
                                  "errorCode": "INVALID_SOURCE_TIMESTAMP"}, fence)
            except PublishRejected as exc:
                return ProcessingReceipt(exc.code, "INVALID_SOURCE_TIMESTAMP", False)
            return ProcessingReceipt("QUARANTINED", "INVALID_SOURCE_TIMESTAMP", is_still_owned())
        return process_record(scorer=self.scorer, snapshot_reader=self.snapshot_reader, outcome_store=store,
                              raw=message.value(), key=message.key(), source=source, partition_fence=fence,
                              notify=notify, requeue_member=requeue_member, is_still_owned=is_still_owned)


def main(argv=None):
    parser = argparse.ArgumentParser(description="742 GBT party Consumer (explicit immutable bundle pin)")
    parser.add_argument("--gbt-bundle", required=True)
    parser.add_argument("--expected-revision", required=True)
    parser.add_argument("--max-snapshot-bytes", required=True, type=int)
    args = parser.parse_args(argv)
    if not 1 <= args.max_snapshot_bytes <= 64 * 1024**2:
        parser.error("--max-snapshot-bytes must be between1 and67108864")
    from feelm_consumer.gbt.artifacts import load_bundle
    from feelm_consumer.gbt.scorer import Scorer
    from feelm_consumer.party_runner import run
    from feelm_consumer.party_settings import load_party_settings

    settings = load_party_settings(gbt=True)
    # Validate all data-only artifacts before creating transport clients.
    scorer = Scorer(load_bundle(args.gbt_bundle, expected_revision=args.expected_revision))
    client = redis.Redis.from_url(settings.redis_url, decode_responses=True)
    reader = RedisSnapshotReader(client, args.max_snapshot_bytes)
    run(settings, gbt_runtime=GbtPartyRuntime(client, scorer, reader))


if __name__ == "__main__":
    main()
