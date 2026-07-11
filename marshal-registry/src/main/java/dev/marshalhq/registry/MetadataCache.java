package dev.marshalhq.registry;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import dev.marshalhq.core.Coordinates;
import dev.marshalhq.core.VersionMetadata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.*;
import java.util.List;

public class MetadataCache {

    private static final Logger log = LoggerFactory.getLogger(MetadataCache.class);
    private static final long TTL_MS = 24L * 60 * 60 * 1000;

    private final Connection conn;
    private final ObjectMapper mapper;

    public MetadataCache(Path dbPath) throws SQLException {
        this("jdbc:sqlite:" + dbPath);
    }

    /**
     * Package-private: used in tests with {@code "jdbc:sqlite::memory:"}.
     */
    MetadataCache(String jdbcUrl) throws SQLException {
        this.conn = DriverManager.getConnection(jdbcUrl);
        this.mapper = new ObjectMapper()
                .registerModule(new com.fasterxml.jackson.module.paramnames.ParameterNamesModule())
                .registerModule(new JavaTimeModule());
        init();
    }

    private void init() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("""
                        CREATE TABLE IF NOT EXISTS metadata_cache (
                            gav TEXT PRIMARY KEY,
                            json TEXT NOT NULL,
                            cached_at INTEGER NOT NULL
                        )
                    """);
            st.execute("""
                        CREATE TABLE IF NOT EXISTS version_history_cache (
                            ga TEXT PRIMARY KEY,
                            versions_json TEXT NOT NULL,
                            cached_at INTEGER NOT NULL
                        )
                    """);
            // Single-document cache for the Marshal-maintained whitelist: one keyed row,
            // not per-entry. The whole list refreshes as one unit.
            st.execute("""
                        CREATE TABLE IF NOT EXISTS whitelist_cache (
                            id INTEGER PRIMARY KEY CHECK (id = 0),
                            yaml TEXT NOT NULL,
                            updated INTEGER NOT NULL,
                            cached_at INTEGER NOT NULL
                        )
                    """);
        }
    }

    // --- single-document whitelist cache ---

    /** A cached whitelist document: its raw YAML, embedded {@code updated} epoch, and cache time. */
    public record CachedWhitelist(String yaml, long updatedEpochMs, long cachedAtMs) {

        public boolean isFresh() {
            return System.currentTimeMillis() - cachedAtMs < TTL_MS;
        }
    }

    /** The cached whitelist document, or null if none has been stored. Ignores TTL. */
    public CachedWhitelist getWhitelist() {
        try (Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery(
                        "SELECT yaml, updated, cached_at FROM whitelist_cache WHERE id = 0")) {
            if (rs.next()) {
                return new CachedWhitelist(rs.getString("yaml"), rs.getLong("updated"), rs.getLong("cached_at"));
            }
        }
        catch (Exception e) {
            log.warn("Whitelist cache read failed: {}", e.getMessage());
        }
        return null;
    }

    /** Stores the verified whitelist document, replacing any prior copy. */
    public void putWhitelist(String yaml, long updatedEpochMs) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO whitelist_cache (id, yaml, updated, cached_at) VALUES (0, ?, ?, ?)")) {
            ps.setString(1, yaml);
            ps.setLong(2, updatedEpochMs);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        }
        catch (Exception e) {
            log.warn("Whitelist cache write failed: {}", e.getMessage());
        }
    }

    // --- per-GAV metadata (signatureStatus, keyId, depCount, repoUrl, publishedAt) ---

    public VersionMetadata get(Coordinates coordinates) {
        String gav = coordinates.toGav();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT json, cached_at FROM metadata_cache WHERE gav = ?")) {
            ps.setString(1, gav);
            ResultSet rs = ps.executeQuery();
            if (rs.next() && fresh(rs.getLong("cached_at"))) {
                return mapper.readValue(rs.getString("json"), VersionMetadata.class);
            }
        }
        catch (Exception e) {
            log.warn("Cache read failed for {}: {}", gav, e.getMessage());
        }
        return null;
    }

    public void put(Coordinates coordinates, VersionMetadata metadata) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO metadata_cache (gav, json, cached_at) VALUES (?, ?, ?)")) {
            ps.setString(1, coordinates.toGav());
            ps.setString(2, mapper.writeValueAsString(metadata));
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        }
        catch (Exception e) {
            log.warn("Cache write failed for {}: {}", coordinates.toGav(), e.getMessage());
        }
    }

    // --- per-GA version history ---

    public List<String> getVersionHistory(String groupId, String artifactId) {
        String ga = groupId + ":" + artifactId;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT versions_json, cached_at FROM version_history_cache WHERE ga = ?")) {
            ps.setString(1, ga);
            ResultSet rs = ps.executeQuery();
            if (rs.next() && fresh(rs.getLong("cached_at"))) {
                return mapper.readValue(rs.getString("versions_json"),
                        new TypeReference<List<String>>() {

                        });
            }
        }
        catch (Exception e) {
            log.warn("Version history cache read failed for {}: {}", ga, e.getMessage());
        }
        return null;
    }

    public void putVersionHistory(String groupId, String artifactId, List<String> versions) {
        String ga = groupId + ":" + artifactId;
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO version_history_cache (ga, versions_json, cached_at) VALUES (?, ?, ?)")) {
            ps.setString(1, ga);
            ps.setString(2, mapper.writeValueAsString(versions));
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        }
        catch (Exception e) {
            log.warn("Version history cache write failed for {}: {}", ga, e.getMessage());
        }
    }

    public void close() throws SQLException {
        conn.close();
    }

    private boolean fresh(long cachedAt) {
        return System.currentTimeMillis() - cachedAt < TTL_MS;
    }
}
