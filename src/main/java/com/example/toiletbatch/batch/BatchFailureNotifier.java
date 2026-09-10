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

    public void notifyAccountErasureFailure(int failed, long overdue, boolean infrastructureFailure) {
        send("[급똥] 회원정보 파기 확인 필요\n"
                + "- 시각: " + ZonedDateTime.now(syncProperties.zoneId()) + "\n"
                + "- 계정 처리 실패: " + failed + ", 기한 경과 미완료: " + overdue + "\n"
                + "- 기반 시스템 오류: " + infrastructureFailure + "\n"
                + "- 조치: account_withdrawal 체크포인트와 배치 로그를 확인하세요. 다음 일일 후속 작업에서 재시도합니다.");
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

        send(content);
    }

    public boolean notifyCheckpointTokenExpiry(String level) {
        String description = switch (level) {
            case "UNKNOWN" -> "만료 정보 확인 실패";
            case "EXPIRED" -> "만료됨";
            case "DAY_1" -> "만료 1일 이내";
            case "DAY_7" -> "만료 7일 이내";
            case "DAY_14" -> "만료 14일 이내";
            case "DAY_30" -> "만료 30일 이내";
            default -> throw new IllegalArgumentException("EXPIRY_LEVEL_INVALID");
        };
        return send("[급똥] 독립 검증 GitHub 토큰: " + description
                + "\n- 조치: 전용 토큰 상태와 교체 일정을 확인하세요.");
    }

    private boolean send(String content) {
        if (!notificationProperties.enabled()) {
            log.warn("Batch notification webhook is not configured");
            return false;
        }
        try {
            restClient.post()
                    .uri(notificationProperties.webhookUrl())
                    .header("User-Agent", "DiscordBot (https://geupddong.com, 1.0)")
                    .body(Map.of("content", content, "allowed_mentions", Map.of("parse", java.util.List.of())))
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RuntimeException notificationException) {
            log.error("배치 실패 Webhook 전송에도 실패했습니다. errorType={}",
                    notificationException.getClass().getSimpleName());
            return false;
        }
    }
}
