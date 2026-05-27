package dev.marshalhq.core.config;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RulesConfig {
    private List<String> disabled = List.of();
    private Map<String, RuleOverride> overrides = new HashMap<>();

    public List<String> getDisabled() { return disabled; }
    public void setDisabled(List<String> disabled) { this.disabled = disabled; }
    public Map<String, RuleOverride> getOverrides() { return overrides; }
    public void setOverrides(Map<String, RuleOverride> overrides) { this.overrides = overrides; }

    public static class RuleOverride {
        private Integer weight;
        public Integer getWeight() { return weight; }
        public void setWeight(Integer weight) { this.weight = weight; }
    }
}
