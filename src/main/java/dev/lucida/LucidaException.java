package dev.lucida;

/** Raised by {@link Lucida#importEvents} when the import cannot start or the server rejects a chunk. */
public final class LucidaException extends RuntimeException {
    public LucidaException(String message) {
        super(message);
    }
}
