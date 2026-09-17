-- 사용자가 목록에서 직접 숨긴 시각. 원본 사건 종료(closed_at)와 사용자 선택을 구분한다.
ALTER TABLE notifications ADD COLUMN dismissed_at timestamptz;

-- 미확인 개수와 전체 읽음은 사용자가 숨긴 행도 제외한다.
DROP INDEX ix_notifications_user_unread;
CREATE INDEX ix_notifications_user_unread
    ON notifications (user_id, notified_at DESC, id DESC)
    WHERE read_at IS NULL AND closed_at IS NULL AND dismissed_at IS NULL;
