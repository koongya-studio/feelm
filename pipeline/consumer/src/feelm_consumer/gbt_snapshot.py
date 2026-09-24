"""Immutable full-input codec referenced by the 742 recommendation state."""

from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass

from feelm_consumer.gbt.errors import GbtError
from feelm_consumer.gbt.inputs import id_set, merge_inputs, movie_id

INPUT_SCHEMA = "gbt-input-v1"
IDENTIFIER = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,99}\Z")
MODEL_VERSION = re.compile(r"[a-z0-9][a-z0-9._-]{0,49}\Z")
REVISION = re.compile(r"(?=.{1,100}\Z)(?=.*\S).+", re.DOTALL)


def valid_revision(value: object) -> bool:
    if not isinstance(value, str) or not REVISION.fullmatch(value):
        return False
    try:
        return len(value.encode("utf-16-le")) <= 200
    except UnicodeError:
        return False


def canonical(value: object) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False).encode()


def safe_identifier(value: object) -> str:
    if not isinstance(value, str) or not IDENTIFIER.fullmatch(value):
        raise GbtError("INVALID_GENERATION")
    return value


@dataclass(frozen=True)
class InputSnapshot:
    user_id: int
    generation_id: str
    serving_revision: str
    model_version: str
    ratings: tuple[tuple[int, float], ...]
    dismissed_movie_ids: frozenset[int]
    watched_movie_ids: frozenset[int]

    def __post_init__(self) -> None:
        object.__setattr__(self, "user_id", movie_id(self.user_id))
        safe_identifier(self.generation_id)
        if not valid_revision(self.serving_revision):
            raise GbtError("INVALID_SERVING_REVISION")
        if not isinstance(self.model_version, str) or not MODEL_VERSION.fullmatch(self.model_version):
            raise GbtError("INVALID_MODEL_VERSION")
        actual = merge_inputs(self.ratings)
        object.__setattr__(self, "ratings", actual.ratings)
        object.__setattr__(self, "dismissed_movie_ids", id_set(self.dismissed_movie_ids))
        object.__setattr__(self, "watched_movie_ids", id_set(self.watched_movie_ids))

    @property
    def input_count(self) -> int:
        return len({mid for mid, _ in self.ratings} | self.dismissed_movie_ids)

    @property
    def excluded_movie_ids(self) -> frozenset[int]:
        return frozenset(mid for mid, _ in self.ratings) | self.dismissed_movie_ids | self.watched_movie_ids

    def _input_payload(self) -> dict:
        history = merge_inputs(self.ratings, self.dismissed_movie_ids, self.watched_movie_ids)
        return {
            "inputSchemaVersion": INPUT_SCHEMA,
            "ratings": [[mid, score] for mid, score in self.ratings],
            "dismissedMovieIds": sorted(self.dismissed_movie_ids),
            "watchedMovieIds": sorted(self.watched_movie_ids),
            "inputs": [
                {"movieId": mid, "score": score, "source": source}
                for (mid, score), source in zip(history.ratings, history.sources, strict=True)
            ],
        }

    @property
    def input_hash(self) -> str:
        return "sha256:" + hashlib.sha256(canonical(self._input_payload())).hexdigest()

    def to_payload(self) -> dict:
        return {
            **self._input_payload(),
            "userId": self.user_id,
            "generationId": self.generation_id,
            "servingRevision": self.serving_revision,
            "modelVersion": self.model_version,
            "inputHash": self.input_hash,
        }


def create_snapshot(*, user_id, generation_id, serving_revision, model_version, ratings,
                    dismissed_movie_ids=(), watched_movie_ids=()) -> InputSnapshot:
    return InputSnapshot(user_id, generation_id, serving_revision, model_version,
                         tuple(ratings), id_set(dismissed_movie_ids), id_set(watched_movie_ids))


def decode_snapshot(raw: bytes | str, *, user_id: int, generation_id: str,
                    serving_revision: str | None, model_version: str | None, input_hash: str,
                    max_bytes: int) -> InputSnapshot:
    if type(max_bytes) is not int or max_bytes < 1:
        raise ValueError("max_bytes must be a positive integer")
    if not isinstance(raw, (str, bytes)):
        raise GbtError("SNAPSHOT_MISSING")
    data = raw.encode() if isinstance(raw, str) else raw
    if len(data) > max_bytes:
        raise GbtError("SNAPSHOT_TOO_LARGE")

    def unique_pairs(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise GbtError("DUPLICATE_SNAPSHOT_KEY")
            result[key] = value
        return result

    try:
        payload = json.loads(data, object_pairs_hook=unique_pairs)
        if not isinstance(payload, dict) or payload.get("inputSchemaVersion") != INPUT_SCHEMA:
            raise GbtError("SNAPSHOT_SCHEMA")
        expected = {"userId": movie_id(user_id), "generationId": safe_identifier(generation_id),
                    "inputHash": input_hash}
        # 829 omits model metadata on the wire. The full codec still validates the
        # stored metadata and content before returning it to the caller.
        if serving_revision is not None:
            expected["servingRevision"] = serving_revision
        if model_version is not None:
            expected["modelVersion"] = model_version
        if any(payload.get(key) != value for key, value in expected.items()):
            raise GbtError("SNAPSHOT_REFERENCE_MISMATCH")
        snapshot = create_snapshot(
            user_id=payload["userId"], generation_id=payload["generationId"],
            serving_revision=payload["servingRevision"], model_version=payload["modelVersion"],
            ratings=payload["ratings"], dismissed_movie_ids=payload["dismissedMovieIds"],
            watched_movie_ids=payload["watchedMovieIds"],
        )
        if snapshot.input_hash != input_hash or canonical(payload) != canonical(snapshot.to_payload()):
            raise GbtError("SNAPSHOT_HASH_MISMATCH")
        return snapshot
    except GbtError:
        raise
    except (KeyError, TypeError, ValueError, UnicodeError, RecursionError) as exc:
        raise GbtError("INVALID_SNAPSHOT") from exc
