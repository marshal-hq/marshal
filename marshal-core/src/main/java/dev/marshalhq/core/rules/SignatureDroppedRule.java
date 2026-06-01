package dev.marshalhq.core.rules;

import dev.marshalhq.core.*;

public class SignatureDroppedRule implements Rule {
    @Override public String id() { return "SIG-DROPPED"; }
    @Override public String description() { return "GPG signature dropped relative to prior releases"; }

    @Override
    public RuleResult evaluate(PackageContext ctx) {
        return switch (ctx.current().signatureStatus()) {
            case PRESENT -> new RuleResult(0, Severity.GREEN, "");
            case UNKNOWN -> new RuleResult(0, Severity.GREEN, "");  // abstain: cannot claim signature was dropped if fetch failed
            case ABSENT -> {
                boolean previousSigned = ctx.previous() != null
                    && ctx.previous().signatureStatus() == SignatureStatus.PRESENT;
                long signedInHistory = ctx.history().stream()
                    .filter(m -> m.signatureStatus() == SignatureStatus.PRESENT)
                    .count();
                if (previousSigned || signedInHistory >= 2) {
                    yield new RuleResult(40, Severity.RED,
                        "GPG signature was present in prior releases but dropped in this version");
                }
                yield new RuleResult(0, Severity.GREEN, "");
            }
        };
    }
}
