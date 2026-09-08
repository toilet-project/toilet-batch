package com.geupddong.account;

import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class ErasureStorageEndpointTest {
    private static final String R2 = "https://" + "a".repeat(32) + ".us.r2.cloudflarestorage.com";
    private static final String KR = "https://kr.ncloudstorage.com";
    private void rejected(String provider, String endpoint, boolean accepted) {
        var error = assertThrows(IllegalStateException.class,
                () -> ErasureStorageEndpoint.resolve(provider, endpoint, accepted));
        assertEquals("ERASURE_LEDGER_CONFIGURATION_INVALID", error.getMessage());
        assertNull(error.getCause());
    }
    @Test void domesticSelectionRequiresSeparateAcceptance() {
        rejected("NCLOUD_KR", KR, false);
    }
    @Test void exactKoreanEndpointUsesKoreanSigningRegion() {
        for (String endpoint : new String[]{KR, KR + "/"}) {
            var result = ErasureStorageEndpoint.resolve("NCLOUD_KR", endpoint, true);
            assertEquals("kr", result.signingRegion());
            assertEquals(endpoint, result.endpoint().toString());
        }
    }
    @Test void overseasAndLegacyEndpointsAreNeverDomesticFallbacks() {
        for (String endpoint : new String[]{R2, "https://us.ncloudstorage.com",
                "https://kr.object.ncloudstorage.com", "https://s3.ap-northeast-2.amazonaws.com"})
            rejected("NCLOUD_KR", endpoint, true);
    }
    @Test void pathsQueriesFragmentsCredentialsPortsAndLookalikesAreRejected() {
        for (String endpoint : new String[]{KR + "/bucket", KR + "/%2f",
                KR + "?key=secret", KR + "#secret", KR + ":443",
                "https://user:secret@kr.ncloudstorage.com",
                "http://kr.ncloudstorage.com", "https://kr.ncloudstorage.com.evil.invalid",
                "https://kr.ncloudstorage.com.", "https://127.0.0.1", "https://bucket.kr.ncloudstorage.com"})
            rejected("NCLOUD_KR", endpoint, true);
    }
    @Test void malformedConfigurationHasOnlySafeErrorMessage() {
        for (String endpoint : new String[]{null, "", "not a uri", " https://kr.ncloudstorage.com"})
            rejected("NCLOUD_KR", endpoint, true);
        for (String provider : new String[]{null, "", "ncloud_kr", "AUTO"})
            rejected(provider, KR, true);
    }
    @Test void legacyR2SelectionRemainsExplicitAndCompatible() {
        for (String suffix : new String[]{"", ".eu", ".us"}) {
            var result = ErasureStorageEndpoint.resolve("R2",
                    "https://" + "a".repeat(32) + suffix + ".r2.cloudflarestorage.com", false);
            assertEquals("auto", result.signingRegion());
        }
        rejected("R2", KR, true);
        rejected("R2", R2 + "?token=secret", true);
    }
    private MockEnvironment environment(String provider, String endpoint) {
        return new MockEnvironment()
                .withProperty("erasure.ledger.provider", provider)
                .withProperty("erasure.ledger.endpoint", endpoint)
                .withProperty("erasure.ledger.realm", "verification")
                .withProperty("erasure.ledger.bucket", "synthetic-local-no-network")
                .withProperty("erasure.ledger.active-key-id", "test")
                .withProperty("erasure.ledger.keys-json", "{\"test\":\"" +
                        Base64.getEncoder().encodeToString(new byte[32]) + "\"}")
                .withProperty("erasure.ledger.access-key-id", "synthetic")
                .withProperty("erasure.ledger.secret-access-key", "synthetic");
    }
    @Test void factoryBlocksUnacceptedDomesticProviderBeforeAnyClientUse() {
        assertThrows(IllegalStateException.class, () -> ErasureLedgerFactory.configured(environment("NCLOUD_KR", KR)));
    }
    @Test void configuredDomesticClientCanBeConstructedWithoutNetwork() {
        try (var client = ErasureLedgerFactory.configured(environment("NCLOUD_KR", KR)
                .withProperty("erasure.ledger.domestic-acceptance-verified", "true"))) {
            assertNotNull(client);
        }
    }
    @Test void existingR2ClientCanStillBeConstructedWithoutNetwork() {
        try (var client = ErasureLedgerFactory.configured(environment("R2", R2))) {
            assertNotNull(client);
        }
    }
    @Test void disabledLedgerDoesNotConstructOrEnableDomesticClient() {
        var env = environment("NCLOUD_KR", KR);
        var disabled = ErasureLedgerFactory.create(env);
        var record = new ErasureRecord(1, "verification", 42, "2000-01-01T00:00",
                "01234567-1234-1234-1234-123456789012", "2000-04-01T00:00");
        assertEquals("ERASURE_LEDGER_NOT_CONFIGURED",
                assertThrows(IllegalStateException.class, () -> disabled.ensureRecorded(record)).getMessage());
    }
}
