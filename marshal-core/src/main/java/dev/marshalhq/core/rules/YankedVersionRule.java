package dev.marshalhq.core.rules;

import dev.marshalhq.core.*;

public class YankedVersionRule implements Rule {
    @Override public String id() { return "YANKED"; }
    @Override public String description() { return "This version has been yanked from the registry"; }

    @Override
    public RuleResult evaluate(PackageContext ctx) {
        if (ctx.current().isYanked()) {
            return new RuleResult(25, Severity.ORANGE,
                "This version has been yanked/removed from the registry");
        }
        return new RuleResult(0, Severity.GREEN, "");
    }
}
