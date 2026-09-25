-- S15P21E106-878: 이미 저장된 파티 알림의 이전 고정 문구만 제품 명칭으로 통일한다.
-- 알림의 상태, 대상, 시각과 사용자 정의 문구는 그대로 둔다.
UPDATE notifications
SET title = '파티팝콘 초대',
    body = CASE WHEN body = '새로운 파티 초대가 도착했어요.'
                THEN '새로운 파티팝콘 초대가 도착했어요.'
                ELSE body END
WHERE type = 'PARTY_INVITE' AND title = '파티 모드 초대';

UPDATE notifications
SET title = '파티팝콘 초대 수락',
    body = CASE
        WHEN right(body, char_length(' 님이 파티 모드에 참여했어요.')) = ' 님이 파티 모드에 참여했어요.'
        THEN left(body, char_length(body) - char_length(' 님이 파티 모드에 참여했어요.'))
             || ' 님이 파티팝콘에 참여했어요.'
        ELSE body END
WHERE type = 'PARTY_INVITE_ACCEPTED' AND title = '파티 모드 초대 수락';
