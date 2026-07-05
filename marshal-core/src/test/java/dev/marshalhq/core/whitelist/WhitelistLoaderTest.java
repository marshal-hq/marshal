package dev.marshalhq.core.whitelist;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WhitelistLoaderTest {

    // ── user whitelist ────────────────────────────────────────────────────────────

    @Test
    void loadsAValidUserWhitelist(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("marshal-whitelist.yml");
        Files.writeString(file, """
                version: 1
                entries:
                  - gav: "com.acme:internal-billing-lib:4.2.1"
                    reason: "Internal artifact, private registry, vetted by platform team"
                    added_by: "usman"
                    added_on: "2026-06-22"
                    expires: "2026-12-22"
                """);

        Whitelist wl = WhitelistLoader.loadUser(file);

        assertThat(wl.source()).isEqualTo(WhitelistSource.USER);
        WhitelistEntry e = wl.find("com.acme:internal-billing-lib:4.2.1", LocalDate.parse("2026-06-22")).orElseThrow();
        assertThat(e.reason()).contains("vetted by platform team");
        assertThat(e.addedBy()).isEqualTo("usman");
        assertThat(e.addedOn()).isEqualTo(LocalDate.parse("2026-06-22"));
        assertThat(e.expires()).isEqualTo(LocalDate.parse("2026-12-22"));
    }

    @Test
    void absentUserFileIsAnEmptyWhitelistNotAnError(@TempDir Path dir) {
        Whitelist wl = WhitelistLoader.loadUser(dir.resolve("does-not-exist.yml"));
        assertThat(wl.entries()).isEmpty();
        assertThat(wl.source()).isEqualTo(WhitelistSource.USER);
    }

    @Test
    void userEntryRejectsWildcard(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("marshal-whitelist.yml");
        Files.writeString(file, """
                version: 1
                entries:
                  - gav: "com.acme:*"
                    reason: "trust the whole group"
                    expires: "2026-12-22"
                """);

        assertThatThrownBy(() -> WhitelistLoader.loadUser(file))
                .isInstanceOf(WhitelistException.class)
                .hasMessageContaining("com.acme:*");
    }

    @Test
    void userEntryRejectsVersionlessCoordinate(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("marshal-whitelist.yml");
        Files.writeString(file, """
                version: 1
                entries:
                  - gav: "com.acme:internal-lib"
                    reason: "trust all versions"
                    expires: "2026-12-22"
                """);

        assertThatThrownBy(() -> WhitelistLoader.loadUser(file))
                .isInstanceOf(WhitelistException.class);
    }

    @Test
    void userEntryRequiresReason(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("marshal-whitelist.yml");
        Files.writeString(file, """
                version: 1
                entries:
                  - gav: "com.acme:lib:1.0.0"
                    expires: "2026-12-22"
                """);

        assertThatThrownBy(() -> WhitelistLoader.loadUser(file))
                .isInstanceOf(WhitelistException.class)
                .hasMessageContaining("reason");
    }

    @Test
    void userEntryRejectsPlaceholderReason(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("marshal-whitelist.yml");
        Files.writeString(file, """
                version: 1
                entries:
                  - gav: "com.acme:lib:1.0.0"
                    reason: "TODO"
                    expires: "2026-12-22"
                """);

        assertThatThrownBy(() -> WhitelistLoader.loadUser(file))
                .isInstanceOf(WhitelistException.class)
                .hasMessageContaining("reason");
    }

    @Test
    void userEntryRequiresExpires(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("marshal-whitelist.yml");
        Files.writeString(file, """
                version: 1
                entries:
                  - gav: "com.acme:lib:1.0.0"
                    reason: "Internal artifact, vetted"
                """);

        assertThatThrownBy(() -> WhitelistLoader.loadUser(file))
                .isInstanceOf(WhitelistException.class)
                .hasMessageContaining("expires");
    }

    @Test
    void rejectsUnsupportedVersion(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("marshal-whitelist.yml");
        Files.writeString(file, """
                version: 99
                entries: []
                """);

        assertThatThrownBy(() -> WhitelistLoader.loadUser(file))
                .isInstanceOf(WhitelistException.class)
                .hasMessageContaining("version");
    }

    // ── marshal-maintained whitelist (embedded baseline) ──────────────────────────

    @Test
    void parsesMarshalBaselineWithUpdatedTimestampAndNoExpiry() {
        String yaml = """
                version: 1
                updated: "2026-06-22T00:00:00Z"
                entries:
                  - gav: "org.springframework:spring-core:6.1.5"
                    reason: "Top-200 Maven Central package, verified maintainer + GPG history"
                """;

        Whitelist wl = WhitelistLoader.parseMarshal(yaml);

        assertThat(wl.source()).isEqualTo(WhitelistSource.MARSHAL);
        assertThat(wl.updated()).isEqualTo(Instant.parse("2026-06-22T00:00:00Z"));
        WhitelistEntry e = wl.find("org.springframework:spring-core:6.1.5", LocalDate.parse("2099-01-01")).orElseThrow();
        assertThat(e.expires()).isNull();
        assertThat(e.reason()).contains("Top-200");
    }

    @Test
    void marshalBaselineAlsoRejectsWildcards() {
        String yaml = """
                version: 1
                updated: "2026-06-22T00:00:00Z"
                entries:
                  - gav: "org.springframework:*"
                    reason: "all of spring"
                """;

        assertThatThrownBy(() -> WhitelistLoader.parseMarshal(yaml))
                .isInstanceOf(WhitelistException.class);
    }

    @Test
    void embeddedBaselineShipsInTheJarAndParses() {
        // The frozen baseline must always be loadable from the classpath, offline.
        Whitelist wl = WhitelistLoader.loadEmbeddedBaseline();
        assertThat(wl.source()).isEqualTo(WhitelistSource.MARSHAL);
        assertThat(wl.updated()).isNotNull();
    }
}
