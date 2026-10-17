package vn.hayi.shop.shared.id;

import java.util.UUID;

import com.github.f4b6a3.uuid.UuidCreator;

/**
 * Generates aggregate IDs in the application: time-ordered UUIDv7, stored as {@code BINARY(16)}
 * (ERD README §2.2) so inserts stay append-only in the primary key index.
 */
public final class UuidV7 {

	private UuidV7() {
	}

	public static UUID generate() {
		return UuidCreator.getTimeOrderedEpoch();
	}

}
