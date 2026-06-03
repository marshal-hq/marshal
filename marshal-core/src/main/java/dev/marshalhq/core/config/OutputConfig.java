package dev.marshalhq.core.config;

public class OutputConfig {

    private String format = "human";
    private String color = "auto";

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }
}
