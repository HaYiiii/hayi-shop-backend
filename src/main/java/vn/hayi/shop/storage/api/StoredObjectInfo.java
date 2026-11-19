package vn.hayi.shop.storage.api;

import java.util.UUID;

/**
 * What a business context needs to know about a stored file (Decision AF3). Only an {@code ACTIVE} object may be
 * linked; {@code publicUrl} is set only for an {@code ACTIVE} {@code PUBLIC} one.
 */
public record StoredObjectInfo(UUID storedObjectId, Status status, Visibility visibility, Purpose purpose,
		String publicUrl) {

	public boolean isActive() {
		return this.status == Status.ACTIVE;
	}

	/** Storage §18.4. */
	public enum Status {

		PENDING_UPLOAD, UPLOADED, ACTIVE, QUARANTINED, ARCHIVED, DELETION_PENDING, DELETED, FAILED

	}

	/** Storage §18.7. */
	public enum Visibility {

		PUBLIC, PRIVATE, RESTRICTED

	}

	/** API 06 §1; S2 has only the image purposes. */
	public enum Purpose {

		PRODUCT_IMAGE, CATEGORY_IMAGE

	}

}
