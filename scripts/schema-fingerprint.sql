-- 스키마 지문: 테이블·컬럼·제약·인덱스를 사람이 비교하기 쉬운 줄 단위로 뽑는다(읽기 전용 SELECT 만 있다).
-- 컬럼 순서나 pg_dump 출력 순서 같은 의미 없는 차이는 정렬해서 없앤다.
-- 사용: psql -X -A -t -f scripts/schema-fingerprint.sql <접속 정보> > 지문.txt  (두 DB 지문을 diff 로 비교)
-- flyway_schema_history 테이블은 앱이 관리하는 이력이라 비교에서 뺀다.
SELECT line FROM (
    -- 컬럼: 이름 · 타입 · NULL 허용 · 기본값
    SELECT 'COLUMN ' || c.relname || '.' || a.attname || ' ' || format_type(a.atttypid, a.atttypmod)
           || CASE WHEN a.attnotnull THEN ' NOT NULL' ELSE '' END
           || COALESCE(' DEFAULT ' || pg_get_expr(d.adbin, d.adrelid), '') AS line
    FROM pg_attribute a
    JOIN pg_class c ON c.oid = a.attrelid AND c.relkind = 'r'
    JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = 'public'
    LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
    WHERE a.attnum > 0 AND NOT a.attisdropped AND c.relname <> 'flyway_schema_history'
    UNION ALL
    -- 제약(PK·FK·UNIQUE·CHECK): 이름 · 정의(FK 삭제 정책 포함)
    SELECT 'CONSTRAINT ' || c.relname || ' ' || k.conname || ' ' || pg_get_constraintdef(k.oid)
    FROM pg_constraint k
    JOIN pg_class c ON c.oid = k.conrelid
    JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = 'public'
    WHERE c.relname <> 'flyway_schema_history'
    UNION ALL
    -- 인덱스: 제약이 만든 인덱스는 위 제약에서 보므로 제외
    SELECT 'INDEX ' || i.tablename || ' ' || i.indexname || ' ' || regexp_replace(i.indexdef, '^CREATE (UNIQUE )?INDEX \S+ ON (public\.)?', 'CREATE \1INDEX ON ')
    FROM pg_indexes i
    WHERE i.schemaname = 'public' AND i.tablename <> 'flyway_schema_history'
      AND NOT EXISTS (SELECT 1 FROM pg_constraint k WHERE k.conname = i.indexname)
) t
ORDER BY line;
