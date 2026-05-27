package dev.marshalhq.core.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

class MarshalConfigLoaderTest {

    @Test
    void defaultsApplyWhenNoFileExists() {
        MarshalConfig config = MarshalConfigLoader.loadDefaults();
        assertThat(config.getVersion()).isEqualTo(1);
        assertThat(config.getScan().getScopes()).containsExactly("compile", "runtime");
        assertThat(config.getScan().isIncludeTransitive()).isTrue();
        assertThat(config.getScan().getDepth()).isEqualTo(-1);
        assertThat(config.getThresholds().getFailOn()).isEqualTo("red");
        assertThat(config.getThresholds().getWarnOn()).isEqualTo("orange");
        assertThat(config.getAllowlist().getPackages()).isEmpty();
        assertThat(config.getRules().getDisabled()).isEmpty();
    }

    @Test
    void loadsProjectConfigFromExplicitPath(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("marshal.yml");
        Files.writeString(configFile, """
            version: 1
            scan:
              scopes:
                - compile
                - runtime
                - test
            thresholds:
              fail-on: orange
              warn-on: yellow
            """);

        MarshalConfig config = MarshalConfigLoader.load(configFile);

        assertThat(config.getScan().getScopes()).containsExactly("compile", "runtime", "test");
        assertThat(config.getThresholds().getFailOn()).isEqualTo("orange");
        assertThat(config.getThresholds().getWarnOn()).isEqualTo("yellow");
    }

    @Test
    void allowlistWildcardMatchesGroupPrefix(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("marshal.yml");
        Files.writeString(configFile, """
            version: 1
            allowlist:
              packages:
                - "org.springframework:*"
                - "com.fasterxml.jackson:jackson-databind"
            """);

        MarshalConfig config = MarshalConfigLoader.load(configFile);

        assertThat(config.getAllowlist().isAllowed("org.springframework:spring-core")).isTrue();
        assertThat(config.getAllowlist().isAllowed("org.springframework:spring-boot")).isTrue();
        assertThat(config.getAllowlist().isAllowed("com.fasterxml.jackson:jackson-databind")).isTrue();
        assertThat(config.getAllowlist().isAllowed("com.fasterxml.jackson:jackson-core")).isFalse();
        assertThat(config.getAllowlist().isAllowed("org.apache.commons:commons-lang3")).isFalse();
    }

    @Test
    void disabledRulesAreParsed(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("marshal.yml");
        Files.writeString(configFile, """
            version: 1
            rules:
              disabled:
                - TYPOSQUAT_PROXIMITY
                - MISSING_SIGNATURE
            """);

        MarshalConfig config = MarshalConfigLoader.load(configFile);
        assertThat(config.getRules().getDisabled())
            .containsExactly("TYPOSQUAT_PROXIMITY", "MISSING_SIGNATURE");
    }

    @Test
    void ruleWeightOverrideIsParsed(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("marshal.yml");
        Files.writeString(configFile, """
            version: 1
            rules:
              overrides:
                INSTALL_SCRIPT_ADDED:
                  weight: 60
            """);

        MarshalConfig config = MarshalConfigLoader.load(configFile);
        assertThat(config.getRules().getOverrides()).containsKey("INSTALL_SCRIPT_ADDED");
        assertThat(config.getRules().getOverrides().get("INSTALL_SCRIPT_ADDED").getWeight()).isEqualTo(60);
    }

    @Test
    void unsupportedVersionThrows(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("marshal.yml");
        Files.writeString(configFile, "version: 99\n");

        assertThatThrownBy(() -> MarshalConfigLoader.load(configFile))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported marshal.yml version");
    }

    @Test
    void unknownFieldsAreIgnored(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("marshal.yml");
        Files.writeString(configFile, """
            version: 1
            future-feature:
              something: true
            """);

        assertThatNoException().isThrownBy(() -> MarshalConfigLoader.load(configFile));
    }

    @Test
    void partialConfigMergesWithDefaults(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("marshal.yml");
        Files.writeString(configFile, """
            version: 1
            thresholds:
              fail-on: orange
            """);

        MarshalConfig config = MarshalConfigLoader.load(configFile);

        // Explicitly set
        assertThat(config.getThresholds().getFailOn()).isEqualTo("orange");
        // Defaults preserved
        assertThat(config.getScan().getScopes()).containsExactly("compile", "runtime");
        assertThat(config.getThresholds().getWarnOn()).isEqualTo("orange");
    }

    @Test
    void envVarInSlackWebhookIsResolved(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("marshal.yml");
        Files.writeString(configFile, """
            version: 1
            notifications:
              slack:
                webhook: ${MARSHAL_SLACK_WEBHOOK}
                min-level: red
            """);

        // Env var not set — should resolve to empty string, not crash
        MarshalConfig config = MarshalConfigLoader.load(configFile);
        assertThat(config.getNotifications().getSlack().getWebhook()).isEmpty();
    }
}
