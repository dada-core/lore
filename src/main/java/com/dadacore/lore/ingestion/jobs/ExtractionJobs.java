package com.dadacore.lore.ingestion.jobs;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The table {@code extraction_job}. A worker takes a job with a lease; the result is recorded only
 * if the lease still holds, so that a job taken over after an expiry is never recorded twice.
 */
@Repository
public class ExtractionJobs {

    /** A job taken by a worker. */
    record Job(UUID versionId, UUID organizationId, String sha256, String mediaType, int attempts) {
    }

    private final JdbcClient jdbc;

    public ExtractionJobs(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A new job, or the same one again from the start (a new attempt asked for by a person). */
    public void add(UUID versionId, UUID organizationId, String sha256, String mediaType) {
        jdbc.sql("""
                insert into extraction_job (version_id, organization_id, sha256, media_type)
                values (?, ?, ?, ?)
                on conflict (version_id) do update set status = 'PENDING', attempts = 0, run_after = now(),
                    lease = null, locked_until = null, last_error = null
                """)
                .params(versionId, organizationId, sha256, mediaType)
                .update();
    }

    /**
     * The next job due, or one whose lease expired (its worker stopped without a word): taken
     * with {@code lease} until {@code duration} has passed.
     */
    Optional<Job> take(UUID lease, Duration duration) {
        return jdbc.sql("""
                update extraction_job j
                set status = 'RUNNING', attempts = j.attempts + 1, lease = ?,
                    locked_until = now() + make_interval(secs => ?)
                where j.version_id = (
                    select version_id from extraction_job
                    where (status = 'PENDING' and run_after <= now()) or (status = 'RUNNING' and locked_until < now())
                    order by run_after, created_at
                    limit 1
                    for update skip locked)
                returning j.version_id, j.organization_id, j.sha256, j.media_type, j.attempts
                """)
                .params(lease, (double) duration.toSeconds())
                .query((rs, row) -> new Job(rs.getObject("version_id", UUID.class),
                        rs.getObject("organization_id", UUID.class), rs.getString("sha256"),
                        rs.getString("media_type"), rs.getInt("attempts")))
                .optional();
    }

    /** Ends a job; false when its lease was lost or the document deleted meanwhile. */
    boolean finish(UUID versionId, UUID lease) {
        return jdbc.sql("delete from extraction_job where version_id = ? and lease = ?")
                .params(versionId, lease)
                .update() > 0;
    }

    /** Puts a job back in the queue for a new attempt after {@code delay}. */
    void postpone(UUID versionId, UUID lease, Duration delay, String error) {
        jdbc.sql("""
                update extraction_job set status = 'PENDING', run_after = now() + make_interval(secs => ?),
                    lease = null, locked_until = null, last_error = ?
                where version_id = ? and lease = ?
                """)
                .params((double) delay.toMillis() / 1000, error, versionId, lease)
                .update();
    }

    /** At a normal stop: the job goes back to the queue, without counting the attempt. */
    void release(UUID lease) {
        jdbc.sql("""
                update extraction_job set status = 'PENDING', attempts = greatest(attempts - 1, 0),
                    lease = null, locked_until = null, run_after = now()
                where lease = ? and status = 'RUNNING'
                """)
                .param(lease)
                .update();
    }
}
