# 급똥 Batch

급똥 서비스의 공중화장실 공공데이터를 수집·정제하여 MySQL에 반영하는 배치 서버입니다.

- 서비스: <https://geupddong.com>
- 운영 API: <https://api.geupddong.com>
- 운영 구조: [아키텍처 v2](https://github.com/toilet-project/docs/blob/main/architecture-v2.md)

## 역할

- 공공데이터 API에서 화장실 데이터를 수집
- 주소·좌표 등 조회에 필요한 데이터를 정제
- 운영 MySQL의 화장실 데이터를 생성·갱신

## 공공데이터 동기화

- 대상 API: 공공데이터포털 `공중화장실정보 데이터 조회 /info_v2`
- 조회 기준: 데이터 갱신시각 범위 `DAT_UPDT_PNT`의 **이상(from) · 미만(to)**, 즉 `[from, to)`
- 요청 형식: JSON, 페이지당 최대 100건을 순회해 내부 `PublicRestroomRecord`로 변환
- 다음 단계: 변환된 데이터를 MySQL에 생성·갱신하는 작업은 별도 배치 항목에서 연결합니다.

배포 환경에는 공공데이터포털에서 발급한 일반 인증키를 `PUBLIC_DATA_API_KEY`로 등록해야 합니다. 키 값은 저장소나 `.env` 예시에 넣지 않습니다.

## 기술 및 실행 환경

- Java 21 (Eclipse Temurin), Spring Boot, MySQL, Docker
- Mini PC Docker Compose 내부에서 실행됩니다.
- DB 연결값과 공공데이터 API 키는 환경 변수로만 관리하며 `.env`는 커밋하지 않습니다.

## 배포

`main` 반영 시 GitHub Actions가 Docker 이미지를 빌드한 뒤 Mini PC 배치 컨테이너를 갱신합니다.
자세한 운영 절차는 [운영 문서](https://github.com/toilet-project/docs/blob/main/operations.md)를 참고하세요.

### 수동 동기화

장애 복구나 최초 검증이 필요할 때는 Mini PC 내부에서만 아래 환경 변수를 지정해 최근 `overlap-days`(기본 3일) 범위를 한 번 동기화할 수 있습니다.

- `BATCH_RESTROOM_SYNC_RUN_ON_STARTUP=true`
- `SPRING_MAIN_WEB_APPLICATION_TYPE=none`

이 실행 방식은 HTTP API를 노출하지 않으며, 완료 후 프로세스가 종료됩니다.
