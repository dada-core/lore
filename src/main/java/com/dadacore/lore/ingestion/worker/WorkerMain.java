package com.dadacore.lore.ingestion.worker;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

import ch.qos.logback.classic.Level;
import com.dadacore.lore.ingestion.Extraction;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The loop of the extraction process. One request per line on the standard input; the result is
 * written to the file the request names, and one line on the standard output says how it went.
 * Everything the libraries print goes to the standard error, which Lore copies to its log.
 */
public final class WorkerMain {

    /** Below this free space in the work folder, a failure is put down to the server. */
    static final long MIN_FREE_BYTES = 100L * 1024 * 1024;

    /** Requests a simulated hang, crash or lack of memory, for the tests only: see {@code lore.extraction.test-hooks}. */
    public static final String TEST_HOOKS = "lore.extraction.test-hooks";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * @param path          the file to read
     * @param mediaType     its type, as decided by Lore at import
     * @param output        where to write the result
     * @param maxCharacters the maximum of text kept
     * @param simulate      {@code hang}, {@code crash} or {@code oom}, honoured only with the test hooks
     */
    public record Request(String path, String mediaType, String output, int maxCharacters, @Nullable String simulate) {
    }

    private WorkerMain() {
    }

    public static void run() {
        PrintStream replies = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        System.setOut(System.err);
        quietLogs();
        boolean testHooks = Boolean.getBoolean(TEST_HOOKS);
        try (BufferedReader requests = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = requests.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                replies.println(JSON.writeValueAsString(handle(JSON.readValue(line, Request.class), testHooks)));
            }
        } catch (IOException e) {
            System.err.println("Extraction process: " + e.getMessage());
            System.exit(2);
        }
    }

    private static Map<String, Object> handle(Request request, boolean testHooks) {
        if (testHooks && request.simulate() != null) {
            simulate(request.simulate());
        }
        Map<String, Object> reply = new LinkedHashMap<>();
        try {
            Extraction extraction = new Extractors(request.maxCharacters())
                    .extract(Path.of(request.path()), request.mediaType());
            Path output = Path.of(request.output());
            Path partial = output.resolveSibling(output.getFileName() + ".part");
            JSON.writeValue(partial.toFile(), extraction);
            Files.move(partial, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            reply.put("ok", true);
        } catch (ExtractionFailure failure) {
            boolean server = failure.code().equals(ExtractionFailure.CORRUPT) && serverTrouble(Path.of(request.path()));
            if (server) {
                System.err.println("Extraction process: " + failure.getMessage() + " (disk of the server short)");
            }
            reply.put("ok", false);
            // Tried again later rather than failed for good, the file being blamed for the server.
            reply.put("error", server ? "extractor_crashed" : failure.code());
            reply.put("detail", failure.getMessage());
        } catch (NoSuchFileException missing) {
            reply.put("ok", false);
            reply.put("error", "file_missing");
        } catch (IOException | JacksonException e) {
            // Neither missing nor damaged: the disk of the server (full, unreadable). Tried again.
            System.err.println("Extraction process: " + e);
            reply.put("ok", false);
            reply.put("error", "extractor_crashed");
            reply.put("detail", e.getMessage());
        } catch (StackOverflowError e) {
            // A document nested beyond reason: it would fail again.
            reply.put("ok", false);
            reply.put("error", ExtractionFailure.CORRUPT);
        }
        return reply;
    }

    /** The disk of the work folder nearly full, or the file unreadable: the server, not the file. */
    private static boolean serverTrouble(Path file) {
        try {
            Path work = Path.of(System.getProperty("java.io.tmpdir"));
            return !Files.isReadable(file) || Files.getFileStore(work).getUsableSpace() < MIN_FREE_BYTES;
        } catch (IOException | RuntimeException e) {
            return true;
        }
    }

    private static void simulate(String what) {
        if (what.equals("crash")) {
            System.exit(3);
        }
        if (what.equals("oom")) {
            // Fills the memory: the Java machine stops on its own (-XX:+ExitOnOutOfMemoryError).
            java.util.List<byte[]> hog = new java.util.ArrayList<>();
            while (hog.size() >= 0) {
                hog.add(new byte[16 * 1024 * 1024]);
            }
        }
        if (what.equals("hang")) {
            try {
                Thread.sleep(Long.MAX_VALUE);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** The libraries log their warnings only; nothing at all without a logger. */
    private static void quietLogs() {
        Logger root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        if (root instanceof ch.qos.logback.classic.Logger logback) {
            logback.setLevel(Level.WARN);
        }
    }
}
