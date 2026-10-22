package dev.traction.demo.governed;

/**
 * What {@link AcmeClient} throws, one type per way an Acme call can fail. The reporter maps each
 * to the level it breaks. {@code status} is the HTTP status, or 0 when no response arrived.
 */
public final class AcmeExceptions {

    private AcmeExceptions() { }

    public abstract static sealed class AcmeException extends RuntimeException
            permits AuthException, ContractException, BadPayloadException, RateLimitedException,
                    VendorUnavailableException {

        private final int status;

        AcmeException(int status, String detail) {
            super(status == 0 ? detail : status + " " + detail);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    /** 401: the credentials are dead. */
    public static final class AuthException extends AcmeException {
        public AuthException(int status, String detail) { super(status, detail); }
    }

    /** 422: the endpoint rejects the request shape (a contract change on Acme's side). */
    public static final class ContractException extends AcmeException {
        public ContractException(int status, String detail) { super(status, detail); }
    }

    /** 400: this one record's data was rejected. */
    public static final class BadPayloadException extends AcmeException {
        public BadPayloadException(int status, String detail) { super(status, detail); }
    }

    /** 429: Acme is throttling us. */
    public static final class RateLimitedException extends AcmeException {
        public RateLimitedException(int status, String detail) { super(status, detail); }
    }

    /** 5xx, timeout or connection failure: Acme is (briefly) unavailable. */
    public static final class VendorUnavailableException extends AcmeException {
        public VendorUnavailableException(int status, String detail) { super(status, detail); }
    }
}
