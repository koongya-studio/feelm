import json
from datetime import datetime, timedelta, timezone
from threading import RLock

import pytest

from jobs.streaming.trending import run_publisher
from streaming.trending.bucket_store import (
    ACTIVE_HOUR_TTL_SECONDS,
    BUCKET_TTL_SECONDS,
    BucketIntegrityError,
    ViewerBucket,
    read_window_events,
    write_buckets,
)
from streaming.trending.kafka_stream import (
    deduplicate_view_events,
    publish_boundary,
    store_batch,
    valid_view_events,
)
from streaming.trending.redis_store import CURRENT_KEY, LAST_GOOD_KEY

TO = datetime(2026, 9, 18, 12, 0, tzinfo=timezone.utc)


class Pipeline:
    def __init__(self, redis):
        self.redis = redis
        self.commands = []

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False

    def watch(self, *keys):
        self.watched = keys

    def type(self, key):
        if key in self.redis.values:
            return "string"
        if key in self.redis.sets:
            return "set"
        return "none"

    def smembers(self, key):
        return self.redis.smembers(key)

    def get(self, key):
        return self.redis.get(key)

    def multi(self):
        assert self.watched

    def sadd(self, key, *values):
        self.commands.append(("sadd", key, values))
        return self

    def expire(self, key, seconds):
        self.commands.append(("expire", key, seconds))
        return self

    def set(self, key, value, *, ex=None):
        self.commands.append(("set", key, value, ex))
        return self

    def execute(self):
        if self.redis.fail:
            raise ConnectionError("Redis unavailable")
        for command in self.commands:
            if command[0] == "sadd":
                self.redis.sets.setdefault(command[1], set()).update(command[2])
            elif command[0] == "expire":
                self.redis.ttls[command[1]] = command[2]
            else:
                self.redis.values[command[1]] = command[2]
                self.redis.ttls[command[1]] = command[3]


class Redis:
    def __init__(self):
        self.values = {}
        self.sets = {}
        self.ttls = {}
        self.fail = False

    def get(self, key):
        return self.values.get(key)

    def smembers(self, key):
        return self.sets.get(key, set())

    def pipeline(self, *, transaction):
        assert transaction
        return Pipeline(self)


def bucket(start, movie_id, *users):
    return ViewerBucket(start, movie_id, frozenset(users))


def test_bucket_replay_and_period_counts_are_exact():
    redis = Redis()
    rows = [
        bucket(TO - timedelta(minutes=90), 550, 1),
        bucket(TO - timedelta(minutes=50), 550, 1, 2),
        bucket(TO - timedelta(minutes=5), 550, 1, 3),
    ]
    write_buckets(redis, rows)
    write_buckets(redis, rows)
    assert all(
        ttl == (ACTIVE_HOUR_TTL_SECONDS if ":active-hour:" in key or
                ":committed-hour:" in key else BUCKET_TTL_SECONDS)
        for key, ttl in redis.ttls.items() if ":bucket:" in key
    )

    publish_boundary(redis, now=TO, publication_lock=RLock())
    payload = json.loads(redis.values[CURRENT_KEY])
    assert payload["items"] == [{"movieId": 550, "rank": 1, "rankDelta": None,
                                 "trendScore": 1.0}]
    assert redis.values[LAST_GOOD_KEY] == redis.values[CURRENT_KEY]
    assert len(read_window_events(redis, to=TO)) == 5


def test_empty_boundaries_publish_without_input_and_retry_is_idempotent():
    redis = Redis()
    lock = RLock()
    publish_boundary(redis, now=TO, publication_lock=lock)
    assert redis.values == {}
    publish_boundary(redis, now=TO, publication_lock=lock)
    assert redis.values == {}
    write_buckets(redis, [bucket(TO - timedelta(minutes=5), 550, 1, 2, 3)])
    publish_boundary(redis, now=TO, publication_lock=lock)
    first = redis.values[LAST_GOOD_KEY]
    publish_boundary(redis, now=TO + timedelta(hours=3), publication_lock=lock)
    assert redis.values[LAST_GOOD_KEY] == first


def test_publisher_clock_fires_without_a_spark_microbatch():
    class IdleQuery:
        isActive = True

        def __init__(self):
            self.waits = 0
            self.drains = 0

        def processAllAvailable(self):
            self.drains += 1

        def awaitTermination(self, timeout):
            assert timeout > 0
            self.waits += 1
            return self.waits > 1

    times = iter([TO - timedelta(minutes=1), TO - timedelta(minutes=1),
                  TO - timedelta(minutes=1), TO, TO, TO + timedelta(minutes=1),
                  TO + timedelta(minutes=1)])
    redis = Redis()
    write_buckets(redis, [bucket(TO - timedelta(minutes=5), 550, 1, 2, 3)])
    query = IdleQuery()
    with pytest.raises(RuntimeError, match="terminated"):
        run_publisher(query, redis=redis, publication_lock=RLock(), clock=lambda: next(times))
    assert query.drains == 2  # 11:55 restart check, then the 12:00 boundary
    assert json.loads(redis.values[CURRENT_KEY])["period"]["to"] == "2026-09-18T12:00:00Z"


def test_restart_checks_latest_boundary_immediately():
    class StoppedAfterPublish:
        isActive = True

        def processAllAvailable(self):
            pass

        def awaitTermination(self, timeout):
            assert timeout > 0
            return True

    redis = Redis()
    write_buckets(redis, [bucket(TO - timedelta(minutes=5), 550, 1, 2, 3)])
    def clock():
        return TO + timedelta(minutes=2)
    with pytest.raises(RuntimeError, match="terminated"):
        run_publisher(StoppedAfterPublish(), redis=redis, publication_lock=RLock(), clock=clock)
    assert json.loads(redis.values[CURRENT_KEY])["period"]["to"] == "2026-09-18T12:00:00Z"


def test_publisher_waits_for_backlog_before_reading_boundary():
    redis = Redis()
    lock = RLock()

    class BackloggedQuery:
        isActive = True

        def processAllAvailable(self):
            write_buckets(redis, [bucket(TO - timedelta(minutes=5), 550, 1, 2, 3)])

        def awaitTermination(self, timeout):
            return True

    with pytest.raises(RuntimeError, match="terminated"):
        run_publisher(BackloggedQuery(), redis=redis, publication_lock=lock, clock=lambda: TO)
    assert json.loads(redis.values[CURRENT_KEY])["items"][0]["movieId"] == 550


def test_backlog_failure_keeps_last_good_without_publishing_new_boundary():
    redis = Redis()
    write_buckets(redis, [bucket(TO - timedelta(minutes=5), 550, 1, 2, 3)])
    publish_boundary(redis, now=TO, publication_lock=RLock())
    last_good = redis.values[LAST_GOOD_KEY]

    class FailedQuery:
        isActive = True

        def processAllAvailable(self):
            raise ConnectionError("bucket write failed")

    with pytest.raises(ConnectionError, match="bucket write failed"):
        run_publisher(
            FailedQuery(), redis=redis, publication_lock=RLock(),
            clock=lambda: TO + timedelta(minutes=5),
        )
    assert redis.values[LAST_GOOD_KEY] == last_good
    assert json.loads(redis.values[CURRENT_KEY])["period"]["to"] == "2026-09-18T12:00:00Z"


def test_publisher_rechecks_backlog_when_drain_crosses_a_boundary():
    redis = Redis()
    times = iter([TO, TO, TO + timedelta(minutes=5),
                  TO + timedelta(minutes=5), TO + timedelta(minutes=5),
                  TO + timedelta(minutes=5)])

    class SlowQuery:
        isActive = True
        drains = 0

        def processAllAvailable(self):
            self.drains += 1
            if self.drains == 1:
                write_buckets(redis, [bucket(TO - timedelta(minutes=5), 550, 1, 2, 3)])
            else:
                assert LAST_GOOD_KEY not in redis.values

        def awaitTermination(self, timeout):
            return True

    query = SlowQuery()
    with pytest.raises(RuntimeError, match="terminated"):
        run_publisher(query, redis=redis, publication_lock=RLock(), clock=lambda: next(times))
    assert query.drains == 2
    assert json.loads(redis.values[CURRENT_KEY])["period"]["to"] == "2026-09-18T12:05:00Z"


def test_redis_failures_do_not_leave_partial_buckets_or_snapshots():
    redis = Redis()
    redis.fail = True
    with pytest.raises(ConnectionError, match="Redis unavailable"):
        write_buckets(redis, [bucket(TO - timedelta(minutes=5), 550, 1, 2, 3)])
    assert redis.sets == {}
    redis.fail = False
    write_buckets(redis, [bucket(TO - timedelta(minutes=5), 550, 1, 2, 3)])
    redis.fail = True
    with pytest.raises(ConnectionError, match="Redis unavailable"):
        publish_boundary(redis, now=TO, publication_lock=RLock())
    assert CURRENT_KEY not in redis.values
    assert LAST_GOOD_KEY not in redis.values


def test_wrongtype_bucket_key_is_rejected_before_any_write():
    redis = Redis()
    rows = [bucket(TO - timedelta(minutes=5), 550, 1, 2, 3)]
    redis.values["feelm:trending:bucket:20260918T1155Z:movie:550:users"] = "bad"
    with pytest.raises(TypeError, match="incompatible type"):
        write_buckets(redis, rows)
    assert redis.sets == {}
    assert redis.ttls == {}


@pytest.mark.parametrize("lost_key", [
    "feelm:trending:bucket:20260918T1155Z:movies",
    "feelm:trending:bucket:20260918T1155Z:expected-movies",
    "feelm:trending:bucket:20260918T1155Z:movie:550:users",
    "feelm:trending:bucket:20260918T1155Z:movie:550:expected-count",
    "feelm:trending:bucket:20260918T1155Z:movie:550:viewer-digest",
    "feelm:trending:bucket:active-hour:20260918T11Z",
    "feelm:trending:bucket:committed-hour:20260918T11Z",
])
def test_detected_bucket_loss_keeps_last_good(lost_key):
    redis = Redis()
    write_buckets(redis, [bucket(TO - timedelta(minutes=5), 550, 1, 2, 3)])
    publish_boundary(redis, now=TO, publication_lock=RLock())
    last_good = redis.values[LAST_GOOD_KEY]
    redis.sets.pop(lost_key, None)
    redis.values.pop(lost_key, None)
    with pytest.raises(BucketIntegrityError, match="incomplete"):
        publish_boundary(redis, now=TO + timedelta(minutes=5), publication_lock=RLock())
    assert redis.values[LAST_GOOD_KEY] == last_good


def test_lost_movie_index_and_mirror_are_detected_by_active_hour():
    redis = Redis()
    write_buckets(redis, [bucket(TO - timedelta(minutes=5), 550, 1, 2, 3)])
    publish_boundary(redis, now=TO, publication_lock=RLock())
    last_good = redis.values[LAST_GOOD_KEY]
    redis.sets.pop("feelm:trending:bucket:20260918T1155Z:movies")
    redis.sets.pop("feelm:trending:bucket:20260918T1155Z:expected-movies")
    with pytest.raises(BucketIntegrityError, match="movie index is incomplete"):
        publish_boundary(redis, now=TO + timedelta(minutes=5), publication_lock=RLock())
    assert redis.values[LAST_GOOD_KEY] == last_good


def test_simultaneous_bucket_loss_cannot_publish_a_false_ranking():
    redis = Redis()
    start = TO - timedelta(minutes=5)
    write_buckets(redis, [bucket(start, 550, 1, 2, 3), bucket(start, 600, 4, 5, 6)])
    publish_boundary(redis, now=TO, publication_lock=RLock())
    last_good = redis.values[LAST_GOOD_KEY]
    current = redis.values[CURRENT_KEY]
    stamp = "20260918T1155Z"
    for suffix in ("movies", "expected-movies", "movie:550:users",
                   "movie:600:users"):
        redis.sets.pop(f"feelm:trending:bucket:{stamp}:{suffix}")
    redis.sets["feelm:trending:bucket:active-hour:20260918T11Z"].discard(stamp)
    for movie_id in (550, 600):
        redis.values.pop(f"feelm:trending:bucket:{stamp}:movie:{movie_id}:expected-count")

    with pytest.raises(BucketIntegrityError, match="movie index is incomplete"):
        publish_boundary(redis, now=TO + timedelta(minutes=5), publication_lock=RLock())
    with pytest.raises(BucketIntegrityError, match="before write"):
        write_buckets(redis, [bucket(start, 550, 7)])
    assert redis.values[CURRENT_KEY] == current
    assert redis.values[LAST_GOOD_KEY] == last_good


def test_detected_partial_viewer_loss_keeps_last_good():
    redis = Redis()
    write_buckets(redis, [bucket(TO - timedelta(minutes=5), 550, 1, 2, 3)])
    publish_boundary(redis, now=TO, publication_lock=RLock())
    last_good = redis.values[LAST_GOOD_KEY]
    users = "feelm:trending:bucket:20260918T1155Z:movie:550:users"
    redis.sets[users].remove("1")
    with pytest.raises(BucketIntegrityError, match="before write"):
        write_buckets(redis, [bucket(TO - timedelta(minutes=5), 550, 4)])
    with pytest.raises(BucketIntegrityError, match="viewers are incomplete"):
        publish_boundary(redis, now=TO + timedelta(minutes=5), publication_lock=RLock())
    assert redis.values[LAST_GOOD_KEY] == last_good


def test_same_size_viewer_replacement_keeps_last_good():
    redis = Redis()
    start = TO - timedelta(minutes=5)
    write_buckets(redis, [bucket(start, 550, 1, 2, 3)])
    publish_boundary(redis, now=TO, publication_lock=RLock())
    current = redis.values[CURRENT_KEY]
    last_good = redis.values[LAST_GOOD_KEY]
    users = "feelm:trending:bucket:20260918T1155Z:movie:550:users"
    redis.sets[users].remove("3")
    redis.sets[users].add("4")

    with pytest.raises(BucketIntegrityError, match="before write"):
        write_buckets(redis, [bucket(start, 550, 5)])
    with pytest.raises(BucketIntegrityError, match="viewers are incomplete"):
        publish_boundary(redis, now=TO + timedelta(minutes=5), publication_lock=RLock())
    assert redis.values[CURRENT_KEY] == current
    assert redis.values[LAST_GOOD_KEY] == last_good


def test_only_24_buckets_are_read():
    redis = Redis()
    write_buckets(redis, [bucket(TO - timedelta(hours=2, minutes=5), 550, 9),
                          bucket(TO - timedelta(hours=2), 550, 1),
                          bucket(TO, 550, 2)])
    assert [event.user_id for event in read_window_events(redis, to=TO)] == [1]


def test_spark_filters_invalid_view_envelopes():
    from pyspark.sql import SparkSession

    spark = SparkSession.builder.master("local[1]").appName("trending-filter-test").getOrCreate()
    try:
        valid = {
            "eventId": "0f3c9a1e-6b2d-4f77-9a10-5c2b8e41d0aa",
            "eventType": "VIEW",
            "occurredAt": "2026-09-18T11:59:00Z",
            "userId": 7,
            "payload": {"movieId": 550},
        }
        rows = [valid, {**valid, "eventType": "RATING"}, {**valid, "userId": 0},
                {**valid, "eventId": "bad"}, {**valid, "payload": {"movieId": None}}]
        source = spark.createDataFrame([(json.dumps(row),) for row in rows], ["value"])
        result = valid_view_events(source).collect()
        assert len(result) == 1
        assert result[0]["userId"] == 7
    finally:
        spark.stop()


def test_spark_can_start_single_deduplication_query(tmp_path):
    from pyspark.sql import SparkSession
    from pyspark.sql.functions import lit, struct, to_json

    spark = SparkSession.builder.master("local[1]").appName("trending-query-test").getOrCreate()
    query = None
    try:
        spark.conf.set("spark.sql.session.timeZone", "UTC")
        source = spark.readStream.format("rate-micro-batch").option("rowsPerBatch", 1).load()
        events = source.select(
            to_json(struct(
                lit("0f3c9a1e-6b2d-4f77-9a10-5c2b8e41d0aa").alias("eventId"),
                lit("VIEW").alias("eventType"),
                lit("2026-09-18T11:59:00Z").alias("occurredAt"),
                lit(7).alias("userId"),
                struct(lit(550).alias("movieId")).alias("payload"),
            )).alias("value")
        )
        query = (
            deduplicate_view_events(events)
            .writeStream.outputMode("append")
            .format("memory")
            .queryName("trending_dedup_test")
            .option("checkpointLocation", str(tmp_path / "checkpoint"))
            .trigger(availableNow=True)
            .start()
        )
        assert query.awaitTermination(30000)
        assert spark.sql("select count(*) from trending_dedup_test").first()[0] == 1
    finally:
        if query is not None and query.isActive:
            query.stop()
        spark.stop()


def test_static_microbatch_updates_five_minute_bucket():
    from pyspark.sql import SparkSession

    spark = SparkSession.builder.master("local[1]").appName("trending-batch-test").getOrCreate()
    try:
        spark.conf.set("spark.sql.session.timeZone", "UTC")
        redis = Redis()
        batch = spark.createDataFrame([
            (TO - timedelta(minutes=3), 1, 550),
            (TO - timedelta(minutes=2), 1, 550),
            (TO - timedelta(minutes=1), 2, 550),
        ], ["occurredAt", "userId", "movieId"])
        store_batch(batch, redis=redis, now=TO)
        assert {(event.movie_id, event.user_id) for event in read_window_events(redis, to=TO)} == {
            (550, 1), (550, 2)
        }, redis.sets
    finally:
        spark.stop()


def test_expired_old_bucket_is_skipped_but_current_bucket_is_written():
    from pyspark.sql import SparkSession

    spark = SparkSession.builder.master("local[1]").appName("trending-late-test").getOrCreate()
    try:
        spark.conf.set("spark.sql.session.timeZone", "UTC")
        redis = Redis()
        old = TO - timedelta(hours=3)
        write_buckets(redis, [bucket(old, 550, 1)])
        stamp = "20260918T0900Z"
        for suffix in ("movies", "expected-movies", "movie:550:users"):
            redis.sets.pop(f"feelm:trending:bucket:{stamp}:{suffix}")
        redis.values.pop(f"feelm:trending:bucket:{stamp}:movie:550:expected-count")
        batch = spark.createDataFrame([
            (old, 2, 550),
            (TO - timedelta(hours=2), 3, 600),
            (TO - timedelta(minutes=1), 4, 600),
        ], ["occurredAt", "userId", "movieId"])

        store_batch(batch, redis=redis, now=TO)

        assert {(event.movie_id, event.user_id) for event in read_window_events(redis, to=TO)} == {
            (600, 3), (600, 4),
        }
        assert f"feelm:trending:bucket:{stamp}:movies" not in redis.sets
    finally:
        spark.stop()
