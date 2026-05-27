package dev.marshalhq.core.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class ScanConfig {

    @JsonProperty("scopes")
    private List<String> scopes = List.of("compile", "runtime");

    @JsonProperty("include-transitive")
    private boolean includeTransitive = true;

    @JsonProperty("depth")
    private int depth = -1;

    public List<String> getScopes() { return scopes; }
    public void setScopes(List<String> scopes) { this.scopes = scopes; }
    public boolean isIncludeTransitive() { return includeTransitive; }
    public void setIncludeTransitive(boolean includeTransitive) { this.includeTransitive = includeTransitive; }
    public int getDepth() { return depth; }
    public void setDepth(int depth) { this.depth = depth; }
}
