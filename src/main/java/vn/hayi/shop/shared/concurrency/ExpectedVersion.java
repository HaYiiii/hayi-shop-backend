package vn.hayi.shop.shared.concurrency;

import vn.hayi.shop.shared.error.CommonErrorCode;
import vn.hayi.shop.shared.error.DomainException;

/**
 * The version of an aggregate the caller based an edit on, from {@code If-Match} (API README §8). An edit applies
 * only if it is still the current version; a concurrent edit that commits first makes the later one fail its
 * optimistic lock, which also means {@code VERSION_CONFLICT}.
 */
public record ExpectedVersion(long value) {

	/** @throws DomainException {@code VERSION_CONFLICT} when {@code current} is not the expected version */
	public void verify(long current) {
		if (current != this.value) {
			throw new DomainException(CommonErrorCode.VERSION_CONFLICT,
					"Expected version " + this.value + " but is " + current);
		}
	}

}
