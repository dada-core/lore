package com.dadacore.lore.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.dadacore.lore.shared.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Each Argon2 computation takes about 16 MiB: only a few run at the same time. */
class PasswordHasherTests {

    @Test
    void theSlotsFollowTheProcessorsWithinTheMemoryFromTwoToEight() {
        long mebibyte = 1024L * 1024;
        assertThat(PasswordHasher.slots(4, 2048 * mebibyte)).isEqualTo(4);
        assertThat(PasswordHasher.slots(32, 512 * mebibyte)).isEqualTo(8);
        assertThat(PasswordHasher.slots(32, 256 * mebibyte)).isEqualTo(4);
        assertThat(PasswordHasher.slots(32, 64 * 1024 * mebibyte)).isEqualTo(8);
        assertThat(PasswordHasher.slots(1, 4096 * mebibyte)).isEqualTo(2);
    }

    @Test
    void parallelHashesNeverExceedTheSlots() throws Exception {
        Watched encoder = new Watched(Duration.ofMillis(40), null);
        PasswordHasher hasher = new PasswordHasher(2, Duration.ofSeconds(30), encoder);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> calls = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                int kind = i % 3;
                calls.add(pool.submit(() -> {
                    switch (kind) {
                        case 0 -> hasher.hash("correct-horse-battery");
                        case 1 -> hasher.matches("correct-horse-battery", "stored-hash");
                        default -> hasher.matchDecoy("correct-horse-battery");
                    }
                    return null;
                }));
            }
            for (Future<?> call : calls) {
                call.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(encoder.calls.get()).isEqualTo(24 + 1);
        assertThat(encoder.most.get()).isEqualTo(2);
    }

    @Test
    void aHashThatWaitsTooLongIsRefusedAsBusy() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Watched encoder = new Watched(Duration.ZERO, release);
        PasswordHasher hasher = new PasswordHasher(1, Duration.ofMillis(200), encoder);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> holding = pool.submit(() -> hasher.hash("first"));
            while (encoder.running.get() == 0) {
                Thread.sleep(5);
            }

            assertThatThrownBy(() -> hasher.hash("second"))
                    .isInstanceOfSatisfying(ApiException.class, busy -> {
                        assertThat(busy.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                        assertThat(busy.code()).isEqualTo("server_busy");
                    });

            release.countDown();
            holding.get(10, TimeUnit.SECONDS);
            assertThat(hasher.hash("third")).isEqualTo("hash");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    /** Counts the computations running at the same time; a latch can hold them. */
    private static final class Watched implements PasswordEncoder {

        final AtomicInteger running = new AtomicInteger();
        final AtomicInteger most = new AtomicInteger();
        final AtomicInteger calls = new AtomicInteger();
        private final Duration duration;
        private final CountDownLatch release;

        Watched(Duration duration, CountDownLatch release) {
            this.duration = duration;
            this.release = release;
        }

        @Override
        public String encode(CharSequence rawPassword) {
            work();
            return "hash";
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            work();
            return false;
        }

        private void work() {
            calls.incrementAndGet();
            most.accumulateAndGet(running.incrementAndGet(), Math::max);
            try {
                if (release != null && calls.get() == 2) {
                    release.await(10, TimeUnit.SECONDS);
                }
                Thread.sleep(duration.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                running.decrementAndGet();
            }
        }
    }
}
