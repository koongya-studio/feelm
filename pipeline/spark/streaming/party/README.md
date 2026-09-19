# Party refresh streaming boundary

> S15P21E106-566 이후 운영 writer는 `pipeline/consumer`의 전용 Python Consumer다.
> 이 Spark 구현과 checkpoint는 전환 검증 및 롤백 기준선으로 보존하며 동시에 실행하지 않는다.

`kafka_stream.start_party_refresh_stream` consumes
`feelm.party-refresh.v1` and writes one complete value to
`feelm:party:{partyId}:candidates`.

Each Kafka value is either the refresh body itself or an event envelope whose
`payload` is the body below.  The producer must build it only after reading one
atomic Consumer `vector`/`candidates` snapshot for every current party member.

```json
{
  "partyId": 9001,
  "inputId": "sha256:<digest-of-current-party-snapshot>",
  "members": [{
    "userId": 1024,
    "vector": [0.1, 0.2],
    "candidateMovieIds": [550, 807]
  }]
}
```

`candidateMovieIds` must be unique and ordered by the member's preference:
Consumer `taste` order first, followed by `discovery` order without repeated
movie IDs (up to 600). An empty `vector` is valid for a content-only user.
Movies without an ALS factor and members without an ALS vector remain eligible:
their within-user candidate rank becomes a percentile fallback. If a movie is
not in a member's candidate list and ALS cannot score it, that member's fit is
zero. The Redis `fallback` field identifies missing-factor movies and
empty-vector members; it does not mean those candidates were excluded.
This mixed ALS/rank fallback policy is `party-mean-fit-v2` in the Redis value.
The producer must use the party ID as its Kafka key so refreshes for one party
stay on one ordered partition.

Spark does not need `modelVersion` or per-member `generationId` in the event to
score movies. The producer must verify that each member's Redis `vector` and
`candidates` belong to the same generation and model, and that model matches
the model deployed with this Spark job. It computes one stable `inputId` from
the party ID, model version, and sorted member IDs/generations. Spark echoes the
ID in Redis and writes its loaded model version. Spring must compare both with
the current party/member snapshot before serving a result. Delayed events are
therefore rejected even if they overwrite the Redis key.

`jobs/streaming/party_candidates.py` is the legacy production entrypoint retained for rollback. It loads the
published ALS version and `service-movie-id-map` Parquet, rejects a mapping
revision mismatch or a non-`SERVING` model, verifies Redis with `PING`, and only
then starts Kafka consumption. During a model rollout, the producer must stop
sending new-model snapshots to an old-model Spark job; the backend reader must
reject any output whose model version no longer matches current Redis snapshots.
Keep the same checkpoint on restart; do not delete it to clear an old event.

The Spark image installs the pinned `redis` client. Spark's Kafka connector is
submitted with the same 4.1.3 package already used by the infrastructure:

```bash
cd pipeline/spark
PYTHONPATH=. spark-submit \
  --master spark://spark-master:7077 \
  --packages org.apache.spark:spark-sql-kafka-0-10_2.13:4.1.3 \
  jobs/streaming/party_candidates.py \
  --kafka-bootstrap-servers kafka:9092 \
  --checkpoint-uri hdfs://hdfs-namenode:8020/checkpoints/party-candidates \
  --redis-url redis://redis:6379/0 \
  --model-root-uri hdfs://hdfs-namenode:8020/models \
  --model-version '<serving-model-version>' \
  --service-movie-map-uri hdfs://hdfs-namenode:8020/service-movie-id-map
```

The checkpoint URI must be stable across restarts and dedicated to this query.
For a new checkpoint, Kafka consumption starts at the earliest retained offset;
subsequent restarts resume from the checkpoint. Each micro-batch reads at most
100 Kafka records by default (`--max-records-per-trigger` changes this).
Redis publication is a single atomic `SET`. A Redis error fails the batch, so
Kafka offsets advance only after every value in that micro-batch is published;
retries are idempotent for the party key.
