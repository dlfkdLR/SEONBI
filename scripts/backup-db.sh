#!/bin/sh
# 선비 DB 백업: docker compose의 db 컨테이너에서 pg_dump로 압축 덤프를 만든다.
# 사용: scripts/backup-db.sh [저장 폴더(기본 ./backups)]
# 환경: POSTGRES_PASSWORD 가 compose 와 같은 값으로 설정돼 있어야 한다(.env 또는 환경변수).
# 자동화: cron 또는 Dokploy 의 Schedule 에 `sh scripts/backup-db.sh /경로` 를 하루 한 번 걸고, 오래된 파일은 정리한다.
set -eu
DIR="${1:-./backups}"
mkdir -p "$DIR"
FILE="$DIR/seonbi-$(date -u +%Y%m%dT%H%M%SZ).sql.gz"
docker compose exec -T db pg_dump -U seonbi -d seonbi --no-owner --clean --if-exists | gzip > "$FILE"
# 비어 있거나 너무 작으면 실패로 본다
[ "$(wc -c < "$FILE")" -gt 1000 ] || { echo "백업이 비정상입니다: $FILE" >&2; rm -f "$FILE"; exit 1; }
echo "백업 완료: $FILE"
# 14일 지난 백업 삭제
find "$DIR" -name 'seonbi-*.sql.gz' -mtime +14 -delete
