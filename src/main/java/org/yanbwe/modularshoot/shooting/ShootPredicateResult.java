package org.yanbwe.modularshoot.shooting;

import org.jetbrains.annotations.Nullable;

/**
 * Result of a shoot predicate check.
 *
 * <p>A {@link #success()} result signals that the checked condition is
 * satisfied and the shot may proceed. A {@link #failure(String)} result carries
 * a human-readable reason that the framework surfaces to the player (on the
 * action bar) when the shot is aborted (设计文档 §射击条件判断).</p>
 *
 * <p>This type is immutable and null-hostile: factory methods never return a
 * {@code null} result. On a successful result {@link #getReason()} returns
 * {@code null}; on a failing result it returns the non-null reason string.</p>
 *
 * <p>Implemented as a final class rather than a record so that the required
 * accessor names {@code isSuccess()} and {@code getReason()} are the sole
 * public accessors, avoiding the redundant auto-generated record accessors
 * that would arise from naming the fields {@code success}/{@code reason}.</p>
 */
public final class ShootPredicateResult {

    private final boolean success;
    private final @Nullable String reason;

    private ShootPredicateResult(boolean success, @Nullable String reason) {
        this.success = success;
        this.reason = reason;
    }

    /**
     * Factory for a passing shoot predicate result.
     *
     * @return a new {@link ShootPredicateResult} with {@code success = true}
     *         and no reason
     */
    public static ShootPredicateResult success() {
        return new ShootPredicateResult(true, null);
    }

    /**
     * Factory for a failing shoot predicate result.
     *
     * @param reason the human-readable reason shown to the player on the
     *               action bar when the shot is aborted; must not be
     *               {@code null}
     * @return a new {@link ShootPredicateResult} with {@code success = false}
     *         and the given reason
     */
    public static ShootPredicateResult failure(String reason) {
        if (reason == null) {
            throw new NullPointerException("reason");
        }
        return new ShootPredicateResult(false, reason);
    }

    /**
     * Tells whether the predicate check passed.
     *
     * @return {@code true} when the shot may proceed; {@code false} when it
     *         should be aborted
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * Returns the failure reason.
     *
     * @return the human-readable reason when {@link #isSuccess()} is
     *         {@code false}; {@code null} on a successful result
     */
    public @Nullable String getReason() {
        return reason;
    }
}
