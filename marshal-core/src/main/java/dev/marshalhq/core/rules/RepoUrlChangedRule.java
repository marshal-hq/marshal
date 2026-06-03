package dev.marshalhq.core.rules;

import dev.marshalhq.core.*;

public class RepoUrlChangedRule implements Rule {

    @Override
    public String id() {
        return "REPO-CHANGED";
    }

    @Override
    public String description() {
        return "Repository URL changed since prior release";
    }

    @Override
    public RuleResult evaluate(PackageContext ctx) {
        if (ctx.previous() == null) {
            return new RuleResult(0, Severity.GREEN, "");
        }
        if (ctx.current().dependencyCount() == -1) {
            return new RuleResult(0, Severity.GREEN, "");  // abstain: POM fetch failed, repoUrl unreliable
        }
        String curUrl = ctx.current().repoUrl();
        String prevUrl = ctx.previous().repoUrl();
        if (prevUrl == null) {
            return new RuleResult(0, Severity.GREEN, "");
        }

        if (curUrl == null || !curUrl.equals(prevUrl)) {
            return new RuleResult(20, Severity.YELLOW,
                    "Repository URL changed from [" + prevUrl + "] to [" + curUrl + "]");
        }
        return new RuleResult(0, Severity.GREEN, "");
    }
}
