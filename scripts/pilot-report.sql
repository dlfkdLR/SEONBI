-- 파일럿 응답 집계 (개발기획서 5.11 "파일럿 보정", 개선사항 3). 읽기 전용이다.
-- 실제 사용자가 질문에 답한 결정들에서, 질문 수·종료 기준을 조정할 근거를 뽑는다.
-- 제목·설명·선택지 이름·자유 입력 원문은 읽지 않는다(개인정보 최소화). 집계 숫자만 나온다.
--
-- 사용:  docker compose exec -T db psql -U seonbi -d seonbi -f - < scripts/pilot-report.sql
-- 점검용·테스트 결정이 섞이지 않게 아래 기간(since)을 파일럿 시작 시각으로 바꿔서 쓴다.

\set since '''2000-01-01'''

\echo '== 1. 질문 수 분포 (엔진이 낸 질문에 답한 개수, 무효 답변 제외) =='
WITH per AS (
  SELECT d.id, d.status,
         count(a.id) FILTER (WHERE a.is_valid AND q.origin = 'engine') AS answered
  FROM decisions d
  JOIN questions q ON q.decision_id = d.id
  LEFT JOIN answers a ON a.question_id = q.id
  WHERE d.deleted_at IS NULL AND d.status IN ('ANALYZED', 'FINALIZED') AND d.created_at >= :since::timestamptz
  GROUP BY d.id, d.status)
SELECT answered AS "질문 수", count(*) AS "결정 수",
       round(100.0 * count(*) / sum(count(*)) OVER (), 1) AS "비율(%)"
FROM per GROUP BY answered ORDER BY answered;

\echo '== 2. 평균·중앙값과 "잘 모르겠어요"(건너뛰기) 비율 =='
WITH per AS (
  SELECT d.id,
         count(a.id) FILTER (WHERE a.is_valid AND q.origin = 'engine') AS answered,
         count(a.id) FILTER (WHERE a.is_valid AND q.origin = 'engine' AND a.response ? 'skip') AS skipped
  FROM decisions d
  JOIN questions q ON q.decision_id = d.id
  LEFT JOIN answers a ON a.question_id = q.id
  WHERE d.deleted_at IS NULL AND d.status IN ('ANALYZED', 'FINALIZED') AND d.created_at >= :since::timestamptz
  GROUP BY d.id)
SELECT count(*) AS "결정 수",
       round(avg(answered), 2) AS "평균 질문 수",
       percentile_cont(0.5) WITHIN GROUP (ORDER BY answered) AS "중앙값",
       round(100.0 * sum(skipped) / NULLIF(sum(answered), 0), 1) AS "건너뛰기(%)"
FROM per;

\echo '== 3. 답변 수정(38번) 사용 =='
SELECT count(DISTINCT a.decision_id) FILTER (WHERE a.updated_at > a.created_at + interval '1 second') AS "수정한 결정 수",
       count(*) FILTER (WHERE a.updated_at > a.created_at + interval '1 second') AS "수정된 답변 수",
       count(*) AS "전체 답변 수"
FROM answers a JOIN decisions d ON d.id = a.decision_id
WHERE d.deleted_at IS NULL AND d.created_at >= :since::timestamptz;

\echo '== 4. 마지막 추정의 확신도(1위일 확률) 분포 — 낮은 확신 배너 기준(0.55) 점검 =='
WITH last AS (
  SELECT DISTINCT ON (s.decision_id) s.decision_id, s.top_probability, s.after_answer_count
  FROM estimate_snapshots s JOIN decisions d ON d.id = s.decision_id
  WHERE d.deleted_at IS NULL AND d.created_at >= :since::timestamptz
  ORDER BY s.decision_id, s.after_answer_count DESC)
SELECT width_bucket(top_probability, 0, 1, 10) AS "구간(0.1 단위, 1=0~0.1)",
       count(*) AS "결정 수",
       round(avg(after_answer_count), 1) AS "평균 답변 수"
FROM last GROUP BY 1 ORDER BY 1;

\echo '== 5. 확정한 선택이 추천과 같았나 (추천 신뢰도의 간접 지표) =='
SELECT count(*) AS "확정 수",
       count(*) FILTER (WHERE matched_recommendation) AS "추천과 같음",
       round(100.0 * count(*) FILTER (WHERE matched_recommendation) / NULLIF(count(*), 0), 1) AS "일치(%)"
FROM final_choices f JOIN decisions d ON d.id = f.decision_id
WHERE d.deleted_at IS NULL AND d.created_at >= :since::timestamptz;

\echo '== 6. 확신도 구간별 추천 일치율 (확신이 높을수록 사용자가 추천을 따르는가) =='
WITH last AS (
  SELECT DISTINCT ON (s.decision_id) s.decision_id, s.top_probability
  FROM estimate_snapshots s ORDER BY s.decision_id, s.after_answer_count DESC)
SELECT CASE WHEN l.top_probability < 0.55 THEN 'a. <0.55 (낮은 확신)'
            WHEN l.top_probability < 0.85 THEN 'b. 0.55~0.85'
            ELSE 'c. 0.85 이상' END AS "확신도",
       count(*) AS "확정 수",
       round(100.0 * count(*) FILTER (WHERE f.matched_recommendation) / count(*), 1) AS "추천 일치(%)"
FROM final_choices f
JOIN decisions d ON d.id = f.decision_id AND d.deleted_at IS NULL AND d.created_at >= :since::timestamptz
JOIN last l ON l.decision_id = f.decision_id
GROUP BY 1 ORDER BY 1;
