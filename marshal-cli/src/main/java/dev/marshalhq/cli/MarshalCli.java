package dev.marshalhq.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParameterException;

@Command(
        name = "marshal",
        mixinStandardHelpOptions = true,
        versionProvider = ManifestVersionProvider.class,
        description = "Behavioral security monitoring for software dependencies.",
        subcommands = { ScanCommand.class, DiffCommand.class }
)
public class MarshalCli implements Runnable {

    public static void main(String[] args) {
        System.exit(create().execute(args));
    }

    /**
     * The configured CommandLine. Package-visible so tests can exercise the parameter
     * handler without {@link System#exit}.
     */
    static CommandLine create() {
        return new CommandLine(new MarshalCli())
                .setParameterExceptionHandler(MarshalCli::onParameterException);
    }

    /**
     * Concise config-error handler: print the one-line reason to stderr and exit 2,
     * with none of picocli's default usage block. A bad flag (e.g. an invalid
     * --threshold) should show the actual problem, not the whole flag reference, both
     * on the terminal and in the Action's config-error comment (run.sh captures stderr).
     */
    private static int onParameterException(ParameterException ex, String[] args) {
        ex.getCommandLine().getErr().println(ex.getMessage());
        return 2;
    }

    @Override
    public void run() {
        System.out.println("Marshal v" + ManifestVersionProvider.version());
        System.out.println("Run 'marshal --help' for usage.");
    }
}
