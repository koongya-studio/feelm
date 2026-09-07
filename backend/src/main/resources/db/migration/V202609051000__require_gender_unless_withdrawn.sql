-- 가입 시 성별을 필수로 요구한다(S15P21E106-202, 플랜 결정 11).
-- 기존 ck_users_gender는 값 도메인만 검사하고 NULL IN (...)은 통과시키므로 필수성을 강제하지 못한다.
-- 탈퇴 계정은 성별을 파기하므로(D-9) 제외하며, birth_date와 같은 상태 기반 제약을 사용한다.
ALTER TABLE users
    ADD CONSTRAINT ck_users_gender_required_unless_withdrawn
        CHECK (status = 'WITHDRAWN' OR gender IS NOT NULL);
