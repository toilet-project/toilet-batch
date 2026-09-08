package com.geupddong.account;

import java.net.URI;

/** Exact endpoints only. Selection does not attest contractual data residency or live S3 compatibility. */
public record ErasureStorageEndpoint(URI endpoint, String signingRegion) {
    public static ErasureStorageEndpoint resolve(String provider, String rawEndpoint, boolean domesticAccepted) {
        try {
            URI uri = URI.create(rawEndpoint);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getPort() != -1
                    || uri.getQuery() != null || uri.getFragment() != null
                    || !(uri.getRawPath().isEmpty() || uri.getRawPath().equals("/")))
                throw new IllegalArgumentException();
            if ("R2".equals(provider)
                    && uri.getHost().matches("[a-f0-9]{32}(\\.(eu|us))?\\.r2\\.cloudflarestorage\\.com"))
                return new ErasureStorageEndpoint(uri, "auto");
            // Ncloud Storage, NOT legacy Object Storage (which lacks ListObjectsV2).
            // Default closed until contractual and synthetic provider acceptance is recorded.
            if ("NCLOUD_KR".equals(provider) && domesticAccepted
                    && "kr.ncloudstorage.com".equals(uri.getHost()))
                return new ErasureStorageEndpoint(uri, "kr");
            throw new IllegalArgumentException();
        } catch (Exception ignored) {
            throw new IllegalStateException("ERASURE_LEDGER_CONFIGURATION_INVALID");
        }
    }
}
