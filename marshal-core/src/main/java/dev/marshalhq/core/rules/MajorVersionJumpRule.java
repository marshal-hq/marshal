package dev.marshalhq.core.rules;

import dev.marshalhq.core.*;

public class MajorVersionJumpRule implements Rule {
    @Override public String id() { return "MAJOR-JUMP"; }
    @Override public String description() { return "Major version jumped suspiciously"; }

    @Override
    public RuleResult evaluate(PackageContext ctx) {
        if (ctx.previous() == null) return new RuleResult(0, Severity.GREEN, "");
        int currentMajor = parseMajor(ctx.current().coordinates().version());
        int previousMajor = parseMajor(ctx.previous().coordinates().version());
        if (currentMajor < 0 || previousMajor < 0) return new RuleResult(0, Severity.GREEN, "");
        int diff = currentMajor - previousMajor;
        if (diff > 2) {
            return new RuleResult(20, Severity.YELLOW,
                "Major version jumped by " + diff + " from previous release");
        }
        return new RuleResult(0, Severity.GREEN, "");
    }

    private int parseMajor(String version) {
        try {
            String segment = version.contains(".") ? version.substring(0, version.indexOf('.')) : version;
            return Integer.parseInt(segment.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
