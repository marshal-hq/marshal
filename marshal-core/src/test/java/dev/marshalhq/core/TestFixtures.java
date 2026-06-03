package dev.marshalhq.core;

import java.time.Instant;
import java.util.List;

public class TestFixtures {

    public static VersionMetadata metadata(String version, boolean hasSig, String email) {
        return metadata(version, hasSig ? SignatureStatus.PRESENT : SignatureStatus.ABSENT, email);
    }

    public static VersionMetadata metadata(String version, SignatureStatus sigStatus, String email) {
        return new VersionMetadata(
            new Coordinates("com.example", "test-artifact", version),
            email,
            null,
            sigStatus,
            List.of(),
            5,
            "https://github.com/example/test",
            Instant.now(),
            false
        );
    }

    public static VersionMetadata metadata(String version, boolean hasSig, String email,
                                           int depCount, String repoUrl, boolean yanked) {
        return metadata(version, hasSig ? SignatureStatus.PRESENT : SignatureStatus.ABSENT,
                        email, depCount, repoUrl, yanked);
    }

    public static VersionMetadata metadata(String version, SignatureStatus sigStatus, String email,
                                           int depCount, String repoUrl, boolean yanked) {
        return new VersionMetadata(
            new Coordinates("com.example", "test-artifact", version),
            email,
            null,
            sigStatus,
            List.of(),
            depCount,
            repoUrl,
            Instant.now(),
            yanked
        );
    }

    public static VersionMetadata withFingerprint(String version, boolean hasSig,
                                                   String email, String fingerprint) {
        return new VersionMetadata(
            new Coordinates("com.example", "test-artifact", version),
            email,
            fingerprint,
            hasSig ? SignatureStatus.PRESENT : SignatureStatus.ABSENT,
            List.of(),
            5,
            "https://github.com/example/test",
            Instant.now(),
            false
        );
    }

    public static PackageContext ctx(VersionMetadata current, VersionMetadata previous) {
        return new PackageContext(
            current.coordinates(),
            current,
            previous,
            previous != null ? List.of(previous) : List.of(),
            new TarballAnalysis(false, false, ""),
            false
        );
    }

    public static PackageContext ctx(VersionMetadata current, VersionMetadata previous,
                                     boolean highReputation) {
        return new PackageContext(
            current.coordinates(),
            current,
            previous,
            previous != null ? List.of(previous) : List.of(),
            new TarballAnalysis(false, false, ""),
            highReputation
        );
    }

    public static PackageContext ctx(VersionMetadata current, VersionMetadata previous,
                                     List<VersionMetadata> history) {
        return new PackageContext(
            current.coordinates(),
            current,
            previous,
            history,
            new TarballAnalysis(false, false, ""),
            false
        );
    }
}
