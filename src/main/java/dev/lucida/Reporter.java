package dev.lucida;

import java.util.function.Consumer;

/** Debug logging and the error callback, shared by the event and flag paths. Never throws. */
final class Reporter {
    private static final System.Logger LOG = System.getLogger("dev.lucida");
    private final boolean debug;
    private final Consumer<Throwable> onError;

    Reporter(boolean debug, Consumer<Throwable> onError) {
        this.debug = debug;
        this.onError = onError;
    }

    void debug(String msg) {
        if (debug) LOG.log(System.Logger.Level.INFO, "[lucida] " + msg);
    }

    void error(String msg, Throwable cause) {
        debug(msg + (cause == null ? "" : ": " + cause));
        if (onError == null) return;
        try {
            onError.accept(cause != null ? cause : new IllegalStateException(msg));
        } catch (RuntimeException ignored) {
            // a failing callback must not break delivery
        }
    }
}
