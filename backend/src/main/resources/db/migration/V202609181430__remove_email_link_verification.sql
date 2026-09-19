-- 메일 링크 인증 경로 제거(S15P21E106-604).
--
-- 가입이 인증번호로 이메일 소유를 먼저 확인하게 되면서(API-078 · 079, S15P21E106-601)
-- 링크 인증(API-002 · 003)과 그 대기 상태가 계약에서 사라졌다. 이 마이그레이션은 그 흔적을
-- 스키마에서 걷어낸다.
--
-- 순서에 이유가 있다. 잔존 계정의 의존 행을 먼저 지워야 users 삭제가 외래키에 걸리지 않고,
-- users를 비운 뒤라야 CHECK 제약을 좁힐 수 있다.

-- 1. 예상 밖의 데이터를 먼저 막는다.
--    미인증 계정은 로그인할 수 없어 세션이 필요한 API를 쓰지 못하므로, 행이 생길 수 있는 곳은
--    가입·계정 메일 경로가 만드는 세 테이블(인증 토큰·재설정 토큰·알림 설정)뿐이다. 그 판단이
--    틀렸을 때 무슨 일이 일어나는지는 외래키의 삭제 정책에 따라 갈린다. `users`를 참조하는
--    제약 중 상당수가 CASCADE라 **경고 없이 함께 삭제**되고, 나머지 RESTRICT는 마이그레이션을
--    통째로 실패시킨다. 어느 쪽도 원하는 결과가 아니므로 삭제 전에 직접 확인하고 멈춘다.
DO $$
DECLARE
    unexpected bigint;
BEGIN
    SELECT count(*) INTO unexpected
    FROM users u
    WHERE u.status = 'PENDING_VERIFICATION'
      AND (EXISTS (SELECT 1 FROM user_auth_providers t WHERE t.user_id = u.id)
        OR EXISTS (SELECT 1 FROM user_otts t WHERE t.user_id = u.id)
        OR EXISTS (SELECT 1 FROM onboarding_preferences t WHERE t.user_id = u.id)
        OR EXISTS (SELECT 1 FROM notifications t WHERE t.user_id = u.id)
        OR EXISTS (SELECT 1 FROM user_blocks t
                   WHERE t.blocker_id = u.id OR t.blocked_id = u.id));

    IF unexpected > 0 THEN
        RAISE EXCEPTION
            '미인증 계정 %건이 예상 밖의 종속 데이터를 갖고 있습니다. 삭제 범위를 확인한 뒤 다시 적용하세요.',
            unexpected;
    END IF;
END
$$;

-- 2. 잔존 미인증 계정의 의존 행.
DELETE FROM notification_settings
WHERE user_id IN (SELECT id FROM users WHERE status = 'PENDING_VERIFICATION');

DELETE FROM password_reset_tokens
WHERE user_id IN (SELECT id FROM users WHERE status = 'PENDING_VERIFICATION');

-- 3. 잔존 미인증 계정.
--    인증을 마칠 방법이 없어진 계정이라 남겨 두면 그 이메일만 영구히 붙든다. 같은 주소로
--    다시 가입할 수 있게 지운다.
DELETE FROM users WHERE status = 'PENDING_VERIFICATION';

-- 4. 링크 인증 토큰 테이블. 남은 행은 테이블과 함께 사라진다.
DROP TABLE email_verification_tokens;

-- 5. 계정 상태 CHECK 제약.
--    남는 값은 ACTIVE · WITHDRAWN 둘이다. 제약을 좁혀 두어야 애플리케이션이 없앤 값이
--    스키마 쪽에서 되살아나지 않는다.
--
--    이 단계는 **애플리케이션이 떠 있지 않다고 가정한다.** 배포가 컨테이너를 교체하는 방식이라
--    마이그레이션이 도는 동안 옛 인스턴스가 살아 있지 않기 때문이다. 무중단 배포로 바꾸면 옛
--    인스턴스가 커밋한 PENDING 행이 제약 검증에 걸려 마이그레이션 전체가 롤백된다.
ALTER TABLE users DROP CONSTRAINT IF EXISTS ck_users_status;
ALTER TABLE users
    ADD CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'WITHDRAWN'));
