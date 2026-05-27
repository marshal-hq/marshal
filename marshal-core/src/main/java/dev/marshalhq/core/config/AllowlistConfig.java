package dev.marshalhq.core.config;

import java.util.List;

public class AllowlistConfig {
    private List<String> packages = List.of();

    public List<String> getPackages() { return packages; }
    public void setPackages(List<String> packages) { this.packages = packages; }

    public boolean isAllowed(String ga) {
        return packages.stream().anyMatch(pattern -> {
            if (pattern.endsWith(":*")) {
                String prefix = pattern.substring(0, pattern.length() - 1);
                return ga.startsWith(prefix);
            }
            return ga.equals(pattern);
        });
    }
}
