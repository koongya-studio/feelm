-- 배포 DB 덤프를 임시 DB에 복원한 뒤 적용한 비식별화 SQL (S15P21E106-980).
-- 배포 DB에는 실행하지 않는다. password_hash 값은 시연용 비밀번호 FeelmDemo2026! 의 BCrypt 해시다.
\set ON_ERROR_STOP on
begin;
create temp table nick_map as select id, nickname as old_nick, '사용자'||id as new_nick from users;

update notifications n set body = replace(replace(n.body, m.old_nick||' 님', m.new_nick||' 님'), m.old_nick||'님', m.new_nick||'님'),
                           title = replace(n.title, m.old_nick, m.new_nick)
  from nick_map m where n.body like '%'||m.old_nick||'%' or n.title like '%'||m.old_nick||'%';

update users set email = 'user'||id||'@feelm.test',
                 password_hash = case when password_hash is null then null else '$2a$10$UuOmyJ9kRs.1vPLRS3jH/eOlDk7vaQ6i4e0kfHAlVKOpC3q5zntwW' end,
                 nickname = '사용자'||id,
                 birth_date = case when birth_date is null then null else make_date(extract(year from birth_date)::int,1,1) end,
                 avatar_storage_key = null;

update user_auth_providers set provider_user_id = 'anon-'||provider||'-'||id;
update user_feedback set comment = null;
truncate password_reset_tokens, web_push_deliveries, web_push_subscriptions, outbox_events;
update report_images set storage_key = 'anonymized/'||id;
commit;
