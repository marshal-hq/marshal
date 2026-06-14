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

        if (curUrl == null || !baseUrl(curUrl).equals(baseUrl(prevUrl))) {
            return new RuleResult(20, Severity.YELLOW,
                    "Repository URL changed from [" + prevUrl + "] to [" + curUrl + "]");
        }
        return new RuleResult(0, Severity.GREEN, "");
    }

    // Strip version-specific path segments so that tag-only changes don't trigger the rule.
    // e.g. http://svn.apache.org/…/tags/v1_2_16 and …/tags/v1_2_17_rc3 share the same base.
    private static String baseUrl(String url) {
        for (String marker : new String[]{"/tags/", "/branches/", "/tree/", "/commit/"}) {
            int idx = url.indexOf(marker);
            if (idx >= 0) {
                return url.substring(0, idx);
            }
        }
        return url;
    }
}
