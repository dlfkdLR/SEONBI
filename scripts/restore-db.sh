#!/bin/sh
# 선비 DB 복원: 백업 파일을 db 컨테이너의 seonbi 데이터베이스에 되돌린다. 기존 테이블은 덮어쓴다(--clean).
# 사용: scripts/restore-db.sh backups/seonbi-YYYYmmddTHHMMSSZ.sql.gz
# 복원 전에 api 컨테이너를 멈추는 것을 권장한다: docker compose stop api
set -eu
[ $# -eq 1 ] && [ -f "$1" ] || { echo "사용: $0 <백업파일.sql.gz>" >&2; exit 1; }
gunzip -c "$1" | docker compose exec -T db psql -U seonbi -d seonbi -v ON_ERROR_STOP=1 -q
echo "복원 완료: $1"
