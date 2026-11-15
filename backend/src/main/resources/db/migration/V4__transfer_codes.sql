-- V4: 기록 이동 코드 (기획서 49-1·49-2번, v0.7).
-- 로그인이 없어서 브라우저 ID(X-Client-Id)가 곧 "나"다. 기기를 바꾸면 그 ID를 잃으므로,
-- 옛 기기가 짧은 일회용 코드를 발급하고 새 기기가 그 코드를 입력하면 같은 ID를 이어받는다.
-- 코드 원문은 저장하지 않고 SHA-256 해시만 둔다.
-- ddl-auto 가 먼저 만든 DB 에서도 오류 없이 지나가도록 IF NOT EXISTS 를 쓴다(V2 와 같은 방식).
CREATE TABLE IF NOT EXISTS transfer_codes (
    id          uuid         NOT NULL,
    client_id   uuid         NOT NULL,
    code_hash   varchar(64)  NOT NULL,
    expires_at  timestamp(6) with time zone NOT NULL,
    used_at     timestamp(6) with time zone,
    created_at  timestamp(6) with time zone NOT NULL,
    CONSTRAINT transfer_codes_pkey PRIMARY KEY (id),
    CONSTRAINT transfer_codes_code_hash_key UNIQUE (code_hash)
);

CREATE INDEX IF NOT EXISTS idx_transfer_codes_client ON transfer_codes (client_id);
