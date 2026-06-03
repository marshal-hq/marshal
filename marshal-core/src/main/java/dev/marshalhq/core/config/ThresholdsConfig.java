package dev.marshalhq.core.config;

import com.fasterxml.jackson.annotation.JsonProperty;

public class ThresholdsConfig {

    @JsonProperty("fail-on")
    private String failOn = "red";

    @JsonProperty("warn-on")
    private String warnOn = "orange";

    public String getFailOn() {
        return failOn;
    }

    public void setFailOn(String failOn) {
        this.failOn = failOn;
    }

    public String getWarnOn() {
        return warnOn;
    }

    public void setWarnOn(String warnOn) {
        this.warnOn = warnOn;
    }
}
