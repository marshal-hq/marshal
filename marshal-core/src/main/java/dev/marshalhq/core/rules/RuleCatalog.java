package dev.marshalhq.core.rules;

import dev.marshalhq.core.Rule;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The rules Marshal ships with, in one place. The engine builder and the config
 * validator both read from here, so they can never disagree about which rule IDs exist —
 * a typo in marshal.yml is caught against the same list the engine actually runs.
 */
public final class RuleCatalog {

    private RuleCatalog() {
    }

    /** A fresh instance of every shipped rule, in evaluation order. */
    public static List<Rule> defaults() {
        return List.of(
                new MissingSignatureRule(),
                new SignatureDroppedRule(),
                new MajorVersionJumpRule(),
                new NewMaintainerRule(),
                new DependencyExplosionRule(),
                new RepoUrlChangedRule(),
                new YankedVersionRule()
        );
    }

    /**
     * Every valid rule ID — the only keys accepted in a marshal.yml {@code rules.disabled}
     * or {@code rules.overrides} block. These are the canonical hyphenated {@code id()}
     * strings the rule classes expose (and the JSON report emits verbatim).
     */
    public static Set<String> knownIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (Rule rule : defaults()) {
            ids.add(rule.id());
        }
        return ids;
    }
}
