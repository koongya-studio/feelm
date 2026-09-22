-- 프로필 사진 파일은 서비스 노드의 영속 저장소에 두고 DB에는 서버 생성 키만 저장한다.
ALTER TABLE users ADD COLUMN avatar_storage_key varchar(40);
