package com.example.toiletbatch.publicdata;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "public-data.restroom")
public record PublicRestroomApiProperties(
        URI baseUrl,
        String serviceKey,
        int pageSize
) {

    public PublicRestroomApiProperties {
        if (baseUrl == null) {
            baseUrl = URI.create("https://apis.data.go.kr/1741000/public_restroom_info_v2");
        }
        if (pageSize < 1 || pageSize > 100) {
            pageSize = 100;
        }
        serviceKey = serviceKey == null ? "" : serviceKey.trim();
    }
}
