-- 탈퇴 시 불필요한 개인정보인 생년월일·성별을 제거할 수 있게 한다(S15P21E106-184).
-- 가입·이용 중인 계정에는 기존의 생년월일 필수 계약을 유지한다.
ALTER TABLE users
    ALTER COLUMN birth_date DROP NOT NULL;

ALTER TABLE users
    ADD CONSTRAINT ck_users_birth_date_required_unless_withdrawn
        CHECK (status = 'WITHDRAWN' OR birth_date IS NOT NULL);
