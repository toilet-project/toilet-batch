package com.example.toiletbatch.account;

import com.geupddong.account.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.core.env.StandardEnvironment;

/** Builds evidence input from the pre-erasure catalogue. No R2/DB writes or automatic bootstrap. */
public final class ErasureCatalogueExportCli {
    private ErasureCatalogueExportCli() { }

    public static ErasureIntentInventory inventory(List<ErasureRecord> records, String realm, String epoch) {
        var digests = new TreeMap<String,String>();
        for (var record : records) {
            if (!realm.equals(record.realm()) || digests.put(record.objectKey(), ErasureCompletion.digest(record)) != null)
                throw new IllegalStateException("ERASURE_CATALOGUE_INVALID");
        }
        var inventory = new ErasureIntentInventory(1, realm, epoch, digests);
        inventory.verify(records);
        return inventory;
    }

    public static byte[] serialize(ErasureIntentInventory inventory) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .writeValueAsBytes(inventory);
    }

    public static void main(String[] args) {
        try {
            boolean export = args.length == 1 && "--export".equals(args[0]);
            if (args.length > 1 || (args.length == 1 && !export && !"--dry-run".equals(args[0]))) throw new IllegalArgumentException();
            var env = new StandardEnvironment();
            if (!"true".equals(env.getProperty("ERASURE_EVIDENCE_WRITERS_STOPPED"))
                    || !"true".equals(env.getProperty("ERASURE_CATALOGUE_CHECKPOINT_CONFIRMED"))) throw new IllegalStateException();
            int expected = Integer.parseInt(env.getRequiredProperty("ERASURE_CATALOGUE_EXPECTED_OBJECTS"));
            if (expected < 0 || expected > 100000) throw new IllegalArgumentException();
            try (var ledger = ErasureLedgerFactory.configured(env)) {
                var inventory = inventory(ledger.readCatalogue(expected), env.getRequiredProperty("erasure.ledger.realm"),
                        env.getRequiredProperty("ERASURE_EVIDENCE_DATABASE_EPOCH"));
                byte[] bytes = serialize(inventory);
                String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                if (export) {
                    Path target = Path.of(env.getRequiredProperty("ERASURE_CATALOGUE_EXPORT_FILE")).toAbsolutePath().normalize();
                    if (!target.getParent().toRealPath().equals(target.getParent())) throw new IllegalStateException();
                    // Production export is Linux-only with an owner-only directory, not a public artifact.
                    var permissions = Files.getPosixFilePermissions(target.getParent());
                    if (permissions.stream().anyMatch(p -> p.name().startsWith("GROUP_") || p.name().startsWith("OTHERS_")))
                        throw new IllegalStateException();
                    Path checksum = target.resolveSibling(target.getFileName() + ".sha256");
                    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) || Files.exists(checksum, LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException();
                    writePrivateNew(target, bytes);
                    writePrivateNew(checksum, (digest + "\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                }
                System.out.printf("dryRun=%s catalogueRecords=%d inventorySha256=%s%n", !export, expected, digest);
            }
        } catch (Exception ignored) {
            System.err.println("ERASURE_CATALOGUE_EXPORT_FAILED"); System.exit(1);
        }
    }

    private static void writePrivateNew(Path target, byte[] bytes) throws Exception {
        var mode = java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        try (var out = java.nio.channels.FileChannel.open(target,
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), mode)) {
            var buffer = java.nio.ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) out.write(buffer);
            out.force(true);
        }
    }
}
