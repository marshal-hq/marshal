package dev.marshalhq.core.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

public class MarshalConfigLoader {
    private static final Logger log = LoggerFactory.getLogger(MarshalConfigLoader.class);
    private static final String CONFIG_FILENAME = "marshal.yml";
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * Load config using standard resolution order:
     * 1. explicit path (--config flag)
     * 2. ./marshal.yml (project root)
     * 3. ~/.marshal/marshal.yml (user global)
     * 4. built-in defaults
     */
    public static MarshalConfig load(Path explicitPath) {
        // 1. Explicit path
        if (explicitPath != null) {
            return loadFile(explicitPath).orElseThrow(() ->
                new IllegalArgumentException("Config file not found: " + explicitPath));
        }

        // 2. Project root
        Path projectConfig = Paths.get(CONFIG_FILENAME);
        Optional<MarshalConfig> project = loadFile(projectConfig);
        if (project.isPresent()) {
            log.debug("Loaded config from {}", projectConfig.toAbsolutePath());
            return project.get();
        }

        // 3. User global
        Path globalConfig = Paths.get(System.getProperty("user.home"), ".marshal", CONFIG_FILENAME);
        Optional<MarshalConfig> global = loadFile(globalConfig);
        if (global.isPresent()) {
            log.debug("Loaded config from {}", globalConfig);
            return global.get();
        }

        // 4. Built-in defaults
        log.debug("No marshal.yml found — using built-in defaults");
        return new MarshalConfig();
    }

    public static MarshalConfig loadDefaults() {
        return load(null);
    }

    private static Optional<MarshalConfig> loadFile(Path path) {
        if (!Files.exists(path)) return Optional.empty();
        try {
            MarshalConfig config = YAML.readValue(path.toFile(), MarshalConfig.class);
            validateVersion(config, path);
            config = resolveEnvVars(config);
            return Optional.of(config);
        } catch (IOException e) {
            log.warn("Failed to parse config file {}: {}", path, e.getMessage());
            return Optional.empty();
        }
    }

    private static void validateVersion(MarshalConfig config, Path path) {
        if (config.getVersion() != 1) {
            throw new IllegalArgumentException(
                "Unsupported marshal.yml version: " + config.getVersion() +
                " in " + path + ". Only version 1 is supported.");
        }
    }

    /**
     * Resolve ${ENV_VAR} placeholders in string config values.
     * Currently handles slack webhook and email fields.
     */
    private static MarshalConfig resolveEnvVars(MarshalConfig config) {
        var slack = config.getNotifications().getSlack();
        if (slack.getWebhook() != null && slack.getWebhook().startsWith("${")) {
            String envVar = slack.getWebhook().replaceAll("^\\$\\{(.+)}$", "$1");
            String value = System.getenv(envVar);
            if (value != null) slack.setWebhook(value);
            else slack.setWebhook("");
        }
        return config;
    }
}
