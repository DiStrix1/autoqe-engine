package com.qe.agent.model;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CancellationToken - a lightweight wrapper around {@link AtomicBoolean} that
 * allows the WebSocket cancel message handler to signal the running pipeline to
 * stop at the next safe checkpoint.
 *
 * <p>Improvement #14: the pipeline checks {@link #isCancelled()} after each
 * Maven execution phase and before each LLM repair call. When cancelled, it
 * cleans up the test file and returns a {@code CANCELLED} status response.
 */
public class CancellationToken {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    /** Signals the token as cancelled. Idempotent. */
    public void cancel() {
        cancelled.set(true);
    }

    /** Returns {@code true} if {@link #cancel()} has been called. */
    public boolean isCancelled() {
        return cancelled.get();
    }
}
