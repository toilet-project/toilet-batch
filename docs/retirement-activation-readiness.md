# 대장 종료 활성화 준비표

기준일 2026-09-10. 운영 활성화 전 구현·검증·배포 준비다.
완료는 근거 있는 항목만 표시한다. 이번에 운영 DB·컨테이너·계정 기능은 변경하지 않았다.

## 구현·검증

- [x] 실제 후보 계획·암호문·DB 부재·사본 증거와 복구 실행 연결.
- [x] 재개 시 원래 계획·식별·보존 대상과 최신 증거 재검증.
- [x] 연속 종료·정상 대장 추가·불변 재검토 이력.
- [x] API·배치·복원 reader 연결과 동일 형식 회귀검사.
- [x] 저널 정리·fsync 실패·응답 유실·재등장 차단.
- [x] 만료 보류·명시적 재검토·미접수 준비 기록 제한 정리.
- [x] 공공데이터 → 회원 파기 → 완료 증거 → 대장 종료 검토 실행 순서.
- [x] 기본 비활성·점검 모드·호환 인수 미충족 시 실행 차단.
- [x] 배치 전체 로컬 검사 406건 중 398 통과, 8 제외, 실패/오류 0.
- [x] API 대상 검사 160건 중 153 통과, 7 제외, 실패/오류 0.
- [x] 배포 설정 Node 검사 25건 통과.
- [x] API·배치 bootJar 및 도구 패키지 생성.
- [x] 별도 BackupLedgerReplay를 새 라이브러리로 컴파일.
- [x] Linux ext4 실제 파일/fsync/공통 잠금과 강제 종료 후 새 JVM 재개.
- [x] Linux 가상 회원 2건 연속 처리·저널 정리·구버전 차단.
- [ ] Docker를 사용하는 API 전체 CI. 첫 로컬 전체 실행의 MySQL 시험 4개는 Docker 부재로 실패했으며 통과로 계산하지 않는다.
- [ ] 다중 작업 형식의 실제 GitHub 요청 인수. 현재 Git object graph 모의 전송 시험이다.
- [ ] 실제 사본 6개 범위의 증거 수집·검토 파일 갱신 절차 인수.

## Linux 검증 범위

RetirementLinuxVerification은 test 클래스에만 있으며 가상 realm·키·회원 2건만 사용한다.
독립 저장소는 디스크에 보존한 모의 Git object graph다.
1단계 승인 후 intent 삭제 직후 Runtime.halt(73), 새 JVM에서 재독·재개·정리를 확인했다.
운영 DB·GitHub 기준 이력·Redis·컨테이너를 변경하지 않았다.
정전·디스크 전체 롤백·실제 GitHub 인수는 아니다.
첫 시험 번들 SHA-256: b4e55117d710f3225647f5e065e0b507b3629fd61bc0e700c511e55667415103.
이후 증거 sealing·캐시 변경은 최종 번들로 재검증해야 한다.

## 준비 배포·활성화 순서

1. 코드 검토·전체 CI·격리 실접속 결과와 산출물 SHA-256 확정.
2. 점검 모드·파기/종료 write 플래그 비활성 유지.
3. API 새 reader → 배치 → 복원·백업 사전검사 도구 준비 배포.
4. 같은 DB 세대·store ID·공통 잠금 inode를 사용하는지 읽기 전용 확인.
5. 별도 private 저널·검토 폴더와 marker/lock 사전 준비. 기존 잠금 교체 금지.
6. 실제 사본·키 복구 증거로 검토 파일 작성·검토·인증.
7. --dry-run 결과와 보류 검토. 0건을 실제 삭제 성공으로 계산하지 않음.
8. 사용자 탈퇴·복구/회원 파기의 기존 인수 조건과 웹 고지 일치 확인.
9. 마지막 운영 승인 후 단계별 활성화·본인 테스트 계정 인수.
10. 첫 정기 실행·보류/오류 알림 확인 후 운영 체크리스트 완료.

## 새 설정 (값은 비공개 설정에서만)

| 설정 | 목적/기본 |
| --- | --- |
| ERASURE_RETIREMENT_WRITE_ENABLED | 대장 종료 실행, false |
| ERASURE_HISTORY_COMPATIBILITY_VERIFIED | 모든 소비자 호환 인수, false |
| ERASURE_RETIREMENT_JOURNAL_DIRECTORY / JOURNAL_STORE_ID | 별도 복구 저장소 |
| ERASURE_RETIREMENT_AUDIT_FILE | 별도 private 폴더의 retirement-audit.bin |
| ERASURE_RETIREMENT_SERVER_UUID / SCOPE_SHA256 | DB와 점검 범위 결합 |
| ERASURE_RETIREMENT_MAX_PER_RUN | 기본 10, 허용 1~100 |
| ERASURE_RETIREMENT_AUDIT_REVIEW_APPROVED | 검토 sealing CLI 승인, false |

기존 LOCAL·암호화 키·DB 세대·GitHub·maintenance 설정을 재사용한다.
기존 준비 배포 스크립트의 활성화 차단을 우회하지 않는다.

## 보류·복구

- 응답 불명확 시 새 작업 대신 원래 독립 작업 재독.
- 0~6단계 만료는 --resume-reviewed로 최신 증거 확인 후 재개.
- 7단계는 저널 정리·부재 fsync 재확인 후 8단계로 완료.
- 새 종료 이력 생성 후 구버전 reader로 단순 롤백 금지.
- 이력·저널 삭제, 기준 건수 초기화, 원본 복원으로 장애 숨기기 금지.
- 전체 서버/디스크 유실·악의적 root에 대한 완전한 복구 보장은 제공하지 않음.

전체 CI·실제 독립 저장소 인수·운영 사본 검토 절차는 미완료다.
이 자료는 운영 활성화 승인서나 법률 검토 완료 증명이 아니다.
사본 증거의 장기 자동 갱신, 최초 이력 전체 조회 비용, 보류 후보 재처리 순서는 추가 운영 검증이 필요하다.
