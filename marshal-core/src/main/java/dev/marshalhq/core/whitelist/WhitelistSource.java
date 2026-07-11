package dev.marshalhq.core.whitelist;

/**
 * Which list suppressed a finding. Shows up in the JSON audit record as
 * {@code "marshal"} or {@code "user"}.
 */
public enum WhitelistSource {

    MARSHAL("marshal"),
    USER("user");

    private final String label;

    WhitelistSource(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
