package com.dadacore.lore.ingestion.jobs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.dadacore.lore.LoreProperties;
import com.dadacore.lore.ingestion.Extraction;
import com.dadacore.lore.ingestion.ExtractionHealth;
import com.dadacore.lore.ingestion.ExtractionResults;
import com.dadacore.lore.storage.FileStore;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The worker threads: each takes the next job, has it run by its extraction process, and records
 * the result in a new transaction if its lease still holds.
 *
 * <p>A process starts at the first job, stops after {@value #IDLE_MINUTES} minutes without work
 * and is replaced every {@value #RECYCLE_AFTER} files. A timeout, a crash or a missing file is
 * tried again ({@value #MAX_ATTEMPTS} attempts in all); a protected, damaged or misnamed file fails
 * at once. Not started by the administration commands ({@code lore ...}), which have no web
 * server, nor in the tests that do not ask for it.
 */
@Component
@ConditionalOnWebApplication
@ConditionalOnProperty(name = "lore.extraction.enabled", havingValue = "true", matchIfMissing = true)
class ExtractionWorkers implements SmartLifecycle, ExtractionHealth {

    static final int MAX_CHARACTERS = 10_000_000;
    static final int MAX_ATTEMPTS = 3;
    static final int RECYCLE_AFTER = 200;
    static final int IDLE_MINUTES = 5;

    /** What becomes of a job once the process has answered, or failed to. */
    private sealed interface Outcome {
        record Extracted(Extraction extraction) implements Outcome {
        }

        record Failed(String code) implements Outcome {
        }

        record Passing(String code) implements Outcome {
        }
    }

    /** The result of an extraction never weighs more: 10 million characters, titles and JSON included. */
    static final long MAX_RESULT_BYTES = 100L * 1024 * 1024;

    private static final Logger log = LoggerFactory.getLogger(ExtractionWorkers.class);
    private static final Set<String> PASSING = ExtractionResults.PASSING_FAILURES;
    private static final long POLL_MILLIS = 5_000;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ExtractionJobs jobs;
    private final ExtractionSignal signal;
    private final ExtractionResults results;
    private final FileStore store;
    private final TransactionTemplate transaction;
    private final LoreProperties properties;
    private final List<Duration> retryDelays;
    private final boolean testHooks;
    private final AtomicReference<String> simulateNext = new AtomicReference<>();
    private final List<Thread> threads = new ArrayList<>();
    private volatile boolean running;
    private volatile @Nullable String problem;

    ExtractionWorkers(ExtractionJobs jobs, ExtractionSignal signal, ExtractionResults results, FileStore store,
            TransactionTemplate transaction, LoreProperties properties,
            @Value("${lore.extraction.retry-delays:1m,5m}") List<String> retryDelays,
            @Value("${lore.extraction.test-hooks:false}") boolean testHooks) {
        this.jobs = jobs;
        this.signal = signal;
        this.results = results;
        this.store = store;
        this.transaction = transaction;
        this.properties = properties;
        this.retryDelays = retryDelays.stream().map(String::strip).map(DurationStyle::detectAndParse).toList();
        this.testHooks = testHooks;
    }

    @Override
    public State state() {
        return new State(properties.extractionWorkers(), problem);
    }

    /** For the tests only (lore.extraction.test-hooks): the next extraction hangs, crashes or runs out of memory. */
    void simulateNext(String what) {
        if (!testHooks) {
            throw new IllegalStateException("lore.extraction.test-hooks is off");
        }
        simulateNext.set(what);
    }

    /** For the tests only: true while a simulation waits for an extraction to take it. */
    boolean simulationPending() {
        return simulateNext.get() != null;
    }

    @Override
    public synchronized void start() {
        running = true;
        for (int i = 1; i <= properties.extractionWorkers(); i++) {
            threads.add(Thread.ofPlatform().daemon().name("lore-extraction-" + i).start(new Worker()));
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        threads.forEach(Thread::interrupt);
        for (Thread thread : threads) {
            try {
                thread.join(Duration.ofSeconds(10));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        threads.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private final class Worker implements Runnable {

        private @Nullable WorkerProcess process;
        private long idleSince = System.nanoTime();
        private @Nullable UUID current;

        @Override
        public void run() {
            while (running && !Thread.currentThread().isInterrupted()) {
                try {
                    UUID lease = UUID.randomUUID();
                    long seen = signal.generation();
                    Optional<ExtractionJobs.Job> job = jobs.take(lease, properties.extractionTimeout().plusSeconds(30));
                    if (job.isEmpty()) {
                        stopIfIdle();
                        signal.await(seen, POLL_MILLIS);
                        continue;
                    }
                    current = lease;
                    handle(job.get(), lease);
                    current = null;
                    idleSince = System.nanoTime();
                } catch (InterruptedException e) {
                    // Lore stops: the job in progress goes back to the queue below, without counting.
                    Thread.currentThread().interrupt();
                } catch (RuntimeException e) {
                    // The database is out of reach, for example: the job comes back once its lease
                    // expires, and the thread goes on rather than leaving the extraction stopped.
                    log.warn("Extraction worker: {}", e.toString());
                    current = null;
                    pause();
                } catch (Error e) {
                    log.warn("Extraction worker: {}", e.toString());
                    current = null;
                    close();
                    pause();
                }
            }
            close();
            if (current != null) {
                try {
                    jobs.release(current);
                } catch (RuntimeException e) {
                    log.warn("Could not give back the extraction in progress: {}", e.getMessage());
                }
            }
        }

        private void handle(ExtractionJobs.Job job, UUID lease) throws InterruptedException {
            if (job.attempts() > MAX_ATTEMPTS) {
                // Taken over after its lease expired too many times: its worker kept stopping on it.
                record(job, lease, () -> results.failed(job.versionId(), "extractor_crashed"));
                return;
            }
            Path file = store.path(job.organizationId(), job.sha256());
            if (!Files.isRegularFile(file)) {
                passing(job, lease, "file_missing");
                return;
            }
            Path output = store.extractionFolder().resolve(job.versionId() + ".json");
            Outcome outcome;
            try {
                outcome = extract(job, file, output);
            } catch (InterruptedException stopping) {
                kill();
                jobs.release(lease);
                throw stopping;
            } finally {
                deleteQuietly(output);
                deleteQuietly(output.resolveSibling(output.getFileName() + ".part"));
            }
            // Recorded apart: a failure of the database is not one of the extraction process.
            switch (outcome) {
                case Outcome.Extracted(Extraction extraction) ->
                        record(job, lease, () -> results.extracted(job.versionId(), extraction));
                case Outcome.Failed(String code) -> record(job, lease, () -> results.failed(job.versionId(), code));
                case Outcome.Passing(String code) -> passing(job, lease, code);
            }
        }

        /** Runs the extraction in the process and reads its result, without touching the database. */
        private Outcome extract(ExtractionJobs.Job job, Path file, Path output) throws InterruptedException {
            WorkerProcess worker;
            try {
                worker = process();
            } catch (IOException e) {
                problem = "The extraction process cannot start: " + e.getMessage();
                log.warn(problem);
                return new Outcome.Passing("extractor_crashed");
            }
            WorkerProcess.Reply reply;
            try {
                reply = worker.extract(file, job.mediaType(), output, MAX_CHARACTERS,
                        testHooks ? simulateNext.getAndSet(null) : null, properties.extractionTimeout());
            } catch (WorkerProcess.Timeout timeout) {
                log.warn("Extraction of the version {} stopped after {}", job.versionId(), properties.extractionTimeout());
                kill();
                return new Outcome.Passing("timeout");
            } catch (WorkerProcess.Crashed crashed) {
                log.warn("The extraction process stopped while reading the version {}", job.versionId());
                if (worker.answered() == 0) {
                    // It never worked: the administrators see it, instead of "Active".
                    problem = "The extraction process stopped before answering (" + worker.exit() + ")";
                }
                close();
                return new Outcome.Passing("extractor_crashed");
            }
            problem = null;
            if (!reply.ok()) {
                String code = reply.error() == null ? "corrupt" : reply.error();
                return PASSING.contains(code) ? new Outcome.Passing(code) : new Outcome.Failed(code);
            }
            try {
                if (Files.size(output) > MAX_RESULT_BYTES) {
                    // More than the text kept could ever weigh: built to exhaust the application.
                    return new Outcome.Failed("corrupt");
                }
                return new Outcome.Extracted(JSON.readValue(output.toFile(), Extraction.class));
            } catch (IOException | JacksonException e) {
                // Announced but unreadable: tried again with a new process.
                log.warn("Unreadable result for the version {}: {}", job.versionId(), e.toString());
                close();
                return new Outcome.Passing("extractor_crashed");
            }
        }

        private void deleteQuietly(Path file) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                log.debug("Could not delete {}", file);
            }
        }

        /** A cause that may pass: tried again later, until the attempts run out. */
        private void passing(ExtractionJobs.Job job, UUID lease, String code) {
            if (job.attempts() < MAX_ATTEMPTS) {
                Duration delay = retryDelays.get(Math.min(job.attempts(), retryDelays.size()) - 1);
                jobs.postpone(job.versionId(), lease, delay, code);
            } else {
                record(job, lease, () -> results.failed(job.versionId(), code));
            }
        }

        /** Ends the job and records its outcome together, if the job is still ours. */
        private void record(ExtractionJobs.Job job, UUID lease, Runnable outcome) {
            transaction.executeWithoutResult(status -> {
                if (jobs.finish(job.versionId(), lease)) {
                    outcome.run();
                }
            });
        }

        private WorkerProcess process() throws IOException {
            if (process != null && (!process.alive() || process.handled() >= RECYCLE_AFTER)) {
                close();
            }
            if (process == null) {
                process = WorkerProcess.start(properties.extractionMemory(), store.extractionFolder(), testHooks);
            }
            return process;
        }

        private void stopIfIdle() {
            if (process != null && System.nanoTime() - idleSince > Duration.ofMinutes(IDLE_MINUTES).toNanos()) {
                close();
            }
        }

        private void kill() {
            if (process != null) {
                process.kill();
                process = null;
            }
        }

        private void close() {
            if (process != null) {
                process.close();
                process = null;
            }
        }

        private void pause() {
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
