package dev.marshalhq.core;

public interface Rule {
    String id();
    String description();
    RuleResult evaluate(PackageContext ctx);
}
