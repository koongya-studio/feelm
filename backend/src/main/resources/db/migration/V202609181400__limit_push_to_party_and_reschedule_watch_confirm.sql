-- 감상 확인은 앱 재진입 시 조회하는 인앱 알림으로만 제공한다.
-- 기존 LINK_CLICKED 기록도 새 24시간 노출 기준으로 맞추고, 이미 생성된
-- WATCH_CONFIRM Web Push 대기는 발송되지 않도록 종료한다.
ALTER TABLE notifications DROP CONSTRAINT ck_notifications_type;
ALTER TABLE notifications ADD CONSTRAINT ck_notifications_type
    CHECK (type IN ('PARTY_INVITE', 'PARTY_INVITE_ACCEPTED', 'WATCH_CONFIRM'));

ALTER TABLE notifications DROP CONSTRAINT ck_notifications_target_type;
ALTER TABLE notifications ADD CONSTRAINT ck_notifications_target_type
    CHECK (target_type IN ('PARTY_INVITATION', 'PARTY', 'WATCH_RECORD'));

ALTER TABLE notifications DROP CONSTRAINT ck_notifications_type_target;
ALTER TABLE notifications ADD CONSTRAINT ck_notifications_type_target CHECK (
    (type = 'PARTY_INVITE' AND target_type = 'PARTY_INVITATION')
    OR (type = 'PARTY_INVITE_ACCEPTED' AND target_type = 'PARTY')
    OR (type = 'WATCH_CONFIRM' AND target_type = 'WATCH_RECORD')
);

UPDATE watch_records
SET confirm_due_at = clicked_at + INTERVAL '24 hours'
WHERE status = 'LINK_CLICKED'
  AND clicked_at IS NOT NULL
  AND confirm_due_at IS DISTINCT FROM clicked_at + INTERVAL '24 hours';

UPDATE notifications notification
SET notified_at = watch.confirm_due_at,
    updated_at = now()
FROM watch_records watch
WHERE notification.type = 'WATCH_CONFIRM'
  AND notification.source_id = watch.id
  AND notification.closed_at IS NULL
  AND notification.notified_at IS DISTINCT FROM watch.confirm_due_at;

UPDATE web_push_deliveries delivery
SET status = 'CANCELED',
    claimed_at = NULL,
    claim_token = NULL,
    finished_at = now(),
    failure_code = 'NOT_PUSH_ELIGIBLE',
    updated_at = now()
FROM notifications notification
WHERE delivery.notification_id = notification.id
  AND notification.type = 'WATCH_CONFIRM'
  AND delivery.status IN ('PENDING', 'PROCESSING');
