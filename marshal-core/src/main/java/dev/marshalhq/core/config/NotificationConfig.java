package dev.marshalhq.core.config;

import com.fasterxml.jackson.annotation.JsonProperty;

public class NotificationConfig {
    private SlackConfig slack = new SlackConfig();
    private EmailConfig email = new EmailConfig();

    public SlackConfig getSlack() { return slack; }
    public void setSlack(SlackConfig slack) { this.slack = slack; }
    public EmailConfig getEmail() { return email; }
    public void setEmail(EmailConfig email) { this.email = email; }

    public static class SlackConfig {
        private String webhook = "";
        @JsonProperty("min-level")
        private String minLevel = "red";
        public String getWebhook() { return webhook; }
        public void setWebhook(String webhook) { this.webhook = webhook; }
        public String getMinLevel() { return minLevel; }
        public void setMinLevel(String minLevel) { this.minLevel = minLevel; }
    }

    public static class EmailConfig {
        private String to = "";
        @JsonProperty("min-level")
        private String minLevel = "orange";
        public String getTo() { return to; }
        public void setTo(String to) { this.to = to; }
        public String getMinLevel() { return minLevel; }
        public void setMinLevel(String minLevel) { this.minLevel = minLevel; }
    }
}
