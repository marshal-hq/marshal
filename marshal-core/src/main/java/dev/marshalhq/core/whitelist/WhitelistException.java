package dev.marshalhq.core.whitelist;

/**
 * Thrown when a whitelist document fails to load or breaks the schema: a wildcard
 * entry, a version-less coordinate, a missing required field. The whitelist decides
 * what gets trusted, so a malformed entry has to fail loudly rather than get skipped.
 */
public class WhitelistException extends RuntimeException {

    public WhitelistException(String message) {
        super(message);
    }

    public WhitelistException(String message, Throwable cause) {
        super(message, cause);
    }
}
