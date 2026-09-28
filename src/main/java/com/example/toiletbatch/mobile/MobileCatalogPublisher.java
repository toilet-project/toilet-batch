package com.example.toiletbatch.mobile;

import org.springframework.stereotype.Service;
import javax.sql.DataSource;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class MobileCatalogPublisher {
    private final MobileCatalogProperties properties;
    private final MobileCatalogExporter exporter;

    public MobileCatalogPublisher(DataSource dataSource, MobileCatalogProperties properties) {
        this.properties = properties;
        this.exporter = new MobileCatalogExporter(dataSource);
    }

    public String publish(boolean registerBaseline, List<String> retiredBaselines) throws Exception {
        if (!properties.enabled()) return "disabled";
        if (!properties.origin().equals("https://mobile-data.geupddong.com") || properties.publishToken().isBlank())
            throw new IllegalStateException("Mobile catalog origin or publication credential is not configured");
        for (String version : retiredBaselines)
            if (!version.matches("[0-9]{8}T[0-9]{6}Z-[a-f0-9]{16}")) throw new IllegalArgumentException("Invalid baseline version");
        var configuredRoot = Path.of(properties.workDirectory()).toAbsolutePath().normalize();
        Files.createDirectories(configuredRoot);
        var root = configuredRoot.toRealPath();
        try (var channel = FileChannel.open(root.resolve(".publish.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.tryLock()) {
            if (lock == null) return "already-running";
            var work = Files.createTempDirectory(root, "run-").toRealPath();
            if (!work.startsWith(root) || work.equals(root)) throw new IllegalStateException("Invalid work directory");
            try {
                var source = work.resolve("public-catalog.jsonl");
                exporter.export(source);
                var command = new ArrayList<>(List.of(properties.python(), "-B", properties.script(), source.toString(), work.resolve("output").toString()));
                if (registerBaseline) command.add("--register-baseline");
                for (String version : retiredBaselines) { command.add("--retire-baseline"); command.add(version); }
                var builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(work.resolve("publisher.log").toFile());
                builder.environment().put("MOBILE_CATALOG_ORIGIN", properties.origin());
                builder.environment().put("MOBILE_CATALOG_PUBLISH_TOKEN", properties.publishToken());
                var process = builder.start();
                try {
                    if (!process.waitFor(properties.timeoutSeconds(), TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Mobile catalog publication timed out");
                    }
                    if (process.exitValue() != 0) throw new IllegalStateException("Mobile catalog publisher failed (exit=" + process.exitValue() + ")");
                    var receipt = work.resolve("output/report.json");
                    if (Files.size(receipt) > 32_768) throw new IllegalStateException("Invalid catalog receipt size");
                    return Files.readString(receipt).strip();
                } finally {
                    if (process.isAlive()) { process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS); }
                }
            } finally {
                // Only this invocation's generated files are removed, never application/database files.
                try (var paths = Files.walk(work)) {
                    for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                }
            }
        }
    }
}
