package dev.marshalhq.cli;

import dev.marshalhq.core.config.MarshalConfig;
import dev.marshalhq.resolvers.GradleDependencyResolver;
import dev.marshalhq.resolvers.PomDependencyResolver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ResolverRouterTest {

    @TempDir
    Path tempDir;

    private final MarshalConfig config = new MarshalConfig();

    private PrintWriter err() {
        return new PrintWriter(new StringWriter(), true);
    }

    @Test
    void pomFile_routesToMaven() {
        Path pom = tempDir.resolve("pom.xml");
        ResolverRouter.Routed r = ResolverRouter.forPath(pom, config, false, err());
        assertThat(r.resolver()).isInstanceOf(PomDependencyResolver.class);
        assertThat(r.target()).isEqualTo(pom);
    }

    @Test
    void gradleBuildFile_routesToGradle() {
        Path build = tempDir.resolve("build.gradle.kts");
        ResolverRouter.Routed r = ResolverRouter.forPath(build, config, false, err());
        assertThat(r.resolver()).isInstanceOf(GradleDependencyResolver.class);
        assertThat(r.target()).isEqualTo(build);
    }

    @Test
    void directoryWithPom_autoDetectsMaven() throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");
        ResolverRouter.Routed r = ResolverRouter.forPath(tempDir, config, false, err());
        assertThat(r.resolver()).isInstanceOf(PomDependencyResolver.class);
        assertThat(r.target()).isEqualTo(tempDir.resolve("pom.xml"));
    }

    @Test
    void directoryWithSettingsGradle_autoDetectsGradle() throws Exception {
        Files.writeString(tempDir.resolve("settings.gradle.kts"), "");
        ResolverRouter.Routed r = ResolverRouter.forPath(tempDir, config, false, err());
        assertThat(r.resolver()).isInstanceOf(GradleDependencyResolver.class);
        assertThat(r.target()).isEqualTo(tempDir);
    }

    @Test
    void emptyDirectory_returnsNull() {
        ResolverRouter.Routed r = ResolverRouter.forPath(tempDir, config, false, err());
        assertThat(r).isNull();
    }

    @Test
    void directoryWithBothBuilds_prefersMaven() throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");
        Files.writeString(tempDir.resolve("build.gradle.kts"), "");
        ResolverRouter.Routed r = ResolverRouter.forPath(tempDir, config, false, err());
        assertThat(r.resolver()).isInstanceOf(PomDependencyResolver.class);
        assertThat(r.target()).isEqualTo(tempDir.resolve("pom.xml"));
    }

    // §2.5: mixed-tool diff is allowed — each side detected independently.
    @Test
    void mixedTools_eachSideDetectedIndependently() throws Exception {
        Path baseDir = Files.createDirectory(tempDir.resolve("base"));
        Path headDir = Files.createDirectory(tempDir.resolve("head"));
        Files.writeString(baseDir.resolve("pom.xml"), "<project/>");
        Files.writeString(headDir.resolve("settings.gradle.kts"), "");

        ResolverRouter.Routed base = ResolverRouter.forPath(baseDir, config, false, err());
        ResolverRouter.Routed head = ResolverRouter.forPath(headDir, config, false, err());

        assertThat(base.resolver()).isInstanceOf(PomDependencyResolver.class);
        assertThat(head.resolver()).isInstanceOf(GradleDependencyResolver.class);
    }
}
