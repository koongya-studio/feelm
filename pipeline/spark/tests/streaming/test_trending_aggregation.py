from datetime import datetime, timedelta, timezone

import pytest

from streaming.trending.aggregation import (
    TrendingItem,
    ViewEvent,
    align_publish_time,
    build_snapshot,
    deduplicate,
    parse_view_event,
)

UTC = timezone.utc
TO = datetime(2026, 9, 18, 12, 0, tzinfo=UTC)


def event(event_id: str, user_id: int, movie_id: int, occurred_at: datetime) -> ViewEvent:
    return ViewEvent(event_id, user_id, movie_id, occurred_at)


def test_parse_and_deduplicate_view_envelope():
    raw = (
        '{"eventId":"0f3c9a1e-6b2d-4f77-9a10-5c2b8e41d0aa","eventType":"VIEW",'
        '"occurredAt":"2026-09-18T11:59:00Z","userId":7,'
        '"payload":{"movieId":550,"dwellMs":100}}'
    )
    parsed = parse_view_event(raw)
    assert parsed.user_id == 7
    assert parsed.movie_id == 550
    assert len(deduplicate((parsed, parsed))) == 1


@pytest.mark.parametrize(
    "raw",
    ["not-json", "{}", '{"eventId":"not-uuid"}'],
)
def test_rejects_invalid_view_event(raw):
    with pytest.raises(ValueError, match="VIEW|invalid"):
        parse_view_event(raw)


def test_rejects_a_non_view_event_on_the_view_topic():
    raw = (
        '{"eventId":"0f3c9a1e-6b2d-4f77-9a10-5c2b8e41d0aa","eventType":"RATING",'
        '"occurredAt":"2026-09-18T11:59:00Z","userId":7,"payload":{"movieId":550}}'
    )
    with pytest.raises(ValueError, match="invalid VIEW event"):
        parse_view_event(raw)


def test_snapshot_uses_unique_viewers_and_half_open_boundaries():
    events = [
        event("p1", 1, 10, TO - timedelta(minutes=60)),
        event("c1", 1, 10, TO - timedelta(minutes=30)),
        event("c2", 2, 10, TO - timedelta(minutes=30)),
        event("c3", 3, 10, TO - timedelta(minutes=30)),
        event("c4", 3, 10, TO - timedelta(minutes=20)),
    ]
    snapshot = build_snapshot(events, to=TO)
    assert snapshot["period"] == {
        "from": "2026-09-18T11:00:00Z",
        "to": "2026-09-18T12:00:00Z",
    }
    assert snapshot["items"] == [
        {"movieId": 10, "rank": 1, "rankDelta": None, "trendScore": 1.0}
    ]


def test_snapshot_orders_ties_and_calculates_rank_delta():
    events = [
        *[event(f"a{i}", i, 10, TO - timedelta(minutes=10)) for i in range(1, 4)],
        *[event(f"b{i}", i, 20, TO - timedelta(minutes=10)) for i in range(1, 4)],
    ]
    previous = (TrendingItem(20, 1, None, 1.0), TrendingItem(10, 2, None, 0.5))
    items = build_snapshot(events, to=TO, previous_items=previous)["items"]
    assert items[0]["movieId"] == 10
    assert items[0]["rankDelta"] == 1
    assert items[1]["movieId"] == 20
    assert items[1]["rankDelta"] == -1


def test_align_publish_time_rounds_down_to_five_minutes():
    value = datetime(2026, 9, 18, 12, 4, 59, 123, tzinfo=UTC)
    assert align_publish_time(value) == datetime(2026, 9, 18, 12, 0, tzinfo=UTC)
