package dev.marshalhq.core.rules;

import dev.marshalhq.core.*;

public class NewMaintainerRule implements Rule {

    @Override
    public String id() {
        return "NEW-MAINTAINER";
    }

    @Override
    public String description() {
        return "Publisher identity changed since prior release";
    }

    @Override
    public RuleResult evaluate(PackageContext ctx) {
        if (ctx.previous() == null) {
            return new RuleResult(0, Severity.GREEN, "");
        }
        VersionMetadata cur = ctx.current();
        VersionMetadata prev = ctx.previous();

        boolean emailChanged = cur.publisherEmail() != null
                && !cur.publisherEmail().equals(prev.publisherEmail());
        // Only compare fingerprints when both fetches succeeded with a real signature;
        // UNKNOWN means the fetch failed — null fingerprint does not mean unsigned.
        boolean keyChanged = cur.signatureStatus() == SignatureStatus.PRESENT
                && prev.signatureStatus() == SignatureStatus.PRESENT
                && cur.gpgKeyFingerprint() != null
                && prev.gpgKeyFingerprint() != null
                && !cur.gpgKeyFingerprint().equals(prev.gpgKeyFingerprint());

        if (emailChanged || keyChanged) {
            return new RuleResult(35, Severity.ORANGE,
                    "Publisher identity changed from [" + prev.publisherEmail() +
                            "] to [" + cur.publisherEmail() + "]");
        }
        return new RuleResult(0, Severity.GREEN, "");
    }
}
