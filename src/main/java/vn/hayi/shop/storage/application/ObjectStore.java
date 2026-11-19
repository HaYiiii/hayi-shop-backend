package vn.hayi.shop.storage.application;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/** The object storage provider (S3-compatible, Decision H5, AF4), as the Storage use cases need it. */
public interface ObjectStore {

	/** Provider name stored with each object ({@code sto_stored_object.provider}). */
	String provider();

	/** The bucket for {@code PUBLIC} objects. */
	String publicBucket();

	/** A {@code PUT} the browser sends the bytes to, signed with {@code contentType}, valid for {@code ttl}. */
	PresignedPut presignPut(String bucket, String key, String contentType, Duration ttl);

	/**
	 * The object's size and, when it is at most {@code maxBytes}, its bytes; empty when there is no such object.
	 */
	Optional<StoredBytes> read(String bucket, String key, long maxBytes);

	/** The fixed URL of a {@code PUBLIC} object (Decision AF3). */
	String publicUrl(String bucket, String key);

	record PresignedPut(URI url, Map<String, String> headers) {
	}

	/** {@code bytes} is {@code null} when {@code size} is over the limit asked for. */
	record StoredBytes(long size, byte[] bytes) {
	}

}
