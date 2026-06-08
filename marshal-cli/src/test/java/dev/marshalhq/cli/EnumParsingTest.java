package dev.marshalhq.cli;

import dev.marshalhq.core.*;
import dev.marshalhq.registry.MavenCentralClient;
import dev.marshalhq.resolvers.PomDependencyResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EnumParsingTest {

    @Mock MavenCentralClient mockClient;
    @Mock PomDependencyResolver mockResolver;
    @TempDir Path tempDir;

    private PrintStream origOut, origErr;
    private ByteArrayOutputStream outBytes;

    @BeforeEach
    void redirect() {
        origOut = System.out; origErr = System.err;
        outBytes = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outBytes));
        System.setErr(new PrintStream(new ByteArrayOutputStream()));
    }

    @AfterEach
    void restore() { System.setOut(origOut); System.setErr(origErr); }

    private static final Coordinates DEP =
        new Coordinates("com.example", "lib-a", "1.0.0");

    private static final VersionMetadata SIGNED_META =
        new VersionMetadata(DEP, null, "KEY1", SignatureStatus.PRESENT,
            List.of(), 5, "https://github.com/example/lib", Instant.EPOCH, false);

    // ── converter unit tests ───────────────────────────────────────────────────

    @Test
    void outputFormatConverter_acceptsAnyCase() throws Exception {
        var c = new CaseInsensitiveConverter.ForOutputFormat();
        assertThat(c.convert("json")).isEqualTo(OutputFormat.JSON);
        assertThat(c.convert("JSON")).isEqualTo(OutputFormat.JSON);
        assertThat(c.convert("Json")).isEqualTo(OutputFormat.JSON);
        assertThat(c.convert("human")).isEqualTo(OutputFormat.HUMAN);
        assertThat(c.convert("HUMAN")).isEqualTo(OutputFormat.HUMAN);
        assertThat(c.convert("md")).isEqualTo(OutputFormat.MD);
        assertThat(c.convert("MD")).isEqualTo(OutputFormat.MD);
    }

    @Test
    void failOnConverter_acceptsAnyCase() throws Exception {
        var c = new CaseInsensitiveConverter.ForFailOn();
        assertThat(c.convert("fail")).isEqualTo(FailOn.FAIL);
        assertThat(c.convert("FAIL")).isEqualTo(FailOn.FAIL);
        assertThat(c.convert("warn")).isEqualTo(FailOn.WARN);
        assertThat(c.convert("Warn")).isEqualTo(FailOn.WARN);
        assertThat(c.convert("never")).isEqualTo(FailOn.NEVER);
    }

    @Test
    void severityConverter_acceptsAnyCase() throws Exception {
        var c = new CaseInsensitiveConverter.ForSeverity();
        assertThat(c.convert("red")).isEqualTo(Severity.RED);
        assertThat(c.convert("RED")).isEqualTo(Severity.RED);
        assertThat(c.convert("orange")).isEqualTo(Severity.ORANGE);
        assertThat(c.convert("Yellow")).isEqualTo(Severity.YELLOW);
        assertThat(c.convert("green")).isEqualTo(Severity.GREEN);
    }

    @Test
    void outputFormatConverter_rejectsUnknownValue() {
        assertThatThrownBy(() -> new CaseInsensitiveConverter.ForOutputFormat().convert("xml"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ── integration: picocli wires converters end-to-end ──────────────────────

    @Test
    void scanCommand_acceptsLowercaseOutput_json() {
        stubGreenScan();
        int exit = new CommandLine(new ScanCommand(mockClient, mockResolver))
            .execute("--pom", tempDir.resolve("pom.xml").toString(),
                     "--output", "json");
        assertThat(exit).isNotEqualTo(2); // 2 = picocli parse error
        assertThat(outBytes.toString()).startsWith("{");
    }

    @Test
    void scanCommand_acceptsLowercaseThresholdAndFailOn() {
        stubGreenScan();
        int exit = new CommandLine(new ScanCommand(mockClient, mockResolver))
            .execute("--pom", tempDir.resolve("pom.xml").toString(),
                     "--output", "json", "--threshold", "orange", "--fail-on", "warn");
        assertThat(exit).isNotEqualTo(2);
    }

    @Test
    void scanCommand_acceptsMixedCaseOutput() {
        stubGreenScan();
        int exit = new CommandLine(new ScanCommand(mockClient, mockResolver))
            .execute("--pom", tempDir.resolve("pom.xml").toString(),
                     "--output", "Json");
        assertThat(exit).isNotEqualTo(2);
        assertThat(outBytes.toString()).startsWith("{");
    }

    @Test
    void diffCommand_acceptsLowercaseOutput() {
        when(mockResolver.resolve(tempDir.resolve("base.xml"))).thenReturn(List.of());
        when(mockResolver.resolve(tempDir.resolve("head.xml"))).thenReturn(List.of(DEP));
        when(mockClient.fetchMetadata(DEP)).thenReturn(SIGNED_META);

        int exit = new CommandLine(new DiffCommand(mockClient, mockResolver))
            .execute("--base", tempDir.resolve("base.xml").toString(),
                     "--head", tempDir.resolve("head.xml").toString(),
                     "--output", "json", "--threshold", "red", "--fail-on", "never");
        assertThat(exit).isNotEqualTo(2);
        assertThat(outBytes.toString()).startsWith("{");
    }

    // ── --cache-path wiring ────────────────────────────────────────────────────

    @Test
    void scanCommand_acceptsCachePath() {
        stubGreenScan();
        int exit = new CommandLine(new ScanCommand(mockClient, mockResolver))
            .execute("--pom", tempDir.resolve("pom.xml").toString(),
                     "--cache-path", tempDir.resolve("custom.db").toString());
        assertThat(exit).isNotEqualTo(2); // 2 = picocli parse error
    }

    @Test
    void diffCommand_acceptsCachePath() {
        when(mockResolver.resolve(tempDir.resolve("base.xml"))).thenReturn(List.of());
        when(mockResolver.resolve(tempDir.resolve("head.xml"))).thenReturn(List.of(DEP));
        when(mockClient.fetchMetadata(DEP)).thenReturn(SIGNED_META);

        int exit = new CommandLine(new DiffCommand(mockClient, mockResolver))
            .execute("--base", tempDir.resolve("base.xml").toString(),
                     "--head", tempDir.resolve("head.xml").toString(),
                     "--cache-path", tempDir.resolve("custom.db").toString());
        assertThat(exit).isNotEqualTo(2);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private void stubGreenScan() {
        when(mockClient.getVersionHistory("com.example", "lib-a"))
            .thenReturn(List.of("1.0.0"));
        when(mockClient.fetchMetadata(DEP)).thenReturn(SIGNED_META);
        when(mockResolver.resolve(any())).thenReturn(List.of(DEP));
    }
}
