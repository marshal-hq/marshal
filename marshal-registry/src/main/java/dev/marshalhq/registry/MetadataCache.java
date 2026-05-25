package dev.marshalhq.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.marshalhq.core.Coordinates;
import dev.marshalhq.core.VersionMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.*;

public class MetadataCache {
    private static final Logger log = LoggerFactory.getLogger(MetadataCache.class);
    private final Connection conn;
    private final ObjectMapper mapper;

    public MetadataCache(Path dbPath) throws SQLException {
        this.conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
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
        }
    }

    public VersionMetadata get(Coordinates coords) {
        String gav = coords.toGav();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT json, cached_at FROM metadata_cache WHERE gav = ?")) {
            ps.setString(1, gav);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                long cachedAt = rs.getLong("cached_at");
                long ttlMs = 24L * 60 * 60 * 1000;
                if (System.currentTimeMillis() - cachedAt < ttlMs) {
                    return mapper.readValue(rs.getString("json"), VersionMetadata.class);
                }
            }
        } catch (Exception e) {
            log.warn("Cache read failed for {}: {}", gav, e.getMessage());
        }
        return null;
    }

    public void put(Coordinates coords, VersionMetadata metadata) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO metadata_cache (gav, json, cached_at) VALUES (?, ?, ?)")) {
            ps.setString(1, coords.toGav());
            ps.setString(2, mapper.writeValueAsString(metadata));
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (Exception e) {
            log.warn("Cache write failed for {}: {}", coords.toGav(), e.getMessage());
        }
    }

    public void close() throws SQLException {
        conn.close();
    }
}
