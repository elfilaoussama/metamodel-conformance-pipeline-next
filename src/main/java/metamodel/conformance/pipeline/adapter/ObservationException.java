package metamodel.conformance.pipeline.adapter;

public final class ObservationException extends Exception {
    public ObservationException(String message) {
        super(message);
    }

    public ObservationException(String message, Throwable cause) {
        super(message, cause);
    }
}
