-- V2: 초기 설정 완료 저장(POST /decisions/setup)의 멱등 키.
-- 같은 브라우저(client_id)가 같은 setup_key 로 다시 보내면 결정을 새로 만들지 않고 만들어 둔 결정을 돌려준다.
-- 다른 경로로 만든 결정은 NULL 이고, PostgreSQL 의 UNIQUE 는 NULL 끼리 충돌하지 않는다.
--
-- 이 컬럼이 이미 있는 DB(이 기능을 ddl-auto=update 로 먼저 받은 DB)에서도 오류 없이 지나가도록 방어적으로 쓴다.
ALTER TABLE decisions ADD COLUMN IF NOT EXISTS setup_key uuid;

DO $$
BEGIN
    -- (client_id, setup_key) 에 이미 UNIQUE 제약이나 유일 인덱스가 있으면 만들지 않는다(이름이 달라도 같은 효과)
    IF NOT EXISTS (
        SELECT 1
        FROM pg_index i
        WHERE i.indrelid = 'decisions'::regclass
          AND i.indisunique
          AND (SELECT array_agg(a.attname::text ORDER BY k.ord)
               FROM unnest(i.indkey) WITH ORDINALITY AS k(attnum, ord)
               JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.attnum)
              = ARRAY['client_id', 'setup_key']
    ) THEN
        ALTER TABLE decisions
            ADD CONSTRAINT decisions_client_id_setup_key_key UNIQUE (client_id, setup_key);
    END IF;
END
$$;
