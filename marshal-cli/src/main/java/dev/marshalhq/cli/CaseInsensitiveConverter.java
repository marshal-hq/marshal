package dev.marshalhq.cli;

import dev.marshalhq.core.Severity;
import picocli.CommandLine.ITypeConverter;

import java.util.Locale;

/** Picocli type converters that accept enum values in any case (json, JSON, Json). */
public class CaseInsensitiveConverter {

    public static class ForOutputFormat implements ITypeConverter<OutputFormat> {
        @Override
        public OutputFormat convert(String value) {
            return OutputFormat.valueOf(value.toUpperCase(Locale.ROOT));
        }
    }

    public static class ForFailOn implements ITypeConverter<FailOn> {
        @Override
        public FailOn convert(String value) {
            return FailOn.valueOf(value.toUpperCase(Locale.ROOT));
        }
    }

    public static class ForSeverity implements ITypeConverter<Severity> {
        @Override
        public Severity convert(String value) {
            return Severity.valueOf(value.toUpperCase(Locale.ROOT));
        }
    }
}
