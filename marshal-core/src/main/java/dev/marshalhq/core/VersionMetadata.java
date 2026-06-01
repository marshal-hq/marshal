package dev.marshalhq.core;

import java.time.Instant;
import java.util.List;

public record VersionMetadata(
    Coordinates coordinates,
    String publisherEmail,
    String gpgKeyFingerprint,
    SignatureStatus signatureStatus,
    List<String> installScripts,
    int dependencyCount,   // -1 means POM fetch failed; rules must abstain rather than treat as 0
    String repoUrl,        // null when POM fetch failed (depCount == -1) or POM has no <scm>
    Instant publishedAt,
    boolean isYanked
) {}
