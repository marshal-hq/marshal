package dev.marshalhq.core.rules;

import dev.marshalhq.core.*;

public class MissingSignatureRule implements Rule {
    @Override public String id() { return "MISSING-SIG"; }
    @Override public String description() { return "GPG signature is absent for this release"; }

    @Override
    public RuleResult evaluate(PackageContext ctx) {
        return switch (ctx.current().signatureStatus()) {
            case ABSENT  -> new RuleResult(15, Severity.YELLOW, "No GPG signature present for this release");
            case UNKNOWN -> new RuleResult(0, Severity.GREEN, "");  // abstain: fetch failed, cannot distinguish from unsigned
            case PRESENT -> new RuleResult(0, Severity.GREEN, "");
        };
    }
}
