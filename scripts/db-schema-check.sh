#!/bin/sh
# 기존 DB의 스키마가 Flyway 마이그레이션(V1, V1+V2)과 같은지 비교한다. baseline 하기 전에 반드시 먼저 실행한다.
#
# 사용:  TARGET_DATABASE_URL='postgresql://사용자:비밀번호@호스트:5432/seonbi' sh scripts/db-schema-check.sh
#
# 안전: 대상 DB에는 읽기 전용 SELECT(scripts/schema-fingerprint.sql)만 보낸다(읽기 전용 트랜잭션 강제). 아무것도 바꾸지 않는다.
#       비교 기준은 이 스크립트가 임시로 띄우는 postgres:16.4 컨테이너에 마이그레이션 파일을 적용해 만들고, 끝나면 지운다.
# 필요: docker, psql
# 결과: 0 = 일치(V1 또는 V1+V2와 같음) / 1 = 다름(차이를 출력함. 이때는 baseline 하지 말 것) / 2 = 사용법·환경 오류
set -eu
: "${TARGET_DATABASE_URL:?TARGET_DATABASE_URL 을 설정하세요 (예: postgresql://user:pw@host:5432/seonbi)}"
command -v docker >/dev/null || { echo "docker 가 필요합니다" >&2; exit 2; }
command -v psql >/dev/null || { echo "psql 이 필요합니다" >&2; exit 2; }
HERE="$(cd "$(dirname "$0")" && pwd)"
MIG="$HERE/../backend/src/main/resources/db/migration"
FP="$HERE/schema-fingerprint.sql"
WORK="$(mktemp -d)"
NAME="seonbi-schema-check-$$"
cleanup() { docker rm -f "$NAME" >/dev/null 2>&1 || true; rm -rf "$WORK"; }
trap cleanup EXIT

docker run -d --name "$NAME" -e POSTGRES_PASSWORD=check postgres:16.4 >/dev/null
i=0; until docker exec "$NAME" pg_isready -U postgres >/dev/null 2>&1; do i=$((i+1)); [ $i -lt 40 ] || { echo "임시 DB가 뜨지 않았습니다" >&2; exit 2; }; sleep 1; done
sleep 2

# 기준 스키마: 버전별로 데이터베이스를 따로 만들어 마이그레이션을 순서대로 적용하고 지문을 뽑는다
make_ref() { # $1=db $2...=적용할 파일
  db="$1"; shift
  docker exec "$NAME" psql -U postgres -qc "create database $db"
  for f in "$@"; do docker exec -i "$NAME" psql -U postgres -d "$db" -q -v ON_ERROR_STOP=1 < "$f"; done
  docker exec -i "$NAME" psql -U postgres -d "$db" -X -A -t < "$FP" > "$WORK/$db.txt"
}
make_ref ref_v1 "$MIG/V1__initial_schema.sql"
make_ref ref_v2 "$MIG/V1__initial_schema.sql" "$MIG/V2__decisions_setup_key.sql"

# 대상 DB 지문 (읽기 전용)
PGOPTIONS='-c default_transaction_read_only=on' psql "$TARGET_DATABASE_URL" -X -A -t -v ON_ERROR_STOP=1 < "$FP" > "$WORK/target.txt"
[ -s "$WORK/target.txt" ] || { echo "대상 DB에서 테이블을 찾지 못했습니다(빈 DB이면 baseline 없이 그냥 마이그레이션하세요)" >&2; exit 2; }

if diff -q "$WORK/target.txt" "$WORK/ref_v1.txt" >/dev/null; then
  echo "일치: 대상 DB = V1 스키마 (setup_key 이전). baseline 버전 1 로 잡으면 V2 가 적용됩니다."; exit 0
fi
if diff -q "$WORK/target.txt" "$WORK/ref_v2.txt" >/dev/null; then
  echo "일치: 대상 DB = V1+V2 스키마 (setup_key 이미 있음). baseline 버전 1 로 잡으면 V2 는 변경 없이 지나갑니다."; exit 0
fi
echo "불일치: 대상 DB 가 V1 / V1+V2 어느 쪽과도 다릅니다. baseline 하지 마세요." >&2
echo "--- 아래 차이는 (< 대상 DB, > V1+V2 기준) ---" >&2
diff "$WORK/target.txt" "$WORK/ref_v2.txt" >&2 || true
exit 1
