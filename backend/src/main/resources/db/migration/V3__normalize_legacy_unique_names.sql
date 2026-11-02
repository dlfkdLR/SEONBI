-- 운영 DB에서 Hibernate가 붙인 UNIQUE 제약 이름 네 개를 V1 기준 이름으로 맞춘다.
-- 제약의 실제 정의가 예상과 같을 때만 이름을 바꾼다. 기존 행이나 제약의 동작은 바꾸지 않는다.
-- 새 DB는 V1에서 이미 기준 이름으로 만들어지므로 아무 작업도 하지 않는다.
DO $$
DECLARE
    expected record;
BEGIN
    FOR expected IN
        SELECT * FROM (VALUES
            ('answers', 'uka8hukb7yjph7wnrlnn8x0fcja', 'answers_question_id_key', 'UNIQUE (question_id)'),
            ('criteria', 'ukpx5c0wra34gl73qbcq2ypt4eb', 'criteria_decision_id_name_key', 'UNIQUE (decision_id, name)'),
            ('options', 'ukpq4di9st3r3vhbirsdyp97afs', 'options_decision_id_name_key', 'UNIQUE (decision_id, name)'),
            ('questions', 'uk4ykmj0m93n4gl4olovto0bcbs', 'questions_decision_id_seq_key', 'UNIQUE (decision_id, seq)')
        ) AS names(table_name, old_name, new_name, definition)
    LOOP
        IF EXISTS (
            SELECT 1 FROM pg_constraint c
            WHERE c.conrelid = format('public.%I', expected.table_name)::regclass
              AND c.conname = expected.old_name
        ) THEN
            IF EXISTS (
                SELECT 1 FROM pg_constraint c
                WHERE c.conrelid = format('public.%I', expected.table_name)::regclass
                  AND c.conname = expected.new_name
            ) OR NOT EXISTS (
                SELECT 1 FROM pg_constraint c
                WHERE c.conrelid = format('public.%I', expected.table_name)::regclass
                  AND c.conname = expected.old_name
                  AND c.contype = 'u'
                  AND pg_get_constraintdef(c.oid) = expected.definition
            ) THEN
                RAISE EXCEPTION '예상과 다른 UNIQUE 제약: %.%', expected.table_name, expected.old_name;
            END IF;

            EXECUTE format(
                'ALTER TABLE public.%I RENAME CONSTRAINT %I TO %I',
                expected.table_name, expected.old_name, expected.new_name
            );
        ELSIF NOT EXISTS (
            SELECT 1 FROM pg_constraint c
            WHERE c.conrelid = format('public.%I', expected.table_name)::regclass
              AND c.conname = expected.new_name
              AND c.contype = 'u'
              AND pg_get_constraintdef(c.oid) = expected.definition
        ) THEN
            RAISE EXCEPTION '기준 UNIQUE 제약이 없거나 정의가 다름: %.%', expected.table_name, expected.new_name;
        END IF;
    END LOOP;
END
$$;
