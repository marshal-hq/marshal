package dev.marshalhq.core.whitelist;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses whitelist YAML into a validated {@link Whitelist}. Two flavours share the
 * granularity rule (GAV-pinned, no wildcards) but differ in required fields:
 * <ul>
 *   <li><b>User</b> ({@code marshal-whitelist.yml} at the repo root): {@code reason}
 *       and {@code expires} are required; placeholder reasons are rejected.</li>
 *   <li><b>Marshal</b> (embedded baseline / signed remote): {@code reason} required,
 *       no {@code expires}; carries a top-level {@code updated} timestamp.</li>
 * </ul>
 * The whitelist is security-relevant, so any schema violation is a hard
 * {@link WhitelistException}, never a silently-skipped line.
 */
public final class WhitelistLoader {

    public static final String USER_FILENAME = "marshal-whitelist.yml";
    public static final String EMBEDDED_RESOURCE = "marshal-whitelist.yml";

    private static final Logger log = LoggerFactory.getLogger(WhitelistLoader.class);
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private WhitelistLoader() {
    }

    /**
     * Loads the user whitelist from {@code path}. An absent file is an empty
     * whitelist, not an error — the file is optional. A present-but-malformed file is
     * a hard error so a broken trust artifact cannot be silently ignored.
     */
    public static Whitelist loadUser(Path path) {
        if (path == null || !Files.exists(path)) {
            return Whitelist.empty(WhitelistSource.USER);
        }
        String content;
        try {
            content = Files.readString(path);
        }
        catch (IOException e) {
            throw new WhitelistException("Could not read user provided whitelist file " + path + ": " + e.getMessage(), e);
        }
        return parse(content, WhitelistSource.USER);
    }

    /** Parses a Marshal-maintained whitelist document (embedded baseline or remote copy). */
    public static Whitelist parseMarshal(String yaml) {
        return parse(yaml, WhitelistSource.MARSHAL);
    }

    /** Loads the frozen baseline embedded in the jar. Always available, offline. */
    public static Whitelist loadEmbeddedBaseline() {
        try (InputStream in = WhitelistLoader.class.getClassLoader().getResourceAsStream(EMBEDDED_RESOURCE)) {
            if (in == null) {
                log.warn("Embedded whitelist baseline '{}' not found on classpath", EMBEDDED_RESOURCE);
                return Whitelist.empty(WhitelistSource.MARSHAL);
            }
            return parse(new String(in.readAllBytes()), WhitelistSource.MARSHAL);
        }
        catch (IOException e) {
            throw new WhitelistException("Could not read embedded whitelist baseline: " + e.getMessage(), e);
        }
    }

    private static Whitelist parse(String yaml, WhitelistSource source) {
        WhitelistDocument doc;
        try {
            doc = YAML.readValue(yaml, WhitelistDocument.class);
        }
        catch (IOException e) {
            throw new WhitelistException("Malformed whitelist YAML: " + e.getMessage(), e);
        }
        if (doc == null) {
            return Whitelist.empty(source);
        }
        if (doc.version != 1) {
            throw new WhitelistException(
                    "Unsupported whitelist version: " + doc.version + ". Only version 1 is supported.");
        }

        Instant updated = parseUpdated(doc.updated, source);
        List<WhitelistEntry> entries = new ArrayList<>();
        for (EntryDocument e : doc.entries) {
            entries.add(toEntry(e, source));
        }
        return new Whitelist(source, doc.version, updated, entries);
    }

    private static WhitelistEntry toEntry(EntryDocument e, WhitelistSource source) {
        WhitelistValidator.requireValidGav(e.gav);
        WhitelistValidator.requireMeaningfulReason(e.reason, e.gav);

        LocalDate expires = validateExpiresDateField(e, source);

        return new WhitelistEntry(
                e.gav,
                e.reason,
                (e.addedBy != null && !e.addedBy.isBlank()) ? e.addedBy : null,
                parseDateField(e.addedOn, e.gav, "added_on"),
                expires);
    }

    private static LocalDate validateExpiresDateField(EntryDocument e, WhitelistSource source) {
        LocalDate expires = parseDateField(e.expires, e.gav, "expires");
        if (source == WhitelistSource.USER && expires == null) {
            throw new WhitelistException(
                    "User whitelist entry '" + e.gav + "' is missing 'expires'. "
                            + "An expiry date is required so suppression is periodically re-reviewed.");
        }
        return expires;
    }

    private static Instant parseUpdated(String raw, WhitelistSource source) {
        if (raw == null || raw.isBlank()) {
            // The user list is never timestamp-selected, so it needs no 'updated'.
            return null;
        }
        try {
            return Instant.parse(raw);
        }
        catch (DateTimeParseException ex) {
            throw new WhitelistException("Invalid 'updated' timestamp '" + raw + "': " + ex.getMessage());
        }
    }

    private static LocalDate parseDateField(String raw, String gav, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw);
        }
        catch (DateTimeParseException ex) {
            throw new WhitelistException(
                    "Whitelist entry '" + gav + "' has an invalid '" + field + "' date '" + raw + "'.");
        }
    }

    // ── deserialisation DTOs ───────────────────────────────────────────────────────

    private static final class WhitelistDocument {
        @JsonProperty("version")
        public int version = 1;
        @JsonProperty("updated")
        public String updated;
        @JsonProperty("entries")
        public List<EntryDocument> entries = List.of();
    }

    private static final class EntryDocument {
        @JsonProperty("gav")
        public String gav;
        @JsonProperty("reason")
        public String reason;
        @JsonProperty("added_by")
        public String addedBy;
        @JsonProperty("added_on")
        public String addedOn;
        @JsonProperty("expires")
        public String expires;
    }
}
