package org.hormigas.ws.domain.conversation.result;

/** A membership-guarded result: the {@link GuardedOutcome} plus a value (non-null only on OK). */
public record Guarded<T>(GuardedOutcome outcome, T value) {
    public static <T> Guarded<T> ok(T value) { return new Guarded<>(GuardedOutcome.OK, value); }
    public static <T> Guarded<T> notFound() { return new Guarded<>(GuardedOutcome.NOT_FOUND, null); }
    public static <T> Guarded<T> forbidden() { return new Guarded<>(GuardedOutcome.FORBIDDEN, null); }
}
