package dev.marshalhq.core;

import java.time.Instant;
import java.util.List;

public record VersionMetadata(
    Coordinates coordinates,
    String publisherEmail,
    String gpgKeyFingerprint,
    boolean hasGpgSignature,
    List<String> installScripts,
    int dependencyCount,
    String repoUrl,
    Instant publishedAt,
    boolean isYanked
) {}
