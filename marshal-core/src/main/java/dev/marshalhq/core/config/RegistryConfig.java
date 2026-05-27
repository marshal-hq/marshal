package dev.marshalhq.core.config;

public class RegistryConfig {
    private String id = "central";
    private String url = "https://repo1.maven.org/maven2/";
    private boolean enabled = true;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
