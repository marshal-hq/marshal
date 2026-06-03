package dev.marshalhq.core.rules;

import dev.marshalhq.core.*;

public class DependencyExplosionRule implements Rule {

    @Override
    public String id() {
        return "DEP-EXPLOSION";
    }

    @Override
    public String description() {
        return "Dependency count grew suspiciously";
    }

    @Override
    public RuleResult evaluate(PackageContext ctx) {
        if (ctx.previous() == null) {
            return new RuleResult(0, Severity.GREEN, "");
        }
        int cur = ctx.current().dependencyCount();
        int prev = ctx.previous().dependencyCount();
        if (cur == -1 || prev == -1) {
            return new RuleResult(0, Severity.GREEN, "");  // abstain: POM fetch failed
        }
        if (prev > 0 && cur > prev * 3) {
            return new RuleResult(25, Severity.ORANGE,
                    String.format("Dependency count grew from %d to %d (%.1fx increase)", prev, cur, (double) cur / prev));
        }
        return new RuleResult(0, Severity.GREEN, "");
    }
}
