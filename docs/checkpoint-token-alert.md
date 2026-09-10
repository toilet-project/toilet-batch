# 독립 검증 토큰 만료 알림

기존 Spring 배치의 `BatchFailureNotifier`와 `BATCH_FAILURE_WEBHOOK_URL`을 재사용한다. 별도 Python/Node 서비스·새 Discord 웹훅·DB 테이블을 추가하지 않는다. 이전 JavaScript 알림 후보는 운영 설치 대상으로 사용하지 않는다.

`batch.checkpoint-token-monitor.enabled=true` 승인·배포 시에만 빈을 등록한다. 기본값은 OFF. 기존 `ERASURE_CHECKPOINT_GITHUB_TOKEN`으로 고정된 독립 저장소의 GET 응답 헤더를 확인하며 토큰 값과 응답 본문을 알림에 넣지 않는다. HTTP 리다이렉트는 따르지 않으며 연결/응답 timeout을 사용한다.

Spring 내부 기본 점검 시각은 09:00 KST이며 `batch.checkpoint-token-monitor.cron`으로 조정 가능하다. 기존 02시 공공데이터→회원 파기 후속 체인은 변경하지 않는다. 현재 Spring scheduler pool이 1이므로 오래 실행 중인 다른 예약 작업이 있으면 점검이 지연될 수 있다. 서버/프로세스 정지는 기존 외부 감시의 영역이다.

만료 30/14/7/1일 이내는 단계별로 알리고, 만료·정보 확인 실패는 하루마다 알린다. 전송 성공한 경우에만 메모리 중복 방지 상태를 갱신한다. 새 DB나 Redis 상태를 만들지 않았으므로 재시작 시 같은 알림이 한 번 더 갈 수 있다. 전송 실패하면 다음 점검에서 다시 시도한다. 토큰 갱신으로 만료일이 바뀌면 새 기준으로 판단한다.

현재는 로컬 코드/모의 검증만 수행했다. 새 설정 활성화·배포·실제 Discord 메시지 전송·예약 배치 조회는 하지 않았다. 기존 알림 메서드는 그대로 유지하며 공통 payload의 멘션을 비활성화하고 클라이언트 식별 헤더를 보완했다.
