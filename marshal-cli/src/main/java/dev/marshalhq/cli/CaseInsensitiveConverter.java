package dev.marshalhq.cli;

import dev.marshalhq.core.Severity;
import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.TypeConversionException;

import java.util.Locale;

/**
 * Picocli type converters that accept enum values in any case (json, JSON, Json).
 * <p>
 * On an unknown value each throws a {@link TypeConversionException} with a concise,
 * specific message naming the valid choices, so picocli reports the actual problem
 * rather than a bare "No enum constant" or a wall of usage text.
 */
public class CaseInsensitiveConverter {

    public static class ForOutputFormat implements ITypeConverter<OutputFormat> {
        @Override
        public OutputFormat convert(String value) {
            try {
                return OutputFormat.valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new TypeConversionException(
                        "'" + value + "' is not a valid output format (choose one of: human, json, md)");
            }
        }
    }

    public static class ForFailOn implements ITypeConverter<FailOn> {
        @Override
        public FailOn convert(String value) {
            try {
                return FailOn.valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new TypeConversionException(
                        "'" + value + "' is not a valid fail-on value (choose one of: fail, warn, never)");
            }
        }
    }

    public static class ForSeverity implements ITypeConverter<Severity> {
        @Override
        public Severity convert(String value) {
            try {
                return Severity.valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new TypeConversionException(
                        "'" + value + "' is not a valid threshold (choose one of: green, yellow, orange, red)");
            }
        }
    }
}
