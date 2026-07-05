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
    void legacyAllowlistBlockIsIgnoredNotAnError(@TempDir Path tempDir) throws IOException {
        // The allowlist landmine (groupId wildcards) is removed from marshal.yml — the
        // whitelist now lives in marshal-whitelist.yml. An old config that still carries
        // an allowlist block must load without error (unknown keys are ignored).
        Path configFile = tempDir.resolve("marshal.yml");
        Files.writeString(configFile, """
            version: 1
            allowlist:
              packages:
                - "org.springframework:*"
            """);

        assertThatNoException().isThrownBy(() -> MarshalConfigLoader.load(configFile));
    }

    @Test
    void disabledRulesAreParsed(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("marshal.yml");
        Files.writeString(configFile, """
            version: 1
            rules:
              disabled:
                - MAJOR-JUMP
                - MISSING-SIG
            """);

        MarshalConfig config = MarshalConfigLoader.load(configFile);
        assertThat(config.getRules().getDisabled())
            .containsExactly("MAJOR-JUMP", "MISSING-SIG");
    }

    @Test
    void ruleWeightOverrideIsParsed(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("marshal.yml");
        Files.writeString(configFile, """
            version: 1
            rules:
              overrides:
                DEP-EXPLOSION:
                  weight: 60
            """);

        MarshalConfig config = MarshalConfigLoader.load(configFile);
        assertThat(config.getRules().getOverrides()).containsKey("DEP-EXPLOSION");
        assertThat(config.getRules().getOverrides().get("DEP-EXPLOSION").getWeight()).isEqualTo(60);
    }

    @Test
    void unknownDisabledRuleIdThrows(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("marshal.yml");
        Files.writeString(configFile, """
            version: 1
            rules:
              disabled:
                - NOT_A_RULE
            """);

        assertThatThrownBy(() -> MarshalConfigLoader.load(configFile))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("NOT_A_RULE")
            .hasMessageContaining("rules.disabled");
    }

    @Test
    void unknownOverrideRuleIdThrows(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("marshal.yml");
        Files.writeString(configFile, """
            version: 1
            rules:
              overrides:
                MISSING_SIG:
                  weight: 40
            """);

        assertThatThrownBy(() -> MarshalConfigLoader.load(configFile))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("MISSING_SIG")
            .hasMessageContaining("rules.overrides");
    }

    @Test
    void negativeOverrideWeightThrows(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("marshal.yml");
        Files.writeString(configFile, """
            version: 1
            rules:
              overrides:
                DEP-EXPLOSION:
                  weight: -5
            """);

        assertThatThrownBy(() -> MarshalConfigLoader.load(configFile))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("DEP-EXPLOSION");
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
