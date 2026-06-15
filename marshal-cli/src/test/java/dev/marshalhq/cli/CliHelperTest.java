package dev.marshalhq.cli;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CliHelperTest {

    @Test
    void isHighReputation_matchesExactGa() {
        Set<String> patterns = Set.of("com.google.guava:guava");
        assertThat(CliHelper.isHighReputation(patterns, "com.google.guava:guava")).isTrue();
        assertThat(CliHelper.isHighReputation(patterns, "com.google.guava:guava-testlib")).isFalse();
    }

    @Test
    void isHighReputation_matchesGroupWildcard() {
        Set<String> patterns = Set.of("org.apache.commons:*", "commons-pool:*");
        // Modern Apache Commons groupId
        assertThat(CliHelper.isHighReputation(patterns, "org.apache.commons:commons-math3")).isTrue();
        assertThat(CliHelper.isHighReputation(patterns, "org.apache.commons:commons-configuration2")).isTrue();
        // Legacy single-segment groupId
        assertThat(CliHelper.isHighReputation(patterns, "commons-pool:commons-pool")).isTrue();
    }

    @Test
    void isHighReputation_wildcardDoesNotMatchUnrelatedGroup() {
        Set<String> patterns = Set.of("org.apache.commons:*");
        assertThat(CliHelper.isHighReputation(patterns, "org.jfree:jcommon")).isFalse();
        assertThat(CliHelper.isHighReputation(patterns, "org.lz4:lz4-java")).isFalse();
    }

    @Test
    void isHighReputation_shippedListCoversApacheCommonsFamily() {
        Set<String> shipped = CliHelper.loadHighReputationGAs();
        // The false-positive cluster from real scans — all benign Apache infra key rotations.
        assertThat(CliHelper.isHighReputation(shipped, "commons-collections:commons-collections")).isTrue();
        assertThat(CliHelper.isHighReputation(shipped, "org.apache.commons:commons-math3")).isTrue();
        assertThat(CliHelper.isHighReputation(shipped, "org.apache.commons:commons-pool2")).isTrue();
        // jcommon is NOT Apache Commons — must remain non-high-reputation.
        assertThat(CliHelper.isHighReputation(shipped, "org.jfree:jcommon")).isFalse();
    }
}
