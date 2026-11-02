-- SEONBI 초기 스키마 (V1)
--
-- Hibernate(ddl-auto=update)가 지금까지 만들어 온 스키마를 그대로 옮긴 것이다. 새 DB는 이 파일로 만들고,
-- 이미 운영 중인 DB는 scripts/db-schema-check.sh 로 이 파일과 같은지 확인한 뒤에만 baseline 한다.
--  * 제약·인덱스 이름은 Hibernate가 붙이던 이름(해시 포함)을 일부러 그대로 쓴다. 그래야 기존 DB와 이름까지 같다.
--  * 컬럼의 NULL 허용·길이는 JPA Entity와 똑같이 둔다(엔티티가 허용하는 값을 DB가 막으면 안 되므로 여기서 조이지 않는다).
--  * 하위 데이터의 FK는 Entity의 @OnDelete(CASCADE)와 같이 ON DELETE CASCADE 다.
--    (결정 삭제 API는 deleted_at 만 채우는 소프트 삭제라서 평소에는 CASCADE가 일어나지 않는다. 정리 작업에서 행을 지울 때만 쓴다.)
--  * 이 파일은 적용된 뒤에는 고치지 않는다. 변경은 V2, V3 ... 새 파일로 한다.

-- 결정: 최상위 엔티티. 브라우저(client_id) 단위로 구분하며 사용자 테이블은 없다.
CREATE TABLE decisions (
    id              uuid                     NOT NULL,
    client_id       uuid                     NOT NULL,
    title           varchar(100)             NOT NULL,
    description     text,
    category        varchar(20)              NOT NULL,
    status          varchar(20)              NOT NULL,
    rng_seed        bigint                   NOT NULL,
    question_limit  integer,
    version         integer                  NOT NULL,
    created_at      timestamp(6) with time zone,
    updated_at      timestamp(6) with time zone,
    deleted_at      timestamp(6) with time zone,
    CONSTRAINT decisions_pkey PRIMARY KEY (id),
    CONSTRAINT decisions_check CHECK (
        category IN ('purchase', 'career', 'study', 'activity', 'living', 'etc')
        AND status IN ('DRAFT', 'QUESTIONING', 'ANALYZED', 'FINALIZED')
        AND char_length(description) <= 1000)
);
CREATE INDEX idxd6laowwhobsxjr6nn0nqenxbw ON decisions (client_id, updated_at DESC);
CREATE INDEX idx4e9anf7cyjnyed4xy1xdotryk ON decisions (updated_at DESC, id DESC); -- 공유 목록 정렬

-- 선택지
CREATE TABLE options (
    id           uuid         NOT NULL,
    decision_id  uuid         NOT NULL,
    name         varchar(60),
    description  text,
    url          text,
    "position"   integer,
    created_at   timestamp(6) with time zone,
    CONSTRAINT options_pkey PRIMARY KEY (id),
    CONSTRAINT options_decision_id_name_key UNIQUE (decision_id, name),
    CONSTRAINT fkeot1a5y84rbu0bc4cdstwqvsv FOREIGN KEY (decision_id) REFERENCES decisions (id) ON DELETE CASCADE
);

-- 평가 기준
CREATE TABLE criteria (
    id            uuid        NOT NULL,
    decision_id   uuid        NOT NULL,
    name          varchar(40),
    type          varchar(20),
    direction     varchar(20),
    unit          varchar(20),
    levels        jsonb,
    input_mode    varchar(10) DEFAULT 'score',
    scale_min     numeric,
    scale_max     numeric,
    scale_frozen  boolean,
    "position"    integer,
    source        varchar(20),
    CONSTRAINT criteria_pkey PRIMARY KEY (id),
    CONSTRAINT criteria_decision_id_name_key UNIQUE (decision_id, name),
    CONSTRAINT criteria_check CHECK (
        type IN ('numeric', 'rating', 'boolean')
        AND direction IN ('higher_better', 'lower_better', 'true_better')
        AND input_mode IN ('score', 'rank')
        AND source IN ('user', 'ai_recommended', 'free_text')),
    CONSTRAINT fkc0ynkg9qypwej630uwyu9at6k FOREIGN KEY (decision_id) REFERENCES decisions (id) ON DELETE CASCADE
);

-- 선택지 x 기준 값 (복합 PK)
CREATE TABLE option_values (
    option_id     uuid     NOT NULL,
    criterion_id  uuid     NOT NULL,
    value_num     numeric,
    value_bool    boolean,
    value_rank    smallint,
    updated_at    timestamp(6) with time zone,
    CONSTRAINT option_values_pkey PRIMARY KEY (criterion_id, option_id),
    CONSTRAINT fknwvpvk3crgi57dage4rvs0q2s FOREIGN KEY (option_id) REFERENCES options (id) ON DELETE CASCADE,
    CONSTRAINT fkoeft5123xo0y321bvl87xkxd7 FOREIGN KEY (criterion_id) REFERENCES criteria (id) ON DELETE CASCADE
);

-- 필수 조건 (임계값 조건은 criterion_id 를 쓰고, 태그 조건은 label 을 쓴다)
CREATE TABLE conditions (
    id            uuid         NOT NULL,
    decision_id   uuid         NOT NULL,
    kind          varchar(20),
    criterion_id  uuid,
    operator      varchar(2),
    value         numeric,
    label         varchar(60),
    source        varchar(20),
    CONSTRAINT conditions_pkey PRIMARY KEY (id),
    CONSTRAINT conditions_check CHECK (
        kind IN ('threshold', 'required_tag')
        AND source IN ('user', 'ai_recommended', 'free_text')),
    CONSTRAINT fkeqrxysbkdwram75roymncfw97 FOREIGN KEY (decision_id) REFERENCES decisions (id) ON DELETE CASCADE,
    CONSTRAINT fk9h2sii7iohwrlaynixy77agyl FOREIGN KEY (criterion_id) REFERENCES criteria (id) ON DELETE CASCADE
);
CREATE INDEX idxm0ll0fgqb7fogubna5hr4s9hf ON conditions (decision_id);

-- 태그 조건을 충족하는 선택지 (복합 PK)
CREATE TABLE condition_satisfactions (
    condition_id  uuid     NOT NULL,
    option_id     uuid     NOT NULL,
    satisfied     boolean,
    CONSTRAINT condition_satisfactions_pkey PRIMARY KEY (condition_id, option_id),
    CONSTRAINT fkqscdks2kuhwjhbdelq21mk295 FOREIGN KEY (condition_id) REFERENCES conditions (id) ON DELETE CASCADE,
    CONSTRAINT fkskqg4lshwkf6o4lg1wu1yx5p9 FOREIGN KEY (option_id) REFERENCES options (id) ON DELETE CASCADE
);

-- 질문
CREATE TABLE questions (
    id               uuid        NOT NULL,
    decision_id      uuid        NOT NULL,
    seq              integer,
    type             varchar(20),
    spec             jsonb,
    display          jsonb,
    phrasing_source  varchar(10),
    origin           varchar(10),
    info_gain        real,
    created_at       timestamp(6) with time zone,
    CONSTRAINT questions_pkey PRIMARY KEY (id),
    CONSTRAINT questions_decision_id_seq_key UNIQUE (decision_id, seq),
    CONSTRAINT questions_check CHECK (
        type IN ('slider_pair', 'ab_criteria', 'ab_scenario', 'numeric', 'free_text')
        AND phrasing_source IN ('ai', 'template')
        AND origin IN ('engine', 'free_text')),
    CONSTRAINT fkmwr40v6odtysqkscasfs4c863 FOREIGN KEY (decision_id) REFERENCES decisions (id) ON DELETE CASCADE
);
CREATE INDEX idx4ykmj0m93n4gl4olovto0bcbs ON questions (decision_id, seq);

-- 답변 (질문 하나에 답변 하나)
CREATE TABLE answers (
    id               uuid         NOT NULL,
    question_id      uuid         NOT NULL,
    decision_id      uuid         NOT NULL,
    response         jsonb,
    preference_kind  varchar(20),
    is_valid         boolean      DEFAULT true,
    created_at       timestamp(6) with time zone,
    updated_at       timestamp(6) with time zone,
    CONSTRAINT answers_pkey PRIMARY KEY (id),
    CONSTRAINT answers_question_id_key UNIQUE (question_id),
    CONSTRAINT answers_preference_kind_check CHECK (preference_kind IN ('stated', 'revealed', 'none')),
    CONSTRAINT fk3erw1a3t0r78st8ty27x6v3g1 FOREIGN KEY (question_id) REFERENCES questions (id) ON DELETE CASCADE,
    CONSTRAINT fkj6yrmvh8055cjhx94kwtnq487 FOREIGN KEY (decision_id) REFERENCES decisions (id) ON DELETE CASCADE
);
CREATE INDEX idxaxmi42vlqlsvmfk83tg4tuw9j ON answers (decision_id);

-- 답변 수에 따른 가중치 추정 기록
CREATE TABLE estimate_snapshots (
    id                  uuid NOT NULL,
    decision_id         uuid NOT NULL,
    after_answer_count  integer,
    weights             jsonb,
    top_probability     real,
    entropy             real,
    created_at          timestamp(6) with time zone,
    CONSTRAINT estimate_snapshots_pkey PRIMARY KEY (id),
    CONSTRAINT fkksywkoqofvo1b3xnwp87quci FOREIGN KEY (decision_id) REFERENCES decisions (id) ON DELETE CASCADE
);

-- 분석 결과 캐시 (결정당 하나, decision_version 이 현재 버전과 다르면 무효)
CREATE TABLE analysis_results (
    decision_id       uuid NOT NULL,
    decision_version  integer,
    payload           jsonb,
    explanation       jsonb,
    computed_at       timestamp(6) with time zone,
    CONSTRAINT analysis_results_pkey PRIMARY KEY (decision_id),
    CONSTRAINT fka0xue5tdwaoxucxtus3s6a4r0 FOREIGN KEY (decision_id) REFERENCES decisions (id) ON DELETE CASCADE
);

-- 최종 선택 (결정당 하나)
CREATE TABLE final_choices (
    decision_id              uuid NOT NULL,
    option_id                uuid NOT NULL,
    memo                     text,
    matched_recommendation   boolean,
    recommendation_snapshot  jsonb,
    finalized_at             timestamp(6) with time zone,
    CONSTRAINT final_choices_pkey PRIMARY KEY (decision_id),
    CONSTRAINT fk9y2u5i4knlnkdnu1glewbxlx5 FOREIGN KEY (decision_id) REFERENCES decisions (id) ON DELETE CASCADE,
    CONSTRAINT fkben85uiblqalxdvayr9t0kapa FOREIGN KEY (option_id) REFERENCES options (id) ON DELETE CASCADE
);

-- 자유 입력 (질문에 답하며 쓴 문장과 AI 추출 결과)
CREATE TABLE free_text_inputs (
    id           uuid         NOT NULL,
    decision_id  uuid         NOT NULL,
    question_id  uuid,
    context      varchar(20),
    text         varchar(500),
    extracted    jsonb,
    status       varchar(20),
    created_at   timestamp(6) with time zone,
    CONSTRAINT free_text_inputs_pkey PRIMARY KEY (id),
    CONSTRAINT free_text_inputs_status_check CHECK (status IN ('PENDING', 'CONFIRMED', 'REJECTED', 'IRRELEVANT')),
    CONSTRAINT fktnarfskwtf7st9dho8jerwgtw FOREIGN KEY (decision_id) REFERENCES decisions (id) ON DELETE CASCADE,
    CONSTRAINT fkerrb1drse3d1kllwjq65230nn FOREIGN KEY (question_id) REFERENCES questions (id) ON DELETE CASCADE
);

-- AI 호출 기록 (결정이 없는 호출도 있어 decision_id 는 NULL 허용)
CREATE TABLE ai_usage_logs (
    id             uuid         NOT NULL,
    client_id      uuid,
    decision_id    uuid,
    call_type      varchar(30),
    provider       varchar(20),
    model          varchar(50),
    input_tokens   integer,
    output_tokens  integer,
    latency_ms     integer,
    success        boolean,
    created_at     timestamp(6) with time zone,
    CONSTRAINT ai_usage_logs_pkey PRIMARY KEY (id),
    CONSTRAINT fk4a4e2t7ot59dwsd05wjbyoy2n FOREIGN KEY (decision_id) REFERENCES decisions (id) ON DELETE CASCADE
);
CREATE INDEX idx6j5nphx02bwrhh6fad77jlx8o ON ai_usage_logs (client_id, created_at);
