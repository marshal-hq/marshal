package dev.marshalhq.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
    name = "marshal",
    mixinStandardHelpOptions = true,
    version = "marshal 0.1.0-SNAPSHOT",
    description = "Behavioral security monitoring for software dependencies.",
    subcommands = { ScanCommand.class }
)
public class MarshalCli implements Runnable {
    public static void main(String[] args) {
        int exitCode = new CommandLine(new MarshalCli()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public void run() {
        System.out.println("Marshal v0.1.0-SNAPSHOT");
        System.out.println("Run 'marshal --help' for usage.");
    }
}
