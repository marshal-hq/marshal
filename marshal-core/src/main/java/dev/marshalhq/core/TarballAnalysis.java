package dev.marshalhq.core;

public record TarballAnalysis(
    boolean hasObfuscation,
    boolean hasNetworkCallsInInstall,
    String obfuscationEvidence
) {}
