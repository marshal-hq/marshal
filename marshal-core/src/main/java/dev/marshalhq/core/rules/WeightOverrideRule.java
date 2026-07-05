package dev.marshalhq.core.rules;

import dev.marshalhq.core.PackageContext;
import dev.marshalhq.core.Rule;
import dev.marshalhq.core.RuleResult;

/**
 * Wraps a rule so a weight from marshal.yml replaces the rule's built-in score whenever
 * it fires. A clean or abstaining result (zero contribution) passes through untouched: an
 * override changes how loud a signal is, never whether a silent rule starts speaking.
 */
public final class WeightOverrideRule implements Rule {

    private final Rule delegate;
    private final int weight;

    public WeightOverrideRule(Rule delegate, int weight) {
        this.delegate = delegate;
        this.weight = weight;
    }

    @Override
    public String id() {
        return delegate.id();
    }

    @Override
    public String description() {
        return delegate.description();
    }

    @Override
    public RuleResult evaluate(PackageContext ctx) {
        RuleResult result = delegate.evaluate(ctx);
        if (result.scoreContribution() <= 0) {
            return result;
        }
        return new RuleResult(weight, result.severity(), result.evidence(), result.ruleId());
    }
}
