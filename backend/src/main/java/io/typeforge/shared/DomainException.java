package io.typeforge.shared;

/** Safe, stable boundary error. Never put SQL, credentials or private content in its message. */
public final class DomainException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String code;
    private final int status;

    public DomainException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }
    public String code() { return code; }
    public int status() { return status; }
    public static DomainException missing() {
        return new DomainException("NOT_FOUND", 404, "Resource is unavailable in this scope");
    }
    public static DomainException conflict(String message) {
        return new DomainException("VERSION_CONFLICT", 409, message);
    }
    public static DomainException invalid(String message) {
        return new DomainException("INVALID_INPUT", 400, message);
    }
}
