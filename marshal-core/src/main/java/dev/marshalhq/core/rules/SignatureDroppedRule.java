package dev.marshalhq.core.rules;

import dev.marshalhq.core.*;

public class SignatureDroppedRule implements Rule {
    @Override public String id() { return "SIG-DROPPED"; }
    @Override public String description() { return "GPG signature dropped relative to prior releases"; }

    @Override
    public RuleResult evaluate(PackageContext ctx) {
        if (ctx.current().hasGpgSignature()) {
            return new RuleResult(0, Severity.GREEN, "");
        }
        boolean previousSigned = ctx.previous() != null && ctx.previous().hasGpgSignature();
        long signedInHistory = ctx.history().stream()
            .filter(VersionMetadata::hasGpgSignature)
            .count();
        if (previousSigned || signedInHistory >= 2) {
            return new RuleResult(40, Severity.RED,
                "GPG signature was present in prior releases but dropped in this version");
        }
        return new RuleResult(0, Severity.GREEN, "");
    }
}
