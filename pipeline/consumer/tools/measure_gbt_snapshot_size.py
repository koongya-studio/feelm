"""Measure canonical input bytes using synthetic IDs; no DB/Redis access."""

import json

from feelm_consumer.gbt_snapshot import canonical, create_snapshot


def main():
    for count in (0, 1, 10, 100, 8518, 100000):
        snapshot = create_snapshot(user_id=1, generation_id="00000000-0000-4000-8000-000000000001",
                                   model_version="gbt-v26-top2-raw", serving_revision="sha256:" + "a" * 64,
                                   ratings=[(10**8 + mid, 4.0) for mid in range(count)],
                                   dismissed_movie_ids=(), watched_movie_ids=())
        print(json.dumps({"synthetic_input_count": count, "snapshot_bytes": len(canonical(snapshot.to_payload()))}))


if __name__ == "__main__":
    main()
