package vn.hayi.shop.storage.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.hayi.shop.shared.error.CommonErrorCode;
import vn.hayi.shop.shared.error.DomainException;
import vn.hayi.shop.storage.domain.ImageType;
import vn.hayi.shop.storage.domain.StorageErrorCode;
import vn.hayi.shop.storage.domain.StoredObject;
import vn.hayi.shop.storage.domain.StoredObjectRepository;
import vn.hayi.shop.storage.domain.StoredObjectStatus;
import vn.hayi.shop.storage.domain.UploadPurpose;
import vn.hayi.shop.storage.domain.Visibility;

/**
 * Upload intent and finalize (Storage §18.5–18.6, API 06 §1, Decision G8, AF1–AF2): the browser sends the bytes
 * straight to object storage, then the server checks them. No business Audit: the context that links the file
 * audits that (AF4).
 */
@Service
public class UploadService {

	private final StoredObjectRepository objects;

	private final ObjectStore store;

	private final Clock clock;

	UploadService(StoredObjectRepository objects, ObjectStore store, Clock clock) {
		this.objects = objects;
		this.store = store;
		this.clock = clock;
	}

	/** A new intent with its pre-signed {@code PUT}; the web layer has checked the request against the policy. */
	@Transactional
	public UploadIntent createIntent(String purpose, String fileName, String contentType, long sizeBytes,
			String checksumSha256, UUID userId) {
		UploadPurpose uploadPurpose = UploadPurpose.valueOf(purpose);
		ImageType type = ImageType.ofMimeType(contentType)
			.orElseThrow(() -> new IllegalArgumentException("Not an accepted type: " + contentType));
		Instant now = this.clock.instant();
		StoredObject object = StoredObject.intent(uploadPurpose, this.store.provider(), this.store.publicBucket(),
				fileName, type, sizeBytes, checksumSha256, userId, now);
		this.objects.saveAndFlush(object);
		ObjectStore.PresignedPut put = this.store.presignPut(object.getBucket(), object.getObjectKey(),
				type.mimeType(), StoredObject.UPLOAD_TTL);
		return new UploadIntent(object.getId(), put.url().toString(), put.headers(), object.getUploadExpiresAt(),
				uploadPurpose.maxSizeBytes(),
				uploadPurpose.allowedTypes().stream().map(ImageType::mimeType).toList());
	}

	/**
	 * Checks the uploaded bytes (Decision AF2). A failed check is kept as {@code FAILED} although the request gets an
	 * error, so this transaction commits on a {@link DomainException}.
	 */
	@Transactional(noRollbackFor = DomainException.class)
	public FinalizedUpload finalizeUpload(UUID storedObjectId, UUID userId) {
		StoredObject object = this.objects.findById(storedObjectId)
			.orElseThrow(() -> new DomainException(StorageErrorCode.UPLOAD_NOT_FOUND,
					"No stored object " + storedObjectId));
		if (!object.getUploadedByUserId().equals(userId)) {
			throw new DomainException(CommonErrorCode.PERMISSION_DENIED,
					"Only the user who asked for upload " + storedObjectId + " may finalize it");
		}
		object.requirePendingUpload();
		ObjectStore.StoredBytes stored = this.store
			.read(object.getBucket(), object.getObjectKey(), object.getPurpose().maxSizeBytes())
			.orElseThrow(() -> new DomainException(StorageErrorCode.UPLOAD_NOT_FOUND_IN_STORAGE,
					"Nothing uploaded yet for " + storedObjectId));
		try {
			object.finalizeWith(stored.size(), (stored.bytes() != null) ? stored.bytes() : new byte[0],
					this.clock.instant());
		}
		finally {
			this.objects.saveAndFlush(object);
		}
		return FinalizedUpload.of(object, publicUrl(object));
	}

	private String publicUrl(StoredObject object) {
		return (object.getStatus() == StoredObjectStatus.ACTIVE && object.getVisibility() == Visibility.PUBLIC)
				? this.store.publicUrl(object.getBucket(), object.getObjectKey()) : null;
	}

	public record UploadIntent(UUID storedObjectId, String url, Map<String, String> headers, Instant expiresAt,
			long maxSizeBytes, List<String> allowedMimeTypes) {
	}

	public record FinalizedUpload(UUID storedObjectId, String status, String detectedMimeType, long sizeBytes,
			Integer width, Integer height, String publicUrl) {

		static FinalizedUpload of(StoredObject object, String publicUrl) {
			return new FinalizedUpload(object.getId(), object.getStatus().name(), object.getDetectedMimeType(),
					object.getSizeBytes(), object.getWidth(), object.getHeight(), publicUrl);
		}

	}

}
