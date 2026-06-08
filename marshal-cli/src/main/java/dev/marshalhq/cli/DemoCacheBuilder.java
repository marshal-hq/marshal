package dev.marshalhq.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import dev.marshalhq.core.Coordinates;
import dev.marshalhq.core.SignatureStatus;
import dev.marshalhq.core.VersionMetadata;

import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.List;

/**
 * Builds the curated SQLite cache for the demo fixture in examples/demo/.
 * Metadata entries use cached_at=Long.MAX_VALUE so they never expire.
 *
 * Usage (from repo root after ./gradlew :marshal-cli:shadowJar):
 *   java -cp marshal-cli/build/libs/marshal-cli-*.jar dev.marshalhq.cli.DemoCacheBuilder
 *
 * Output: examples/demo/marshal-cache.db
 */
public class DemoCacheBuilder {

    public static void main(String[] args) throws Exception {
        String outputPath = args.length > 0 ? args[0] : "examples/demo/marshal-cache.db";
        Path.of(outputPath).getParent().toFile().mkdirs();

        ObjectMapper mapper = new ObjectMapper()
            .registerModule(new ParameterNamesModule())
            .registerModule(new JavaTimeModule());

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + outputPath)) {
            initSchema(conn);
            populate(conn, mapper);
        }
        System.out.println("Demo cache written to: " + outputPath);
    }

    private static void initSchema(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS metadata_cache (
                    gav TEXT PRIMARY KEY,
                    json TEXT NOT NULL,
                    cached_at INTEGER NOT NULL
                )""");
            st.execute("""
                CREATE TABLE IF NOT EXISTS version_history_cache (
                    ga TEXT PRIMARY KEY,
                    versions_json TEXT NOT NULL,
                    cached_at INTEGER NOT NULL
                )""");
        }
    }

    private static void populate(Connection conn, ObjectMapper mapper) throws Exception {

        // ── GREEN: commons-lang3 3.14.0 ─────────────────────────────────────
        Coordinates lang3     = new Coordinates("org.apache.commons", "commons-lang3", "3.14.0");
        Coordinates lang3Prev = new Coordinates("org.apache.commons", "commons-lang3", "3.13.0");
        putMeta(conn, mapper, lang3, new VersionMetadata(lang3,
            null, "A1B2C3D4", SignatureStatus.PRESENT, List.of(), 4,
            "https://github.com/apache/commons-lang",
            Instant.parse("2024-01-10T10:00:00Z"), false));
        putMeta(conn, mapper, lang3Prev, new VersionMetadata(lang3Prev,
            null, "A1B2C3D4", SignatureStatus.PRESENT, List.of(), 4,
            "https://github.com/apache/commons-lang",
            Instant.parse("2023-09-18T10:00:00Z"), false));
        putHistory(conn, mapper, "org.apache.commons", "commons-lang3",
            List.of("3.14.0", "3.13.0", "3.12.0"));

        // ── GREEN: slf4j-api 2.0.12 ──────────────────────────────────────────
        Coordinates slf4j     = new Coordinates("org.slf4j", "slf4j-api", "2.0.12");
        Coordinates slf4jPrev = new Coordinates("org.slf4j", "slf4j-api", "2.0.11");
        putMeta(conn, mapper, slf4j, new VersionMetadata(slf4j,
            null, "E5F6G7H8", SignatureStatus.PRESENT, List.of(), 1,
            "https://github.com/qos-ch/slf4j",
            Instant.parse("2024-02-01T12:00:00Z"), false));
        putMeta(conn, mapper, slf4jPrev, new VersionMetadata(slf4jPrev,
            null, "E5F6G7H8", SignatureStatus.PRESENT, List.of(), 1,
            "https://github.com/qos-ch/slf4j",
            Instant.parse("2023-12-15T09:00:00Z"), false));
        putHistory(conn, mapper, "org.slf4j", "slf4j-api",
            List.of("2.0.12", "2.0.11", "2.0.10"));

        // ── ORANGE: commons-io 2.16.1 — signature dropped ───────────────────
        // Previous: PRESENT signed. Current: ABSENT.
        // SIG_DROPPED (40) + MISSING_SIG (15) = 55 → ORANGE
        Coordinates io     = new Coordinates("org.apache.commons", "commons-io", "2.16.1");
        Coordinates ioPrev = new Coordinates("org.apache.commons", "commons-io", "2.15.1");
        putMeta(conn, mapper, io, new VersionMetadata(io,
            null, null, SignatureStatus.ABSENT, List.of(), 6,
            "https://github.com/apache/commons-io",
            Instant.parse("2024-03-14T14:00:00Z"), false));
        putMeta(conn, mapper, ioPrev, new VersionMetadata(ioPrev,
            null, "C9D0E1F2", SignatureStatus.PRESENT, List.of(), 6,
            "https://github.com/apache/commons-io",
            Instant.parse("2023-10-05T11:00:00Z"), false));
        putHistory(conn, mapper, "org.apache.commons", "commons-io",
            List.of("2.16.1", "2.15.1", "2.15.0"));

        // ── RED: jackson-databind 2.17.0 — new maintainer + signature dropped
        // NEW_MAINTAINER (35) + SIG_DROPPED (40) + MISSING_SIG (15) = 90 → RED
        Coordinates jd     = new Coordinates("com.fasterxml.jackson.core", "jackson-databind", "2.17.0");
        Coordinates jdPrev = new Coordinates("com.fasterxml.jackson.core", "jackson-databind", "2.16.2");
        putMeta(conn, mapper, jd, new VersionMetadata(jd,
            "new-publisher@example.com", null, SignatureStatus.ABSENT, List.of(), 9,
            "https://github.com/FasterXML/jackson-databind",
            Instant.parse("2024-03-20T09:00:00Z"), false));
        putMeta(conn, mapper, jdPrev, new VersionMetadata(jdPrev,
            "original@fasterxml.com", "F3A4B5C6", SignatureStatus.PRESENT, List.of(), 9,
            "https://github.com/FasterXML/jackson-databind",
            Instant.parse("2024-01-30T10:00:00Z"), false));
        putHistory(conn, mapper, "com.fasterxml.jackson.core", "jackson-databind",
            List.of("2.17.0", "2.16.2", "2.16.1", "2.15.4"));

        // ── GREEN: spring-core 6.1.4 ────────────────────────────────────────
        Coordinates spring     = new Coordinates("org.springframework", "spring-core", "6.1.4");
        Coordinates springPrev = new Coordinates("org.springframework", "spring-core", "6.1.3");
        putMeta(conn, mapper, spring, new VersionMetadata(spring,
            null, "G7H8I9J0", SignatureStatus.PRESENT, List.of(), 3,
            "https://github.com/spring-projects/spring-framework",
            Instant.parse("2024-02-22T08:00:00Z"), false));
        putMeta(conn, mapper, springPrev, new VersionMetadata(springPrev,
            null, "G7H8I9J0", SignatureStatus.PRESENT, List.of(), 3,
            "https://github.com/spring-projects/spring-framework",
            Instant.parse("2024-01-18T08:00:00Z"), false));
        putHistory(conn, mapper, "org.springframework", "spring-core",
            List.of("6.1.4", "6.1.3", "6.1.2", "6.0.17"));
    }

    private static void putMeta(Connection conn, ObjectMapper mapper,
                                 Coordinates coords, VersionMetadata meta) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
            "INSERT OR REPLACE INTO metadata_cache (gav, json, cached_at) VALUES (?, ?, ?)")) {
            ps.setString(1, coords.toGav());
            ps.setString(2, mapper.writeValueAsString(meta));
            ps.setLong(3, Long.MAX_VALUE); // never expires
            ps.executeUpdate();
        }
    }

    private static void putHistory(Connection conn, ObjectMapper mapper,
                                    String groupId, String artifactId,
                                    List<String> versions) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
            "INSERT OR REPLACE INTO version_history_cache (ga, versions_json, cached_at) VALUES (?, ?, ?)")) {
            ps.setString(1, groupId + ":" + artifactId);
            ps.setString(2, mapper.writeValueAsString(versions));
            ps.setLong(3, Long.MAX_VALUE); // never expires
            ps.executeUpdate();
        }
    }
}
