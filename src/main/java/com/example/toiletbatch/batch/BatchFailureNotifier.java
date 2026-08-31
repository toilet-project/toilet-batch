package com.example.toiletbatch.batch;

import java.time.ZonedDateTime;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** 최종 재시도까지 실패한 배치만 운영 Webhook으로 알립니다. 비밀값과 원문 응답은 전송하지 않습니다. */
@Component
public class BatchFailureNotifier {

    private static final Logger log = LoggerFactory.getLogger(BatchFailureNotifier.class);
    private final RestClient restClient;
    private final BatchNotificationProperties notificationProperties;
    private final RestroomSyncProperties syncProperties;

    public BatchFailureNotifier(
            RestClient.Builder restClientBuilder,
            BatchNotificationProperties notificationProperties,
            RestroomSyncProperties syncProperties
    ) {
        this.restClient = restClientBuilder.build();
        this.notificationProperties = notificationProperties;
        this.syncProperties = syncProperties;
    }

    public void notifyFailure(RuntimeException exception) {
        if (!notificationProperties.enabled()) {
            log.warn("배치 실패 Webhook이 설정되지 않아 알림 전송을 건너뜁니다.");
            return;
        }

        String errorType = exception.getClass().getSimpleName();
        String content = "[급똥] 공공화장실 배치 실패\n"
                + "- 시각: " + ZonedDateTime.now(syncProperties.zoneId()) + "\n"
                + "- 결과: API 재시도 " + syncProperties.maxAttempts() + "회 소진 후 실패\n"
                + "- 오류 유형: " + errorType + "\n"
                + "- 조치: 관리자 배치 실행 이력에서 실패 사유를 확인해 주세요.";

        try {
            restClient.post()
                    .uri(notificationProperties.webhookUrl())
                    .body(Map.of("content", content))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException notificationException) {
            log.error("배치 실패 Webhook 전송에도 실패했습니다. errorType={}",
                    notificationException.getClass().getSimpleName());
        }
    }
}
