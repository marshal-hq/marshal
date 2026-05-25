package dev.marshalhq.core.rules;

import dev.marshalhq.core.*;

public class DependencyExplosionRule implements Rule {
    @Override public String id() { return "DEP-EXPLOSION"; }
    @Override public String description() { return "Dependency count grew suspiciously"; }

    @Override
    public RuleResult evaluate(PackageContext ctx) {
        if (ctx.previous() == null) return new RuleResult(0, Severity.GREEN, "");
        int cur = ctx.current().dependencyCount();
        int prev = ctx.previous().dependencyCount();
        if (prev > 0 && cur > prev * 3) {
            return new RuleResult(25, Severity.ORANGE,
                "Dependency count grew from " + prev + " to " + cur +
                " (" + (cur / prev) + "x increase)");
        }
        return new RuleResult(0, Severity.GREEN, "");
    }
}
