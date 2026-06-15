package dev.marshalhq.cli;

import java.io.PrintWriter;

/**
 * Renders an already-classified {@link ScanReport} to a specific surface.
 * Reporters format; they do not classify. The buckets and counts they print
 * come from the shared {@link ScanReport}, never from re-filtering findings.
 */
public interface Reporter {

    void report(ScanReport report, PrintWriter out);
}
