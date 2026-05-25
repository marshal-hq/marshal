package dev.marshalhq.core;

import java.util.List;

public record PackageContext(
    Coordinates coordinates,
    VersionMetadata current,
    VersionMetadata previous,
    List<VersionMetadata> history,
    TarballAnalysis tarball,
    boolean isHighReputation
) {}
