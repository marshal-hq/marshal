package dev.marshalhq.resolvers;

import dev.marshalhq.core.Coordinates;
import org.junit.jupiter.api.BeforeEach;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class PomDependencyResolverTest {

    private PomDependencyResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new PomDependencyResolver();
    }

    @Test
    void resolvesExplicitVersionCorrectly(@TempDir Path tempDir) throws IOException {
        Path pom = writePom(tempDir, """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0"
                     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                     xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>test-app</artifactId>
                <version>1.0.0</version>
                <dependencies>
                    <dependency>
                        <groupId>org.apache.commons</groupId>
                        <artifactId>commons-lang3</artifactId>
                        <version>3.13.0</version>
                    </dependency>
                </dependencies>
            </project>
            """);

        List<Coordinates> deps = resolver.resolve(pom);

        assertThat(deps).hasSize(1);
        assertThat(deps.get(0).groupId()).isEqualTo("org.apache.commons");
        assertThat(deps.get(0).artifactId()).isEqualTo("commons-lang3");
        assertThat(deps.get(0).version()).isEqualTo("3.13.0");
    }

    @Test
    void resolvesPropertyVersionCorrectly(@TempDir Path tempDir) throws IOException {
        Path pom = writePom(tempDir, """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0"
                     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                     xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>test-app</artifactId>
                <version>1.0.0</version>
                <properties>
                    <commons.version>3.13.0</commons.version>
                </properties>
                <dependencies>
                    <dependency>
                        <groupId>org.apache.commons</groupId>
                        <artifactId>commons-lang3</artifactId>
                        <version>${commons.version}</version>
                    </dependency>
                </dependencies>
            </project>
            """);

        List<Coordinates> deps = resolver.resolve(pom);

        assertThat(deps).hasSize(1);
        assertThat(deps.get(0).version()).isEqualTo("3.13.0");
        assertThat(deps.get(0).version()).doesNotStartWith("${");
    }

    @Test
    void marksUnresolvablePropertyAsUNRESOLVED(@TempDir Path tempDir) throws IOException {
        Path pom = writePom(tempDir, """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0"
                     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                     xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>test-app</artifactId>
                <version>1.0.0</version>
                <dependencies>
                    <dependency>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-web</artifactId>
                        <version>${spring.boot.version}</version>
                    </dependency>
                </dependencies>
            </project>
            """);

        List<Coordinates> deps = resolver.resolve(pom);

        // Dependency must NOT be silently dropped
        assertThat(deps).hasSize(1);
        assertThat(deps.get(0).groupId()).isEqualTo("org.springframework.boot");
        assertThat(deps.get(0).artifactId()).isEqualTo("spring-boot-starter-web");
        assertThat(deps.get(0).version()).isEqualTo("UNRESOLVED");
    }

    @Test
    void marksNullVersionAsUNRESOLVED(@TempDir Path tempDir) throws IOException {
        // BOM-managed dependency with no explicit version
        Path pom = writePom(tempDir, """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0"
                     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                     xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>test-app</artifactId>
                <version>1.0.0</version>
                <dependencies>
                    <dependency>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-web</artifactId>
                    </dependency>
                </dependencies>
            </project>
            """);

        List<Coordinates> deps = resolver.resolve(pom);

        // BOM-managed dep with no version must NOT be dropped
        assertThat(deps).hasSize(1);
        assertThat(deps.get(0).version()).isEqualTo("UNRESOLVED");
    }

    @Test
    void excludesTestScopedDependencies(@TempDir Path tempDir) throws IOException {
        Path pom = writePom(tempDir, """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0"
                     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                     xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>test-app</artifactId>
                <version>1.0.0</version>
                <dependencies>
                    <dependency>
                        <groupId>org.apache.commons</groupId>
                        <artifactId>commons-lang3</artifactId>
                        <version>3.13.0</version>
                        <scope>compile</scope>
                    </dependency>
                    <dependency>
                        <groupId>org.junit.jupiter</groupId>
                        <artifactId>junit-jupiter</artifactId>
                        <version>5.10.2</version>
                        <scope>test</scope>
                    </dependency>
                </dependencies>
            </project>
            """);

        List<Coordinates> deps = resolver.resolve(pom);

        assertThat(deps).hasSize(1);
        assertThat(deps.get(0).artifactId()).isEqualTo("commons-lang3");
        assertThat(deps).noneMatch(c -> c.groupId().equals("org.junit.jupiter"));
    }

    @Test
    void excludesProvidedScopedDependencies(@TempDir Path tempDir) throws IOException {
        Path pom = writePom(tempDir, """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0"
                     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                     xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>test-app</artifactId>
                <version>1.0.0</version>
                <dependencies>
                    <dependency>
                        <groupId>org.apache.commons</groupId>
                        <artifactId>commons-lang3</artifactId>
                        <version>3.13.0</version>
                    </dependency>
                    <dependency>
                        <groupId>javax.servlet</groupId>
                        <artifactId>javax.servlet-api</artifactId>
                        <version>4.0.1</version>
                        <scope>provided</scope>
                    </dependency>
                </dependencies>
            </project>
            """);

        List<Coordinates> deps = resolver.resolve(pom);

        assertThat(deps).hasSize(1);
        assertThat(deps).noneMatch(c -> c.groupId().equals("javax.servlet"));
    }

    @Test
    void returnsEmptyListForPomWithNoDependencies(@TempDir Path tempDir) throws IOException {
        Path pom = writePom(tempDir, """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0"
                     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                     xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>empty-app</artifactId>
                <version>1.0.0</version>
            </project>
            """);

        List<Coordinates> deps = resolver.resolve(pom);
        assertThat(deps).isEmpty();
    }

    @Test
    void honorsCustomScopesFromConstructor(@TempDir Path tempDir) throws IOException {
        // When caller adds TEST to the included-scopes set, test-scoped deps must appear.
        resolver = new PomDependencyResolver(
                EnumSet.of(DependencyScope.COMPILE, DependencyScope.RUNTIME, DependencyScope.TEST));
        Path pom = writePom(tempDir, """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0"
                     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                     xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>test-app</artifactId>
                <version>1.0.0</version>
                <dependencies>
                    <dependency>
                        <groupId>org.apache.commons</groupId>
                        <artifactId>commons-lang3</artifactId>
                        <version>3.13.0</version>
                    </dependency>
                    <dependency>
                        <groupId>org.junit.jupiter</groupId>
                        <artifactId>junit-jupiter</artifactId>
                        <version>5.10.2</version>
                        <scope>test</scope>
                    </dependency>
                </dependencies>
            </project>
            """);

        List<Coordinates> deps = resolver.resolve(pom);

        assertThat(deps).hasSize(2);
        assertThat(deps).anyMatch(c -> c.artifactId().equals("commons-lang3"));
        assertThat(deps).anyMatch(c -> c.artifactId().equals("junit-jupiter"));
    }

    @Test
    void doesNotCrashOnMalformedPom(@TempDir Path tempDir) throws IOException {
        Path pom = writePom(tempDir, """
            <?xml version="1.0" encoding="UTF-8"?>
            <project>
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example
            """);

        assertThatNoException().isThrownBy(() -> {
            List<Coordinates> deps = resolver.resolve(pom);
            assertThat(deps).isEmpty();
        });
    }

    @Test
    void unresolvedDependenciesAreNotSilentlyDropped(@TempDir Path tempDir) throws IOException {
        // Mixed POM: one resolvable, one BOM-managed (no version), one unresolvable property
        Path pom = writePom(tempDir, """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0"
                     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                     xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>mixed-app</artifactId>
                <version>1.0.0</version>
                <dependencies>
                    <dependency>
                        <groupId>org.apache.commons</groupId>
                        <artifactId>commons-lang3</artifactId>
                        <version>3.13.0</version>
                    </dependency>
                    <dependency>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-web</artifactId>
                        <version>${spring.boot.version}</version>
                    </dependency>
                    <dependency>
                        <groupId>com.fasterxml.jackson.core</groupId>
                        <artifactId>jackson-databind</artifactId>
                    </dependency>
                </dependencies>
            </project>
            """);

        List<Coordinates> deps = resolver.resolve(pom);

        // All 3 must be present — none silently dropped
        assertThat(deps).hasSize(3);

        // Explicit version resolved correctly
        assertThat(deps).anyMatch(c ->
            c.artifactId().equals("commons-lang3") && c.version().equals("3.13.0"));

        // Unresolvable property marked UNRESOLVED
        assertThat(deps).anyMatch(c ->
            c.artifactId().equals("spring-boot-starter-web") && c.version().equals("UNRESOLVED"));

        // Null version (BOM-managed) marked UNRESOLVED
        assertThat(deps).anyMatch(c ->
            c.artifactId().equals("jackson-databind") && c.version().equals("UNRESOLVED"));
    }

    private Path writePom(Path dir, String content) throws IOException {
        Path pom = dir.resolve("pom.xml");
        Files.writeString(pom, content);
        return pom;
    }
}
