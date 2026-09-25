-- S15P21E106-857: 파티 초대 알림 수신 설정(API-017·018 PARTY_INVITE).
-- 기존 계정도 지금까지 파티 초대 알림을 받아 왔으므로 기본값 true로 채워 행동을 바꾸지 않는다.
ALTER TABLE notification_settings
    ADD COLUMN party_invite_enabled boolean NOT NULL DEFAULT true;
