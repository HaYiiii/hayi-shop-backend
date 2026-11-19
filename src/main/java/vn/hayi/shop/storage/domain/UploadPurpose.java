package vn.hayi.shop.storage.domain;

import java.util.List;

/**
 * What a file is for, with its upload policy (API 06 §1): visibility, size limit, accepted types and the key prefix.
 * S2 takes only the two image purposes (Decision AF1); evidence purposes join with the slice that needs them.
 */
public enum UploadPurpose {

	PRODUCT_IMAGE("product"), CATEGORY_IMAGE("category");

	/** 5 MB (API 06 §1). */
	public static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;

	private final String keyPrefix;

	UploadPurpose(String keyPrefix) {
		this.keyPrefix = keyPrefix;
	}

	public Visibility visibility() {
		return Visibility.PUBLIC;
	}

	public long maxSizeBytes() {
		return MAX_IMAGE_BYTES;
	}

	public List<ImageType> allowedTypes() {
		return List.of(ImageType.values());
	}

	String keyPrefix() {
		return this.keyPrefix;
	}

}
