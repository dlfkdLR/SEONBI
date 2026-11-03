# 운영 DB Flyway 이관 기록 (2026-11-02 ~ 11-03)

`ddl-auto=update` 로 만들어 온 운영 DB를 Flyway 로 넘기면서 겪은 일과, 다음에 같은 일을 겪지 않기 위한 점검표를 남긴다. 절차 자체는 [데이터베이스-마이그레이션.md](데이터베이스-마이그레이션.md)가 기준이고, 이 문서는 **실제로 무슨 일이 있었는지**를 적는다.

## 1. 요약

- PR #8(초기 설정을 끝낸 뒤에만 결정 생성), #9(Flyway 도입)를 main 에 병합하자 Dokploy 가 main 을 자동 배포했다.
- 이력(`flyway_schema_history`)이 없는 기존 운영 DB 를 Flyway 가 **의도대로 거부**해서 api 컨테이너가 재시작을 반복했고, 서비스가 약 12시간 내려가 있었다(11-02 20:20경 → 11-03 09:08 KST 복구). **데이터는 손상되거나 삭제되지 않았다.**
- 백업 → 스키마 비교 → baseline 순서로 복구했다. 비교에서 운영 DB 의 UNIQUE 제약 이름 4개가 V1 과 달라(Hibernate 가 과거에 붙인 이름) 이를 정규화하는 `V3__normalize_legacy_unique_names.sql` 을 추가했다(PR #10).
- 결과: 운영 `flyway_schema_history` 에 버전 1·2·3 모두 성공, `FLYWAY_BASELINE_ON_MIGRATE=false` 로 복귀, 운영에서 5단계 입력 → 저장 → 질문 3개 응답 → 추천 → 확정까지 확인(점검용 결정은 삭제).

## 2. 시간 순서

| 단계 | 내용 |
|---|---|
| 병합 | #8, #9 병합 → main 자동 배포 |
| 장애 | api 가 `Found non-empty schema(s) "public" but no schema history table` 로 시작 거부, 재시작 반복. db·frontend 는 정상 |
| 백업 | DB 컨테이너에서 `pg_dump … | gzip > /var/lib/postgresql/data/pre-flyway.sql.gz` (21,407바이트) |
| 비교 | 스키마 지문 md5 `f36cdc70…` 가 V1(`eee3beb2…`), V1+V2(`fb1818cf…`) 와 모두 달랐다 → baseline 중단, 차이 분석 |
| 원인 | 네 UNIQUE 제약(`answers`·`criteria`·`options`·`questions`)의 이름만 달랐다. 컬럼·타입·CHECK·FK·인덱스는 같았다 |
| 수정 | V3 로 이름 정규화(정의가 예상과 같을 때만 변경, 다르면 중단), compose·앱에 `FLYWAY_BASELINE_ON_MIGRATE`(기본 false) 추가(PR #10) |
| baseline | Dokploy 환경변수를 한 번만 `true` 로 배포 → 곧바로 `false` 로 복귀 |
| 검증 | 이력 1·2·3 성공, 운영 기능 확인, 백업 사본 로컬 보관과 임시 컨테이너 복원 시험(PR #11) |

## 3. 무엇이 잘못됐나

1. **스키마를 검사하는 변경을 main 에 병합하면 곧바로 운영에 배포된다**는 점을 병합 전에 막지 않았다. 문서에는 "배포 전에 baseline" 이라고 적었지만 자동 배포 설정이 그 순서를 지키지 못하게 했다. 병합 후에야 배포가 이미 일어난 것을 확인했다.
2. 운영 DB 의 실제 스키마가 Entity·V1 과 같다고 가정하지 않고 비교 절차를 만든 것은 옳았다. 실제로 이름 차이가 있었다.
3. 운영 터미널·DB 접근은 자동 도구로 열 수 없어 사람이 직접 실행해야 했고, 이 때문에 복구가 길어졌다.

## 4. 다음에 스키마 관련 변경을 병합할 때 점검표

- [ ] 운영이 main 을 자동 배포하는가? 그렇다면 **병합 전에** 아래 선행 작업을 끝내거나 자동 배포를 잠시 끈다.
- [ ] 운영 DB 백업(`scripts/backup-db.sh`)을 받았고, 컨테이너 밖에도 사본이 있다.
- [ ] 변경이 DB 를 건드린다면 `scripts/db-schema-check.sh` 또는 지문 SQL 로 운영 스키마가 기대와 같은지 읽기 전용으로 확인했다.
- [ ] 이력 없는 DB 가 처음 Flyway 를 만나는 경우에만 `FLYWAY_BASELINE_ON_MIGRATE=true` 로 **한 번** 배포하고 즉시 `false` 로 되돌린다.
- [ ] 배포 후 `flyway_schema_history` 의 모든 버전이 `success=t`, api 컨테이너가 재시작 없이 떠 있고, 결정 생성·질문·결과·확정을 한 번 끝까지 눌러 본다. 점검용 데이터는 삭제한다.

## 5. 남은 위험

- **자동 백업이 없다.** Dokploy 의 Backups·Volume Backups 에 예약 작업이 없다. 현재 사본은 일회성(컨테이너 안 1개, 로컬 1개)이다. 외부 저장소(S3 호환 등)를 연결해 정기 백업과 복원 시험을 정해야 한다.
- 복원 시험은 스키마와 `COPY` 13개까지 확인했고 행별 내용 비교는 하지 않았다. 복원 후 지문 차이는 CHECK 제약 6개의 타입 캐스팅 표기뿐이었다.
- 로컬 백업 파일에는 운영 데이터가 들어 있다. 동기화되지 않는 위치에 두고 필요가 없어지면 지운다.
- 이 저장소가 iCloud Drive 안에 있어 동기화 충돌 때문에 `파일명 2.확장자` 복사본이 반복해서 생긴다(`build/` 의 `* 2.class` 가 테스트를 깨뜨린 적이 있다). 저장소를 iCloud 밖으로 옮기는 것을 권장한다.
