package com.geupddong.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** AES-256-GCM; version, key ID, realm and object key are authenticated. */
public final class ErasureCipher {
    private static final byte[] MAGIC = {'G','E','L',1};
    private final Map<String, SecretKeySpec> keys = new HashMap<>();
    private final String activeKey;
    private final ObjectMapper json = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();

    public ErasureCipher(String activeKey, Map<String, String> encodedKeys) {
        if (activeKey == null || !activeKey.matches("[a-zA-Z0-9_-]{1,40}") || encodedKeys.size() > 10)
            throw failure();
        encodedKeys.forEach((id, value) -> {
            if (!id.matches("[a-zA-Z0-9_-]{1,40}")) throw failure();
            byte[] bytes = Base64.getDecoder().decode(value);
            if (bytes.length != 32) throw failure();
            keys.put(id, new SecretKeySpec(bytes, "AES"));
            java.util.Arrays.fill(bytes, (byte) 0);
        });
        if (!keys.containsKey(activeKey)) throw failure();
        this.activeKey = activeKey;
    }

    public byte[] encrypt(ErasureRecord record) {
        try {
            byte[] id = activeKey.getBytes(StandardCharsets.US_ASCII);
            byte[] nonce = new byte[12]; random.nextBytes(nonce);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keys.get(activeKey), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad(activeKey, record.realm(), record.objectKey()));
            byte[] payload = cipher.doFinal(json.writeValueAsBytes(record));
            return ByteBuffer.allocate(5 + id.length + 12 + payload.length)
                    .put(MAGIC).put((byte)id.length).put(id).put(nonce).put(payload).array();
        } catch (Exception ignored) { throw failure(); }
    }

    public ErasureRecord decrypt(String realm, String objectKey, byte[] envelope) {
        try {
            if (envelope.length < 34 || envelope.length > 8192) throw failure();
            var input = ByteBuffer.wrap(envelope);
            for (byte magic : MAGIC) if (input.get() != magic) throw failure();
            int size = Byte.toUnsignedInt(input.get());
            if (size < 1 || size > 40 || input.remaining() < size + 28) throw failure();
            byte[] id = new byte[size]; input.get(id);
            String keyId = new String(id, StandardCharsets.US_ASCII);
            var key = keys.get(keyId);
            if (key == null) throw failure();
            byte[] nonce = new byte[12]; input.get(nonce);
            byte[] payload = new byte[input.remaining()]; input.get(payload);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad(keyId, realm, objectKey));
            var record = json.readValue(cipher.doFinal(payload), ErasureRecord.class);
            if (!realm.equals(record.realm()) || !objectKey.equals(record.objectKey())) throw failure();
            return record;
        } catch (Exception ignored) { throw failure(); }
    }

    private byte[] aad(String keyId, String realm, String objectKey) {
        return ("geupddong-erasure-v1\n" + keyId + "\n" + realm + "\n" + objectKey)
                .getBytes(StandardCharsets.UTF_8);
    }
    private static IllegalStateException failure() { return new IllegalStateException("ERASURE_LEDGER_CRYPTO_INVALID"); }
}
