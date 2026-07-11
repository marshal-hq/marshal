package dev.marshalhq.core.config;

import dev.marshalhq.core.rules.RuleCatalog;

import java.util.Set;

/**
 * Rejects a marshal.yml that names a rule which does not exist. Same fail-closed stance as
 * the whitelist loader: a typo'd or stale rule ID is a configuration error, not a line we
 * quietly skip. A silently dropped {@code disabled} entry is the dangerous case — it would
 * leave a rule firing that the user believed they had turned off.
 */
public final class RulesConfigValidator {

    private RulesConfigValidator() {
    }

    public static void validate(RulesConfig rules) {
        Set<String> known = RuleCatalog.knownIds();
        for (String id : rules.getDisabled()) {
            requireKnown(id, known, "rules.disabled");
        }
        rules.getOverrides().forEach((id, override) -> {
            requireKnown(id, known, "rules.overrides");
            if (override.getWeight() == null || override.getWeight() < 0) {
                throw new IllegalArgumentException(
                        "rules.overrides['" + id + "'] needs a weight of zero or more.");
            }
        });
    }

    private static void requireKnown(String id, Set<String> known, String where) {
        if (id == null || !known.contains(id)) {
            throw new IllegalArgumentException(
                    "Unknown rule ID '" + id + "' in " + where + ". Valid rule IDs are " + known + ".");
        }
    }
}
