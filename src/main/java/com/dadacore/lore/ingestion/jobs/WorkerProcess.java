package com.dadacore.lore.ingestion.jobs;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;

import com.dadacore.lore.ingestion.ExtractionWorker;
import com.dadacore.lore.ingestion.worker.WorkerMain;
import org.springframework.util.unit.DataSize;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One extraction process: the same Java and the same Lore, started on {@link ExtractionWorker},
 * its environment emptied of the settings of Lore (database password included). Requests and replies are lines of JSON on its standard input and output; what it
 * logs goes to the log of Lore.
 */
final class WorkerProcess implements AutoCloseable {

    /** The reply of the process to one request. */
    record Reply(boolean ok, String error) {
    }

    /** The process did not answer in time. */
    static final class Timeout extends Exception {
    }

    /** The process stopped (crashed, out of memory, killed), or answered something unreadable. */
    static final class Crashed extends Exception {
    }

    /** Only these variables reach the process: neither the settings of Lore nor any other secret. */
    private static final Set<String> KEPT_ENVIRONMENT = Set.of("PATH", "HOME", "USERPROFILE", "TMP", "TEMP",
            "TMPDIR", "LANG", "LC_ALL", "LC_CTYPE", "SYSTEMROOT", "WINDIR", "JAVA_HOME");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String END = "\u0000end";
    private static final Object ARGUMENT_FILES = new Object();

    private final Process process;
    private final Writer requests;
    private final BlockingQueue<String> replies = new LinkedBlockingQueue<>();
    private int handled;
    private int answered;

    private WorkerProcess(Process process) {
        this.process = process;
        this.requests = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
        Thread reader = Thread.ofPlatform().daemon().name("lore-extraction-reader-" + process.pid()).start(() -> {
            try (BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = out.readLine()) != null) {
                    replies.add(line);
                }
            } catch (IOException e) {
                // The process stopped: reported below.
            }
            replies.add(END);
        });
        reader.setUncaughtExceptionHandler((thread, error) -> replies.add(END));
    }

    static WorkerProcess start(DataSize memory, Path workFolder, boolean testHooks) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command(memory, workFolder, testHooks))
                .redirectError(ProcessBuilder.Redirect.INHERIT);
        builder.environment().keySet().removeIf(name -> !KEPT_ENVIRONMENT.contains(name.toUpperCase(Locale.ROOT)));
        return new WorkerProcess(builder.start());
    }

    /**
     * Sends one request and waits for its reply.
     *
     * @throws InterruptedException when Lore stops meanwhile: the job goes back to the queue
     */
    Reply extract(Path file, String mediaType, Path output, int maxCharacters, String simulate, Duration timeout)
            throws Timeout, Crashed, InterruptedException {
        handled++;
        WorkerMain.Request request = new WorkerMain.Request(file.toString(), mediaType, output.toString(),
                maxCharacters, simulate);
        try {
            requests.write(JSON.writeValueAsString(request));
            requests.write('\n');
            requests.flush();
        } catch (IOException e) {
            throw new Crashed();
        }
        String line = replies.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (line == null) {
            throw new Timeout();
        }
        if (line.equals(END)) {
            throw new Crashed();
        }
        try {
            JsonNode reply = JSON.readTree(line);
            if (reply == null || !reply.has("ok")) {
                throw new Crashed();
            }
            answered++;
            return new Reply(reply.path("ok").asBoolean(false), reply.path("error").asString(null));
        } catch (JacksonException unreadable) {
            // Not a reply of the extraction process: it cannot be trusted any more.
            throw new Crashed();
        }
    }

    int handled() {
        return handled;
    }

    /** The replies it gave: none means it never managed to work. */
    int answered() {
        return answered;
    }

    /** Its exit code once stopped, for the administrators. */
    String exit() {
        try {
            return process.waitFor(2, TimeUnit.SECONDS) ? "exit code " + process.exitValue() : "still running";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted";
        }
    }

    boolean alive() {
        return process.isAlive();
    }

    /** Closes its input, which ends it; kills it if it does not end on its own. */
    @Override
    public void close() {
        try {
            requests.close();
        } catch (IOException ignored) {
            // Already gone.
        }
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    void kill() {
        process.destroyForcibly();
    }

    /**
     * The command that starts the extraction process. From the jar of Lore, through the launcher
     * of Spring Boot that reads the class to start from {@code loader.main}; otherwise
     * (development, tests) on the same class path.
     */
    static List<String> command(DataSize memory, Path workFolder, boolean testHooks) throws IOException {
        String java = ProcessHandle.current().info().command()
                .orElse(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        List<String> command = new ArrayList<>(List.of(java,
                "-Xmx" + memory.toMegabytes() + "m",
                "-XX:+ExitOnOutOfMemoryError",
                // The messages of the Java machine itself, out of memory included, stay off the replies.
                "-XX:+DisplayVMOutputToStderr",
                "-XX:+UseSerialGC",
                "-Djava.awt.headless=true",
                "-Dfile.encoding=UTF-8",
                "-Djava.io.tmpdir=" + workFolder));
        if (testHooks) {
            command.add("-D" + WorkerMain.TEST_HOOKS + "=true");
        }
        String classPath = System.getProperty("java.class.path");
        if (isLoreJar(classPath)) {
            command.addAll(List.of("-Dloader.main=" + ExtractionWorker.class.getName(), "-cp", classPath,
                    "org.springframework.boot.loader.launch.PropertiesLauncher"));
        } else {
            // Development and tests: a class path of a few hundred jars, too long for the command
            // line of Windows (32,767 characters), given through a file of arguments of Java.
            command.addAll(List.of("@" + argumentFile(workFolder, classPath), ExtractionWorker.class.getName()));
        }
        return command;
    }

    /**
     * Written once, then only read: several workers start their processes at the same time. Named
     * after its class path, so that a file left by another run is never the wrong one.
     */
    private static Path argumentFile(Path workFolder, String classPath) throws IOException {
        String name = "worker-classpath-" + Integer.toHexString(classPath.hashCode()) + ".args";
        Path file = workFolder.resolve(name);
        synchronized (ARGUMENT_FILES) {
            if (!Files.exists(file)) {
                Files.createDirectories(workFolder);
                Path partial = workFolder.resolve(name + ".part");
                Files.writeString(partial, "-cp \"" + classPath.replace("\\", "\\\\") + "\"\n", StandardCharsets.UTF_8);
                Files.move(partial, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return file;
    }

    /** The jar of Lore, built by Spring Boot, which names the class it starts. */
    private static boolean isLoreJar(String classPath) {
        if (classPath.contains(File.pathSeparator) || !classPath.endsWith(".jar")) {
            return false;
        }
        try (JarFile jar = new JarFile(classPath)) {
            return jar.getManifest() != null && jar.getManifest().getMainAttributes().getValue("Start-Class") != null;
        } catch (IOException e) {
            return false;
        }
    }
}
