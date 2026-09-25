-- S15P21E106-858: 리포트 발급 알림.
-- 1) 수신 설정(API-017·018 REPORT_ISSUED). 기본값 true — 새 알림이라 켠 채로 시작한다.
ALTER TABLE notification_settings
    ADD COLUMN report_issued_enabled boolean NOT NULL DEFAULT true;

-- 2) 인앱 알림 종류 REPORT_ISSUED, 이동 대상 TASTE_REPORT(taste_reports.id).
ALTER TABLE notifications DROP CONSTRAINT ck_notifications_type;
ALTER TABLE notifications ADD CONSTRAINT ck_notifications_type
    CHECK (type IN ('PARTY_INVITE', 'PARTY_INVITE_ACCEPTED', 'WATCH_CONFIRM', 'REPORT_ISSUED'));

ALTER TABLE notifications DROP CONSTRAINT ck_notifications_target_type;
ALTER TABLE notifications ADD CONSTRAINT ck_notifications_target_type
    CHECK (target_type IN ('PARTY_INVITATION', 'PARTY', 'WATCH_RECORD', 'TASTE_REPORT'));

ALTER TABLE notifications DROP CONSTRAINT ck_notifications_type_target;
ALTER TABLE notifications ADD CONSTRAINT ck_notifications_type_target CHECK (
    (type = 'PARTY_INVITE' AND target_type = 'PARTY_INVITATION')
    OR (type = 'PARTY_INVITE_ACCEPTED' AND target_type = 'PARTY')
    OR (type = 'WATCH_CONFIRM' AND target_type = 'WATCH_RECORD')
    OR (type = 'REPORT_ISSUED' AND target_type = 'TASTE_REPORT')
);
