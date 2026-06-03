package dev.marshalhq.cli;

import dev.marshalhq.core.Finding;

import java.io.PrintWriter;
import java.util.List;

public interface Reporter {

    void report(List<Finding> findings, PrintWriter out);
}
