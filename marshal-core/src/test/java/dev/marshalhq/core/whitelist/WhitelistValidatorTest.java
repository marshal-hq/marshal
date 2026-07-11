package dev.marshalhq.core.whitelist;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The granularity rule: entries match on the full GAV coordinate, pinned to a version.
 * Wildcards and version-less coordinates get rejected at load time, on both lists.
 */
class WhitelistValidatorTest {

    @Test
    void acceptsAFullyPinnedGav() {
        assertThatCode(() -> WhitelistValidator.requireValidGav("com.acme:internal-lib:1.2.0"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsGroupIdWildcard() {
        assertThatThrownBy(() -> WhitelistValidator.requireValidGav("com.acme:*"))
                .isInstanceOf(WhitelistException.class)
                .hasMessageContaining("com.acme:*");
    }

    @Test
    void rejectsWildcardAnywhereInTheCoordinate() {
        assertThatThrownBy(() -> WhitelistValidator.requireValidGav("com.acme:*:1.0.0"))
                .isInstanceOf(WhitelistException.class);
        assertThatThrownBy(() -> WhitelistValidator.requireValidGav("com.acme:lib:*"))
                .isInstanceOf(WhitelistException.class);
    }

    @Test
    void rejectsVersionlessCoordinate() {
        // groupId:artifactId with no version = "all versions" = forbidden.
        assertThatThrownBy(() -> WhitelistValidator.requireValidGav("com.acme:internal-lib"))
                .isInstanceOf(WhitelistException.class)
                .hasMessageContaining("version");
    }

    @Test
    void rejectsEmptyVersionSegment() {
        assertThatThrownBy(() -> WhitelistValidator.requireValidGav("com.acme:internal-lib:"))
                .isInstanceOf(WhitelistException.class);
    }

    @Test
    void rejectsEmptyGroupOrArtifactSegment() {
        assertThatThrownBy(() -> WhitelistValidator.requireValidGav(":internal-lib:1.0.0"))
                .isInstanceOf(WhitelistException.class);
        assertThatThrownBy(() -> WhitelistValidator.requireValidGav("com.acme::1.0.0"))
                .isInstanceOf(WhitelistException.class);
    }

    @Test
    void rejectsTooManySegments() {
        assertThatThrownBy(() -> WhitelistValidator.requireValidGav("com.acme:lib:1.0.0:extra"))
                .isInstanceOf(WhitelistException.class);
    }

    @Test
    void rejectsNullOrBlank() {
        assertThatThrownBy(() -> WhitelistValidator.requireValidGav(null))
                .isInstanceOf(WhitelistException.class);
        assertThatThrownBy(() -> WhitelistValidator.requireValidGav("   "))
                .isInstanceOf(WhitelistException.class);
    }

    @Test
    void placeholderReasonsAreRejected() {
        assertThatCode(() -> WhitelistValidator.requireMeaningfulReason(
                "Internal artifact, vetted by platform", "g:a:1")).doesNotThrowAnyException();
        for (String bad : new String[] {"", "   ", null, "todo", "TBD", "xxx", "n/a"}) {
            assertThatThrownBy(() -> WhitelistValidator.requireMeaningfulReason(bad, "g:a:1"))
                    .isInstanceOf(WhitelistException.class);
        }
    }
}
