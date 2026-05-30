package dev.marshalhq.resolvers;

import dev.marshalhq.core.Coordinates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for PomDependencyResolver.
 * These tests make real network calls to Maven Central.
 * Run with: ./gradlew :marshal-resolvers:integrationTest
 * These do NOT run on every push — nightly CI only.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class PomDependencyResolverIntegrationTest {

    private PomDependencyResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new PomDependencyResolver();
    }

    @Test
    void resolvesSimplePomWithExplicitVersions() {
        Path pom = fixture("simple-pom.xml");
        List<Coordinates> deps = resolver.resolve(pom);

        assertThat(deps).isNotEmpty();
        assertThat(deps).anyMatch(c -> c.groupId().equals("org.apache.commons")
            && c.artifactId().equals("commons-lang3")
            && c.version().equals("3.13.0"));
        assertThat(deps).anyMatch(c -> c.groupId().equals("com.fasterxml.jackson.core")
            && c.artifactId().equals("jackson-databind"));
    }

    @Test
    void resolvesVersionPropertiesCorrectly() {
        Path pom = fixture("version-properties-pom.xml");
        List<Coordinates> deps = resolver.resolve(pom);

        assertThat(deps).isNotEmpty();
        // Version properties must be resolved — no literal ${...} in results
        deps.forEach(c ->
            assertThat(c.version())
                .as("Version should not contain unresolved property for %s", c.toGa())
                .doesNotStartWith("${")
        );
        assertThat(deps).anyMatch(c ->
            c.groupId().equals("org.apache.commons") && c.version().equals("3.13.0"));
    }

    @Test
    void filtersCompileAndRuntimeScopesByDefault() {
        Path pom = fixture("scopes-pom.xml");
        List<Coordinates> deps = resolver.resolve(pom);

        // compile scope must be included
        assertThat(deps).anyMatch(c ->
            c.groupId().equals("org.apache.commons") &&
            c.artifactId().equals("commons-lang3"));

        // test scope must be excluded by default
        assertThat(deps).noneMatch(c ->
            c.groupId().equals("org.junit.jupiter"));

        // provided scope must be excluded by default
        assertThat(deps).noneMatch(c ->
            c.groupId().equals("javax.servlet"));
    }

    @Test
    void returnsEmptyListForPomWithNoDependencies() {
        Path pom = fixture("no-deps-pom.xml");
        List<Coordinates> deps = resolver.resolve(pom);

        assertThat(deps).isEmpty();
    }

    @Test
    void doesNotCrashOnMalformedPom() {
        Path pom = fixture("malformed-pom.xml");

        assertThatNoException().isThrownBy(() -> {
            List<Coordinates> deps = resolver.resolve(pom);
            assertThat(deps).isEmpty();
        });
    }

    @Test
    void handlesUnknownGroupIdGracefully() {
        Path pom = fixture("unknown-groupid-pom.xml");
        List<Coordinates> deps = resolver.resolve(pom);

        // Known dep must still be resolved
        assertThat(deps).anyMatch(c ->
            c.groupId().equals("org.apache.commons") &&
            c.artifactId().equals("commons-lang3"));

        // Unknown dep: either skipped or included with original coords — must not crash
        // If included, version must not be null or empty
        deps.stream()
            .filter(c -> c.groupId().equals("com.company.internal"))
            .forEach(c -> assertThat(c.version()).isNotBlank());
    }

    @Test
    void allResolvedCoordinatesHaveNonBlankFields() {
        Path pom = fixture("simple-pom.xml");
        List<Coordinates> deps = resolver.resolve(pom);

        deps.forEach(c -> {
            assertThat(c.groupId()).as("groupId must not be blank").isNotBlank();
            assertThat(c.artifactId()).as("artifactId must not be blank").isNotBlank();
            assertThat(c.version()).as("version must not be blank for %s", c.toGa()).isNotBlank();
        });
    }

    private Path fixture(String filename) {
        try {
            return Paths.get(getClass().getClassLoader()
                .getResource("fixtures/" + filename).toURI());
        } catch (URISyntaxException e) {
            throw new RuntimeException("Fixture not found: " + filename, e);
        }
    }
}
