package com.example.toiletbatch.geocoding;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kakao.geocoding")
public record KakaoGeocodingProperties(
        String baseUrl,
        String restApiKey
) {
}
