package dev.marshalhq.resolvers;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Shared helpers for the Gradle integration tests: copy a committed fixture project to
 * a writable temp dir, materialize the bundled init script, and parse the marshalDeps
 * JSON into {@code group:name:version} strings.
 */
final class GradleFixtures {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GradleFixtures() {
    }

    /** Copies {@code fixtures/<id>} from the integration-test resources into {@code dest}. */
    static Path copyFixture(String id, Path dest) {
        Path src = resourceDir("fixtures/" + id);
        try (Stream<Path> tree = Files.walk(src)) {
            tree.forEach(p -> {
                Path target = dest.resolve(src.relativize(p).toString());
                try {
                    if (Files.isDirectory(p)) {
                        Files.createDirectories(target);
                    } else {
                        Files.createDirectories(target.getParent());
                        Files.copy(p, target);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return dest;
    }

    /** Writes the production init script ({@code /marshal-init.gradle}) to a temp file. */
    static Path initScript(Path dir) {
        Path out = dir.resolve("marshal-init.gradle");
        try (InputStream in = GradleFixtures.class.getResourceAsStream("/marshal-init.gradle")) {
            Files.write(out, Objects.requireNonNull(in, "init script on classpath").readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    /** Parses the marshalDeps output into unique {@code group:name:version} strings. */
    static Set<String> gavs(Path json) {
        Set<String> out = new LinkedHashSet<>();
        try {
            JsonNode root = MAPPER.readTree(json.toFile());
            for (JsonNode n : root.path("modules")) {
                out.add(n.get("group").asText() + ":" + n.get("name").asText()
                        + ":" + n.get("version").asText());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    private static Path resourceDir(String name) {
        try {
            return Path.of(Objects.requireNonNull(
                    GradleFixtures.class.getClassLoader().getResource(name),
                    "missing test resource: " + name).toURI());
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }
}
