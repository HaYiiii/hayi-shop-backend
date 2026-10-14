package vn.hayi.shop.shared.error;

/**
 * Stable, client-facing error code ({@code UPPER_SNAKE_CASE}, prefixed by module, e.g. {@code ORDER_NOT_FOUND}).
 * Each module declares its codes as an enum implementing this interface (API README §6).
 */
public interface ErrorCode {

	/** The stable code exposed as Problem Details {@code code}. Enum constants satisfy this via {@link Enum#name()}. */
	String name();

	ErrorKind kind();

}
