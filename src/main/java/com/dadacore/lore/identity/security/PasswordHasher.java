package com.dadacore.lore.identity.security;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.dadacore.lore.shared.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Hashes passwords with Argon2id. Only the hash is stored.
 *
 * <p>Each computation takes about 16 MiB of memory: as many run at the same time as the server has
 * processors, within what its memory allows (one per 64 MiB of the heap), two at least and eight at
 * most; the others wait their turn. A request that waits more than ten
 * seconds is answered {@code 503 server_busy}: a burst of sign-ins cannot exhaust the memory.
 */
@Component
public class PasswordHasher {

    /** The longest wait for a computation to start. */
    static final Duration MAX_WAIT = Duration.ofSeconds(10);

    private final PasswordEncoder encoder;
    private final Semaphore slots;
    private final Duration maxWait;
    private final String decoy;

    public PasswordHasher() {
        this(slots(Runtime.getRuntime().availableProcessors(), Runtime.getRuntime().maxMemory()), MAX_WAIT,
                Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8());
    }

    /** The computations at once: by processors and by memory, from two to eight. */
    static int slots(int processors, long maxMemory) {
        long byMemory = maxMemory / (64L * 1024 * 1024);
        return (int) Math.max(2, Math.min(8, Math.min(processors, byMemory)));
    }

    PasswordHasher(int slots, Duration maxWait, PasswordEncoder encoder) {
        this.encoder = encoder;
        this.slots = new Semaphore(slots, true);
        this.maxWait = maxWait;
        byte[] random = new byte[24];
        new SecureRandom().nextBytes(random);
        this.decoy = encoder.encode(HexFormat.of().formatHex(random));
    }

    public String hash(String password) {
        return limited(() -> encoder.encode(password));
    }

    public boolean matches(String password, String hash) {
        return password != null && hash != null && limited(() -> encoder.matches(password, hash));
    }

    /**
     * Spends the same time as a real check when no account matches the address, so that the
     * response time does not reveal which accounts exist.
     */
    public void matchDecoy(String password) {
        limited(() -> encoder.matches(password == null ? "" : password, decoy));
    }

    /** Runs one computation when a slot is free, or answers {@code 503 server_busy} after the wait. */
    private <T> T limited(Supplier<T> computation) {
        boolean acquired;
        try {
            acquired = slots.tryAcquire(maxWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            acquired = false;
        }
        if (!acquired) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "server_busy");
        }
        try {
            return computation.get();
        } finally {
            slots.release();
        }
    }
}
