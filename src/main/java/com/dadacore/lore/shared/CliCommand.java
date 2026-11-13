package com.dadacore.lore.shared;

import java.io.PrintStream;

/**
 * A command run on the server: {@code lore <name> [arguments]}.
 */
public interface CliCommand {

    String name();

    String description();

    /** Runs the command and returns the process exit code. */
    int run(String[] args, PrintStream out, PrintStream err);
}
