package vn.hayi.shop.shared.patch;

import java.util.function.Function;

/**
 * One field of a partial edit (JSON Merge Patch, API README §3): either {@link Keep} the current value, or
 * {@link Set} it — possibly to {@code null}, which clears an optional field.
 */
public sealed interface Change<T> {

	static <T> Change<T> keep() {
		return new Keep<>();
	}

	static <T> Change<T> set(T value) {
		return new Set<>(value);
	}

	/** The new value when set, otherwise {@code current}. */
	default T applyTo(T current) {
		return (this instanceof Set<T> set) ? set.value() : current;
	}

	default <R> Change<R> map(Function<T, R> mapper) {
		return (this instanceof Set<T> set) ? new Set<>((set.value() != null) ? mapper.apply(set.value()) : null)
				: keep();
	}

	record Keep<T>() implements Change<T> {
	}

	record Set<T>(T value) implements Change<T> {
	}

}
