# 공공데이터 동기화 후 회원정보 파기

상태: feature 구현 및 GitHub 격리 검증. 기존 R2/복원 코드는 feature에 push했으나 운영 파기/배포는 수행하지 않았다. 아래 정리 검토 정책 추가분은 로컬 구현이다.

> 이 문서는 초기 구현 이력을 포함한다. 현재 LOCAL 운영 구조와 준비 배포 상태는 [재개 릴리스](account-resume-release.md)와 [활성화 준비표](retirement-activation-readiness.md)를 우선한다. 아래 R2 전용 설명을 현재 저장 위치나 활성화 상태로 해석하지 않는다.

## 실행 순서

매일 02:00 KST RestroomSyncScheduler → 공공데이터 동기화 종료(성공/실패)
→ finally에서 AccountErasureJob → 만료된 회원별 DB 트랜잭션 및 Redis 정리.

- 독립 1분 타이머·DB Event Scheduler 없음. 수동 동기화 CLI나 정규화 분석에는 연결하지 않는다.
- 동기화 실행 이력은 변경하지 않는다. 파기는 별도 계정 파기 로그·Prometheus 메트릭과 account_withdrawal 실패 체크포인트로 확인한다.
- 50명씩 keyset 조회, 기본 실행 상한 5,000명. 실패한 ID를 같은 실행에서 계속 재시도하지 않고 다음 ID로 진행한다.
- 재시작 즉시 파기하지 않는다. 다음 일일 공공데이터 동기화가 끝나면 DB에서 다시 조회한다.
- 실패 체크포인트는 기존 API와 같은 2/4/8/16/32/60분 이후의 처리 가능 시각을 저장하지만, 실제 자동 재시도는 **다음 일일 후속 실행**이다.
- 복구 허용은 정확한 기한에서 종료한다. 물리 삭제는 다음 일일 실행까지 대기할 수 있고, 동기화 장기 지연·서버 중지·상한/오류 발생 시 더 늦을 수 있다. 즉시 파기 요청은 API가 즉시 시도한다.
- 공공데이터 작업이 종료되지 않고 멈추면 후속 파기도 시작하지 못한다. 기존 배치 완료 감시와 함께 다음 배치 시점까지 미완료 여부를 감시해야 한다.

## 안전 조건

- ACCOUNT_ERASURE_ENABLED=false 기본값. 배포 워크플로도 false를 명시한다.
- 활성화 전 API의 V11 설치, API/배치 동일 SQL 계약, 실제 MySQL 잠금/FK 테스트, 백업 삭제 대장/복원 재적용 검증 필수.
- API ACCOUNT_RETENTION_ENABLED와 batch ACCOUNT_ERASURE_ENABLED는 별도 스위치다.
- Redis는 API와 같은 인스턴스와 DB 번호를 사용한다. REDIS_HOST/PORT/PASSWORD/DATABASE 필요. 정리 네임스페이스는 auth:refresh-user/token, auth:recovery-user/recovery뿐이다.
- 배치 DB 계정에 회원 관련 테이블의 SELECT/UPDATE/DELETE 및 행 잠금 권한이 필요하다. 운영 계정 권한은 아직 확인/확대하지 않았다. DDL 관리자 권한은 런타임에 주지 않는다.
- Redis 장애면 DB 파기는 중단한다. DB 롤백으로 Redis 키가 복원되지는 않지만 탈퇴 계정의 인증을 되살리지 않는다.
- 복구와 같은 순서로 app_user → account_withdrawal 잠금을 잡는다. 잠금 뒤 상태·기한을 재검증한다.
- API 즉시 삭제와 배치 정기 삭제의 SQL은 두 저장소에 동일한 소스로 보관한다. 라이브러리 자동 공유가 아니므로 동시 변경/일치 확인이 릴리스 게이트다.

```powershell
./scripts/verify-account-erasure-contract.ps1 -ApiRepository C:/fork/tiolet/.codex-api-profile
```

## 알림/관측

- account_erasure_completed_total, account_erasure_failed_total, account_erasure_overdue.
- BATCH_FAILURE_WEBHOOK_URL의 기존 Discord 경로에 실행당 한 번, 실패/미완료/인프라 장애 건수만 전송한다. 회원 ID·주소·이메일·SQL·예외 원문은 보내지 않는다.
- DB 장애 시 overdue는 마지막 측정값일 수 있으므로 infrastructureFailure=true를 함께 확인한다.
- 성공 이력은 애플리케이션 로그/카운터이며 영구 삭제 완료 증빙을 대체하지 않는다. R2 파기 의도 선기록·복원 재적용은 feature에서 구현/격리 검증했다. 완료 증빙의 별도 CLI는 아래와 같이 구현했으나 독립 목록 자동 생성·운영 설치·보관 만료 실행은 아직 남아 있다.
- 실패 건·상한 초과 잔여 건은 다음 일일 실행에서 처리한다. 백로그가 증가하면 운영자가 원인과 처리 상한을 검토한다.

## 과거 R2 정리 검토 정책 (초기 설계 기록)

`ErasureRetentionReviewPolicy`는 주어진 증빙으로만 판단하는 순수 dry-run 함수다. DB/R2 접근, 파일 삭제, 스케줄러는 없다. 증빙을 수집/검증한 것으로 간주하지 않는다.

- 최초 신뢰 가능한 DB 부재 확인부터 최소 32일은 초기 검토 대기 기준이다. 현재 전체 시스템 로그가 30일 뒤 삭제된다는 뜻이나 법정 보관 기간·자동 삭제일이 아니다. 의도 객체 생성일은 입력으로 받지 않으며 실제 사본의 잔존 범위와 부재 증거가 별도로 필요하다.
- 현재 DB 부재, 사본 범위/실제 제거, 복원 없음, 진행 중 백업/복원 없음, 독립 대장 목록, 키 복구, v1 보관 정책/만료 처리 확인 중 하나라도 없으면 보류한다.
- 누락/미래/역전 시각과 24시간 초과한 증빙도 보류한다. 24시간은 검토 목록용이며 삭제 직전 재검증을 대체하지 않는다.
- `REVIEW_CANDIDATE`도 삭제 승인이 아니다. 영속 완료 증빙·목록·실행 잠금·조건부 정리/재개 어댑터를 구현하고 검증하기 전 연결하지 않는다.
- 이 클래스는 batch 전용 검토 도구다. 후속 완료 증빙 구현으로 API/배치 공유 계약은 8개 소스로 늘었다.

## 완료 증빙 · 백업 진단 CLI (운영 미연결)

후속 구현: `ERASURE_LEDGER_CATALOGUE_ENABLED=false` 기본값. true이면 API/배치가 `catalogue-v1/`를 기존 의도보다 먼저 조건부 기록/검증한다. `ErasureCatalogueExportCli --dry-run|--export`로 별도 기대 건수와 비교하여 증빙 도구 입력을 생성한다. 같은 R2 안의 두 경로이며 버킷 전체 손실에 대한 독립 백업은 아니다. 신뢰 기준 자동 보존·복원 세대 회전은 아직 운영 조건이다.

`BackupCaptureMetadata`와 스캐너는 `.metadata.json`의 암호화 파일 해시·크기·시각·DB 식별 형식을 확인하고 캡처 시작 기준으로 진단한다. 없는 metadata를 파일 날짜로 생성하지 않으며 전체 사본 제거 확인은 계속 false다. docs의 새 백업 스크립트는 만료 삭제를 분리했으므로 독립 정리 도구와 함께 준비하기 전 운영 설치하면 안 된다.

`installErasureTools` 후 `java -cp 'build/erasure-tools/lib/*' com.example.toiletbatch.account.AccountErasureEvidenceCli --dry-run`으로 별도 실행한다. 기존 Spring 스케줄러/즉시 파기 트랜잭션에는 연결하지 않았다.

- 기본 DB SELECT/R2 조회·목록 읽기만 수행. 명시적인 `--record-completions`만 R2 `completion-v1/<realm>/<databaseEpoch>/` 아래 암호화 증빙을 조건부 최초 기록한다. DB/백업/대장 삭제 기능은 없다.
- 삭제 트랜잭션과 분리된 DB 부재 재조회, 의도 전체 사전 대조, ID 재사용 충돌, 파기 기한·복원 세대·최초 시각을 검증한다. R2 장애 후에는 의도 목록으로 재시도한다.
- 운영자가 관련 작성자·복원을 중지하고 독립 의도 목록 파일/해시·서버 UUID/복원 세대를 제공해야 한다. 자동 잠금/독립 목록 생성은 아직 없다. 무인 타이머 등록 금지.
- 암호화 백업 파일과 checksum을 비재귀 읽기 대조한다. mtime만으로 캡처 시점을 추정하지 않으며 빈 디렉터리도 전체 사본 제거를 증명하지 않는다. `retentionClearance=false`를 유지한다.
- 상세 설정/제약은 docs 저장소 `operations/account-erasure-evidence-reconciliation-v1.md`를 참고한다. 실제 운영 통합 시험·배포는 별도 단계다.
