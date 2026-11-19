package vn.hayi.shop.storage.domain;

/** Technical lifecycle of a stored object (Storage §18.4). */
public enum StoredObjectStatus {

	PENDING_UPLOAD, UPLOADED, ACTIVE, QUARANTINED, ARCHIVED, DELETION_PENDING, DELETED, FAILED

}
