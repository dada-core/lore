package com.dadacore.lore;

import java.io.PrintStream;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

import com.dadacore.lore.shared.CliCommand;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Administration commands run on the server, for example
 * {@code docker compose exec lore lore reset-password <e-mail>}.
 *
 * <p>The application starts without its web server, runs the command and stops.
 */
final class CommandLine {

    private static final Set<String> HELP = Set.of("help", "--help", "-h");

    private CommandLine() {
    }

    static boolean handles(String[] args) {
        return args.length > 0 && !args[0].startsWith("--");
    }

    static int run(String[] args) {
        PrintStream out = System.out;
        PrintStream err = System.err;
        SpringApplication application = new SpringApplication(LoreApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setBannerMode(Banner.Mode.OFF);
        application.setLogStartupInfo(false);
        application.setDefaultProperties(Map.of("spring.main.lazy-initialization", "true"));
        // Passed as arguments, which take precedence over LORE_LOG_LEVEL: the output of a
        // command is its result, not the startup of the application.
        try (ConfigurableApplicationContext context = application.run(
                "--logging.level.root=WARN", "--spring.main.log-startup-info=false")) {
            Map<String, CliCommand> commands = context.getBeansOfType(CliCommand.class);
            if (HELP.contains(args[0])) {
                printUsage(out, commands);
                return 0;
            }
            CliCommand command = commands.values().stream()
                    .filter(candidate -> candidate.name().equals(args[0]))
                    .findFirst()
                    .orElse(null);
            if (command == null) {
                err.println("Unknown command: " + args[0]);
                printUsage(err, commands);
                return 2;
            }
            return command.run(Arrays.copyOfRange(args, 1, args.length), out, err);
        }
    }

    private static void printUsage(PrintStream stream, Map<String, CliCommand> commands) {
        stream.println("Usage: lore <command> [arguments]");
        stream.println();
        stream.println("Commands:");
        commands.values().forEach(command ->
                stream.printf("  %-16s %s%n", command.name(), command.description()));
    }
}
