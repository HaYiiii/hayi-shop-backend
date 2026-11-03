package vn.hayi.shop.shared.error;

import java.util.Map;
import java.util.Objects;

/**
 * A business rule refused the operation. Carries a stable {@link ErrorCode} and no HTTP status: the web layer
 * derives the status from {@link ErrorCode#kind()} (API README §6).
 * <p>
 * The message is for logs only and never reaches the client. The client-facing detail is looked up as
 * {@code error.<CODE>.detail} and formatted with {@link #detailArgs()}.
 */
public class DomainException extends RuntimeException {

	private final ErrorCode code;

	private final Object[] detailArgs;

	public DomainException(ErrorCode code, String message, Object... detailArgs) {
		super(message);
		this.code = Objects.requireNonNull(code, "code");
		this.detailArgs = detailArgs.clone();
	}

	public ErrorCode code() {
		return this.code;
	}

	public Object[] detailArgs() {
		return this.detailArgs.clone();
	}

	/**
	 * Structured data the client needs besides {@code code}, added to the Problem Details as extra members (API
	 * README §6), e.g. {@code conflicts[]} or {@code issues[]}. Values must be JSON-serializable. None by default.
	 */
	public Map<String, Object> problemProperties() {
		return Map.of();
	}

}
