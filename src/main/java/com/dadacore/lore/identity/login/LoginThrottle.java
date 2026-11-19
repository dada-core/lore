package com.dadacore.lore.identity.login;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import com.dadacore.lore.audit.AuditEntry;
import com.dadacore.lore.audit.AuditLog;
import com.dadacore.lore.identity.organization.OrganizationSettings;
import com.dadacore.lore.identity.security.Tokens;
import com.dadacore.lore.shared.ApiException;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
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
 *
 * <p>The current password asked again to change one's address or password counts on the same
 * counters, and on a third one, of the session. It is refused while the address is blocked from the
 * IP of the request, or once the session has given as many wrong current passwords as the sign-in
 * allows: a session left open, or stolen, gives no more guesses than the sign-in form, from one
 * machine or from many. A block from everywhere, which anyone can cause by failing on purpose, does
 * not keep the person signed in from changing their password.
 *
 * <p>The keys of the counters hold the SHA-256 digests of the address and of the IP, in
 * hexadecimal: their length is fixed whatever a request sends. These digests are not secret: an IPv4
 * address, or a known e-mail address, is found again from its digest, and the audit log keeps both
 * anyway. Keys written by an earlier version no longer match and are purged after a day.
 *
 * <p>The checks of a password for one address run one at a time ({@link #oneAtATime}): many guesses
 * sent together get no more than guesses sent one by one. A block already in place is never pushed
 * back by a later failure.
 */
@Component
public class LoginThrottle {

    static final int ADDRESS_ONLY_FACTOR = 4;
    private static final int STRIPES = 64;
    private static final Duration TURN_WAIT = Duration.ofSeconds(10);

    private final JdbcClient jdbc;
    private final AuditLog audit;
    private final Clock clock;
    private final ReentrantLock[] stripes = new ReentrantLock[STRIPES];

    LoginThrottle(JdbcClient jdbc, AuditLog audit, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
        for (int i = 0; i < STRIPES; i++) {
            stripes[i] = new ReentrantLock();
        }
    }

    /**
     * Runs a check of a password for this address once the checks before it are over: the block is
     * read, the password computed and a failure counted without another check of the same address in
     * between. Otherwise, guesses sent together would all pass the block before the first failure is
     * counted. The turn is kept within one instance of Lore; an address that waits more than 10
     * seconds for it gets {@code 503 server_busy}.
     */
    public <T> T oneAtATime(String email, Supplier<T> check) {
        ReentrantLock turn = stripes[Math.floorMod(email.hashCode(), STRIPES)];
        boolean acquired;
        try {
            acquired = turn.tryLock(TURN_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            acquired = false;
        }
        if (!acquired) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "server_busy");
        }
        try {
            return check.get();
        } finally {
            turn.unlock();
        }
    }

    /** When the address is blocked, from this IP or from everywhere, the end of the block. */
    public Optional<Instant> lockedUntil(String email, String ip) {
        return lockedUntil(List.of(pairKey(email, ip), addressKey(email)));
    }

    /**
     * When the current password may no longer be given in this session, the end of the block: the
     * address blocked from this IP, or the session blocked by its own wrong current passwords.
     */
    public Optional<Instant> lockedForSessionUntil(String email, String ip, String sessionId) {
        return lockedUntil(List.of(pairKey(email, ip), sessionKey(sessionId)));
    }

    private Optional<Instant> lockedUntil(List<String> keys) {
        return jdbc.sql("""
                select locked_until from login_throttle
                where key in (:keys) and locked_until > :now
                order by locked_until desc limit 1
                """)
                .param("keys", keys)
                .param("now", Timestamp.from(clock.instant()))
                .query(Timestamp.class)
                .optional()
                .map(Timestamp::toInstant);
    }

    /** The whole minutes left before the end of a block, at least one. */
    public long minutesUntil(Instant until) {
        long seconds = Duration.between(clock.instant(), until).toSeconds();
        return Math.max(1, (seconds + 59) / 60);
    }

    /**
     * Counts a wrong password given for this address, and writes it in the audit log, with the
     * block it causes, if any.
     *
     * @param actorId   the signed-in person who gave it again in their account, null at sign-in
     * @param accountId the account of this address, null when none has it
     * @return the attempts left before the block, 0 when the address has just been blocked
     */
    public Failure recordFailure(UUID organizationId, OrganizationSettings settings, @Nullable UUID actorId,
            @Nullable UUID accountId, String email, String ip) {
        return recordFailure(organizationId, settings, actorId, accountId, email, ip, null);
    }

    /**
     * As at sign-in, and on the counter of the session when the current password was given again in
     * one's account.
     *
     * @param sessionId the session that gave it, in one's account; null at sign-in
     */
    public Failure recordFailure(UUID organizationId, OrganizationSettings settings, @Nullable UUID actorId,
            @Nullable UUID accountId, String email, String ip, @Nullable String sessionId) {
        Failure failure = count(email, ip, sessionId, settings.loginMaxAttempts(),
                Duration.ofMinutes(settings.loginLockoutMinutes()));
        audit.record(AuditEntry.of(organizationId, actorId, "auth.login_failed")
                .resource("account", accountId)
                .ip(ip)
                .detail("email", email)
                .build());
        if (failure.locked() || failure.sessionLocked()) {
            audit.record(AuditEntry.of(organizationId, actorId, "auth.locked")
                    .resource("account", accountId)
                    .ip(ip)
                    .detail("email", email)
                    .detail("minutes", settings.loginLockoutMinutes())
                    .detail("scope", failure.addressLocked() ? "address" : failure.pairLocked() ? "address_and_ip" : "session")
                    .build());
        }
        return failure;
    }

    /** The answer to a blocked address: {@code 429 login_locked} with {@code retryAfterMinutes}. */
    public static ApiException locked(long retryAfterMinutes) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "login_locked")
                .with("retryAfterMinutes", retryAfterMinutes);
    }

    /**
     * After the right current password given in one's account: the counters of its IP and of its
     * session start again from zero. A block from everywhere stays, until the end of the attack that
     * caused it.
     */
    public void clearForSession(String email, String ip, String sessionId) {
        jdbc.sql("delete from login_throttle where key in (:keys)")
                .param("keys", List.of(pairKey(email, ip), sessionKey(sessionId)))
                .update();
    }

    /** After a successful sign-in, or a new password: both counters start again from zero. */
    public void clear(String email) {
        String pairPrefix = pairPrefix(email);
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

    private Failure count(String email, String ip, @Nullable String sessionId, int maxAttempts, Duration lockout) {
        String pairKey = pairKey(email, ip);
        String addressKey = addressKey(email);
        int pairFailures = increment(pairKey, lockout);
        int addressFailures = increment(addressKey, lockout);
        int sessionFailures = sessionId == null ? 0 : increment(sessionKey(sessionId), lockout);
        boolean pairLocked = pairFailures >= maxAttempts;
        boolean sessionLocked = sessionId != null && sessionFailures >= maxAttempts;
        Instant until = clock.instant().plus(lockout);
        if (pairLocked) {
            lock(pairKey, until);
        }
        // During a block from everywhere, a wrong current password given in one's account still
        // counts, but neither pushes the block back nor blocks the address a second time.
        boolean addressLocked = addressFailures >= maxAttempts * ADDRESS_ONLY_FACTOR && lock(addressKey, until);
        if (sessionLocked) {
            lock(sessionKey(sessionId), until);
        }
        return new Failure(Math.max(0, maxAttempts - pairFailures), pairLocked, addressLocked, sessionLocked);
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

    /** Blocks the key until then, unless it is blocked already. True when this call blocked it. */
    private boolean lock(String key, Instant until) {
        return jdbc.sql("update login_throttle set locked_until = :until"
                        + " where key = :key and (locked_until is null or locked_until <= :now)")
                .param("until", Timestamp.from(until))
                .param("key", key)
                .param("now", Timestamp.from(clock.instant()))
                .update() == 1;
    }

    /** {@code pair:} then the digests of the address and of the IP: 134 characters. */
    private static String pairKey(String email, String ip) {
        return pairPrefix(email) + sha256(ip == null ? "" : ip);
    }

    private static String pairPrefix(String email) {
        return "pair:" + sha256(email) + ":";
    }

    /** {@code address:} then the digest of the address: 72 characters. */
    private static String addressKey(String email) {
        return "address:" + sha256(email);
    }

    /** {@code session:} then the digest of the session identifier: 72 characters. */
    private static String sessionKey(String sessionId) {
        return "session:" + sha256(sessionId);
    }

    private static String sha256(String text) {
        return HexFormat.of().formatHex(Tokens.hash(text));
    }

    /**
     * @param remainingAttempts attempts left before the block, from this IP
     * @param pairLocked        the address is now blocked from this IP
     * @param addressLocked     the address is now blocked from everywhere, by this failure
     * @param sessionLocked     the session may no longer give the current password
     */
    public record Failure(int remainingAttempts, boolean pairLocked, boolean addressLocked, boolean sessionLocked) {

        /** The address is blocked, from this IP at least: the sign-in refuses it. */
        public boolean locked() {
            return pairLocked || addressLocked;
        }
    }
}
