package dev.marshalhq.core.config;

import java.util.List;

public class MarshalConfig {

    private int version = 1;
    private ScanConfig scan = new ScanConfig();
    private RulesConfig rules = new RulesConfig();
    private ThresholdsConfig thresholds = new ThresholdsConfig();
    private AllowlistConfig allowlist = new AllowlistConfig();
    private List<RegistryConfig> registries = List.of(new RegistryConfig());
    private NotificationConfig notifications = new NotificationConfig();
    private OutputConfig output = new OutputConfig();

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public ScanConfig getScan() {
        return scan;
    }

    public void setScan(ScanConfig scan) {
        this.scan = scan;
    }

    public RulesConfig getRules() {
        return rules;
    }

    public void setRules(RulesConfig rules) {
        this.rules = rules;
    }

    public ThresholdsConfig getThresholds() {
        return thresholds;
    }

    public void setThresholds(ThresholdsConfig thresholds) {
        this.thresholds = thresholds;
    }

    public AllowlistConfig getAllowlist() {
        return allowlist;
    }

    public void setAllowlist(AllowlistConfig allowlist) {
        this.allowlist = allowlist;
    }

    public List<RegistryConfig> getRegistries() {
        return registries;
    }

    public void setRegistries(List<RegistryConfig> registries) {
        this.registries = registries;
    }

    public NotificationConfig getNotifications() {
        return notifications;
    }

    public void setNotifications(NotificationConfig notifications) {
        this.notifications = notifications;
    }

    public OutputConfig getOutput() {
        return output;
    }

    public void setOutput(OutputConfig output) {
        this.output = output;
    }
}
