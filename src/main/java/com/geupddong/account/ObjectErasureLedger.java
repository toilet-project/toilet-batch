package com.geupddong.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Same authenticated record format for local files and object storage. Never deletes objects. */
public class ObjectErasureLedger implements CheckpointedErasureLedger.SnapshotLedger {
    private final ErasureObjectStore objects;
    private final ErasureCipher cipher;
    private final String realm;
    private final boolean catalogueEnabled;
    private final ObjectMapper json = new ObjectMapper();

    public ObjectErasureLedger(ErasureObjectStore objects, ErasureCipher cipher, String realm, boolean catalogueEnabled) {
        if (realm == null || !realm.matches("[a-z0-9-]{3,40}")) throw unavailable();
        this.objects = objects; this.cipher = cipher; this.realm = realm; this.catalogueEnabled = catalogueEnabled;
    }

    @Override public void ensureRecorded(ErasureRecord record) {
        try {
            if (!realm.equals(record.realm())) throw unavailable();
            if (catalogueEnabled) {
                String key = catalogueKey(record);
                objects.putIfAbsent(key, cipher.encryptDocument(realm, key, json.writeValueAsBytes(record)));
                if (!record.equals(readRecord(key, true))) throw unavailable();
            }
            objects.putIfAbsent(record.objectKey(), cipher.encrypt(record));
            if (!record.equals(readRecord(record.objectKey(), false))) throw unavailable();
        } catch (Exception ignored) { throw unavailable(); }
    }

    private String catalogueKey(ErasureRecord record) {
        return "catalogue-v1/" + realm + "/" + record.withdrawalKey() + ".bin";
    }

    private ErasureRecord readRecord(String key, boolean catalogue) {
        try {
            byte[] bytes = objects.read(key);
            if (bytes == null) return null;
            if (!catalogue) return cipher.decrypt(realm, key, bytes);
            var record = json.readValue(cipher.decryptDocument(realm, key, bytes), ErasureRecord.class);
            if (!realm.equals(record.realm()) || !catalogueKey(record).equals(key)) throw unavailable();
            return record;
        } catch (Exception ignored) { throw unavailable(); }
    }

    public List<ErasureRecord> readAll(int expected) { return readExact(expected, false); }
    public List<ErasureRecord> readCatalogue(int expected) { return readExact(expected, true); }
    @Override public List<ErasureRecord> intentsAtMost(int maximum) { return readBounded(maximum, false); }
    @Override public List<ErasureRecord> catalogueAtMost(int maximum) { return readBounded(maximum, true); }

    private List<ErasureRecord> readExact(int expected, boolean catalogue) {
        var records = readBounded(expected, catalogue);
        if (records.size() != expected) throw unavailable();
        return records;
    }

    private List<ErasureRecord> readBounded(int maximum, boolean catalogue) {
        try {
            if (maximum < 0 || maximum > 1000000) throw unavailable();
            String prefix = (catalogue ? "catalogue-v1/" : "v1/") + realm + "/";
            var records = new ArrayList<ErasureRecord>();
            var seen = new HashSet<String>();
            for (String key : objects.list(prefix, maximum)) {
                if (!key.startsWith(prefix) || !seen.add(key) || records.size() >= maximum) throw unavailable();
                var record = readRecord(key, catalogue);
                if (record == null) throw unavailable();
                records.add(record);
            }
            return List.copyOf(records);
        } catch (Exception ignored) { throw unavailable(); }
    }

    public ErasureCompletion ensureCompletion(ErasureCompletion proposed) {
        try {
            if (!realm.equals(proposed.realm())) throw unavailable();
            var intent = readRecord("v1/" + realm + "/" + proposed.withdrawalKey() + ".bin", false);
            if (intent == null || !ErasureCompletion.digest(intent).equals(proposed.intentDigest())) throw unavailable();
            Instant eligible = LocalDateTime.parse(intent.eligibleAt()).atZone(ZoneId.of("Asia/Seoul")).toInstant();
            if (Instant.parse(proposed.firstConfirmedAbsentAt()).isBefore(eligible)) throw unavailable();
            objects.putIfAbsent(proposed.objectKey(), cipher.encryptDocument(realm, proposed.objectKey(), json.writeValueAsBytes(proposed)));
            var actual = readCompletion(proposed);
            if (actual == null || Instant.parse(actual.firstConfirmedAbsentAt()).isBefore(eligible)) throw unavailable();
            return actual;
        } catch (Exception ignored) { throw unavailable(); }
    }

    public ErasureCompletion readCompletion(ErasureCompletion expected) {
        try {
            if (!realm.equals(expected.realm())) throw unavailable();
            byte[] bytes = objects.read(expected.objectKey());
            if (bytes == null) return null;
            var actual = json.readValue(cipher.decryptDocument(realm, expected.objectKey(), bytes), ErasureCompletion.class);
            if (!actual.objectKey().equals(expected.objectKey()) || !actual.intentDigest().equals(expected.intentDigest())
                    || Instant.parse(actual.firstConfirmedAbsentAt()).isAfter(Instant.parse(expected.firstConfirmedAbsentAt()))) throw unavailable();
            return actual;
        } catch (Exception ignored) { throw unavailable(); }
    }

    @Override public void close() { objects.close(); }
    private static IllegalStateException unavailable() { return new IllegalStateException("ERASURE_LEDGER_UNAVAILABLE"); }
}
