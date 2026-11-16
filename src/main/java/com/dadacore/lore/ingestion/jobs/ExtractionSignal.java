package com.dadacore.lore.ingestion.jobs;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.stereotype.Component;

/** Wakes the idle workers when a job arrives, instead of waiting for their next look. */
@Component
public class ExtractionSignal {

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition arrived = lock.newCondition();
    private long generation;

    public void wake() {
        lock.lock();
        try {
            generation++;
            arrived.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** Read before looking at the queue, then given to {@link #await}: a job arriving meanwhile is not missed. */
    long generation() {
        lock.lock();
        try {
            return generation;
        } finally {
            lock.unlock();
        }
    }

    /** Waits for a new job since {@code seen}, or for the time given, whichever comes first. */
    void await(long seen, long millis) throws InterruptedException {
        lock.lock();
        try {
            long remaining = TimeUnit.MILLISECONDS.toNanos(millis);
            while (generation == seen && remaining > 0) {
                remaining = arrived.awaitNanos(remaining);
            }
        } finally {
            lock.unlock();
        }
    }
}
