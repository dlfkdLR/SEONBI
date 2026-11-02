#!/bin/sh
# 이미 운영 중인 DB(테이블은 있고 flyway_schema_history 는 없음)에 Flyway 기준선(baseline 버전 1)을 기록한다.
# 테이블·데이터는 건드리지 않고 flyway_schema_history 테이블 하나만 만들어 "V1 은 이미 적용됨"이라고 적는다.
#
# 반드시 순서대로: ① 백업(scripts/backup-db.sh) → ② scripts/db-schema-check.sh 가 "일치" → ③ 이 스크립트.
# 사용:  TARGET_JDBC_URL='jdbc:postgresql://호스트:5432/seonbi' TARGET_DB_USER=seonbi TARGET_DB_PASSWORD=... \
#        TARGET_DATABASE_URL='postgresql://seonbi:...@호스트:5432/seonbi' CONFIRM_BASELINE=yes sh scripts/db-baseline.sh
# 필요: docker (flyway/flyway 이미지), psql. 이 스크립트는 사람이 직접 실행하는 용도이며 배포 파이프라인에 넣지 않는다.
set -eu
[ "${CONFIRM_BASELINE:-}" = "yes" ] || { echo "CONFIRM_BASELINE=yes 를 지정해야 실행합니다(백업과 스키마 확인을 마쳤다는 확인)." >&2; exit 2; }
: "${TARGET_JDBC_URL:?}" "${TARGET_DB_USER:?}" "${TARGET_DB_PASSWORD:?}" "${TARGET_DATABASE_URL:?}"
HERE="$(cd "$(dirname "$0")" && pwd)"

# 스키마가 일치할 때만 진행한다(불일치면 check 가 1을 돌려주고 여기서 멈춘다)
sh "$HERE/db-schema-check.sh"

# 이미 기준선이 있으면 두 번 잡지 않는다
if PGOPTIONS='-c default_transaction_read_only=on' psql "$TARGET_DATABASE_URL" -X -A -t -c "select to_regclass('public.flyway_schema_history')" | grep -q flyway_schema_history; then
  echo "이미 flyway_schema_history 가 있습니다. baseline 이 필요 없습니다." >&2; exit 2
fi

docker run --rm --network host \
  -v "$HERE/../backend/src/main/resources/db/migration:/flyway/sql:ro" \
  flyway/flyway:11 \
  -url="$TARGET_JDBC_URL" -user="$TARGET_DB_USER" -password="$TARGET_DB_PASSWORD" \
  -locations=filesystem:/flyway/sql -baselineVersion=1 -baselineDescription="existing production schema" baseline
echo "baseline 완료. 이제 새 버전의 앱을 배포하면 V2 부터 자동 적용됩니다."
