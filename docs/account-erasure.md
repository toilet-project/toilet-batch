# 공공데이터 동기화 후 회원정보 파기

상태: 로컬 구현. 운영 파기/배포/원격 업로드를 수행하지 않았다.

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
- 성공 이력은 애플리케이션 로그/카운터이며 영구 삭제 대장을 대체하지 않는다. 삭제 대장 외부 저장은 아직 미구현이고 운영 활성화 차단 조건이다.
- 실패 건·상한 초과 잔여 건은 다음 일일 실행에서 처리한다. 백로그가 증가하면 운영자가 원인과 처리 상한을 검토한다.
