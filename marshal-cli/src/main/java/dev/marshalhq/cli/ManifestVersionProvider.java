package dev.marshalhq.cli;

import picocli.CommandLine.IVersionProvider;

/**
 * Resolves the CLI version from the jar manifest's Implementation-Version,
 * which is stamped from the Gradle project version at build time. When running
 * outside a packaged jar (IDE, tests) the manifest is absent, so we fall back to
 * a dev marker rather than a misleading hardcoded release number.
 */
public class ManifestVersionProvider implements IVersionProvider {

    static String version() {
        String v = MarshalCli.class.getPackage().getImplementationVersion();
        return (v == null || v.isBlank()) ? "0.0.0-dev" : v;
    }

    @Override
    public String[] getVersion() {
        return new String[] { "marshal " + version() };
    }
}
