package com.dadacore.lore.identity.login;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Counts failed sign-ins and blocks further attempts.
 *
 * <p>Two counters: one per address and client IP, which blocks after the organization's number of
 * attempts (5 by default); one per address alone, four times higher. A third party who fails on
 * purpose from their own machine therefore does not block the account for its owner, while an
 * attack spread over many machines is still stopped.
 */
@Component
public class LoginThrottle {

    static final int ADDRESS_ONLY_FACTOR = 4;

    private final JdbcClient jdbc;
    private final Clock clock;

    LoginThrottle(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** When the address is blocked, the end of the block. */
    Optional<Instant> lockedUntil(String email, String ip) {
        return jdbc.sql("""
                select locked_until from login_throttle
                where key in (?, ?) and locked_until > ?
                order by locked_until desc limit 1
                """)
                .params(pairKey(email, ip), addressKey(email), Timestamp.from(clock.instant()))
                .query(Timestamp.class)
                .optional()
                .map(Timestamp::toInstant);
    }

    /**
     * Counts a failure.
     *
     * @return the attempts left before the block, 0 when the address has just been blocked
     */
    Failure recordFailure(String email, String ip, int maxAttempts, Duration lockout) {
        int pairFailures = increment(pairKey(email, ip), lockout);
        int addressFailures = increment(addressKey(email), lockout);
        boolean pairLocked = pairFailures >= maxAttempts;
        boolean addressLocked = addressFailures >= maxAttempts * ADDRESS_ONLY_FACTOR;
        Instant until = clock.instant().plus(lockout);
        if (pairLocked) {
            lock(pairKey(email, ip), until);
        }
        if (addressLocked) {
            lock(addressKey(email), until);
        }
        return new Failure(Math.max(0, maxAttempts - pairFailures), pairLocked || addressLocked, addressLocked);
    }

    /** After a successful sign-in, or a new password: both counters start again from zero. */
    public void clear(String email) {
        String pairPrefix = "pair:" + email + "|";
        jdbc.sql("delete from login_throttle where key = ? or left(key, ?) = ?")
                .params(addressKey(email), pairPrefix.length(), pairPrefix)
                .update();
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
    void purge() {
        Timestamp dayAgo = Timestamp.from(clock.instant().minus(Duration.ofDays(1)));
        jdbc.sql("delete from login_throttle where window_started_at < ? and (locked_until is null or locked_until < ?)")
                .params(dayAgo, Timestamp.from(clock.instant()))
                .update();
    }

    private int increment(String key, Duration lockout) {
        Timestamp now = Timestamp.from(clock.instant());
        Timestamp windowStart = Timestamp.from(clock.instant().minus(lockout));
        List<Integer> failures = jdbc.sql("""
                insert into login_throttle as t (key, failures, window_started_at, locked_until)
                values (:key, 1, :now, null)
                on conflict (key) do update set
                    failures = case when t.window_started_at < :windowStart or t.locked_until <= :now
                                    then 1 else t.failures + 1 end,
                    window_started_at = case when t.window_started_at < :windowStart or t.locked_until <= :now
                                    then :now else t.window_started_at end,
                    locked_until = case when t.locked_until <= :now then null else t.locked_until end
                returning failures
                """)
                .param("key", key)
                .param("now", now)
                .param("windowStart", windowStart)
                .query(Integer.class)
                .list();
        return failures.getFirst();
    }

    private void lock(String key, Instant until) {
        jdbc.sql("update login_throttle set locked_until = ? where key = ?")
                .params(Timestamp.from(until), key)
                .update();
    }

    private static String pairKey(String email, String ip) {
        return "pair:" + email + "|" + (ip == null ? "" : ip);
    }

    private static String addressKey(String email) {
        return "address:" + email;
    }

    record Failure(int remainingAttempts, boolean locked, boolean addressLocked) {
    }
}
