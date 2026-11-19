package vn.hayi.shop.storage.domain;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import vn.hayi.shop.shared.error.DomainException;
import vn.hayi.shop.shared.id.UuidV7;

/**
 * Metadata of one object whose bytes live in object storage (Storage §18.2, ERD 16 {@code sto_stored_object}). Its
 * meaning (a Product image…) belongs to the business context that links it (§18.3).
 */
@Entity
@Table(name = "sto_stored_object")
public class StoredObject {

	/** How long the pre-signed upload and the intent last (API 06 §1, Decision AF1). */
	public static final Duration UPLOAD_TTL = Duration.ofMinutes(10);

	@Id
	private UUID id;

	@Column(updatable = false)
	private String provider;

	@Column(updatable = false)
	private String bucket;

	@Column(updatable = false)
	private String objectKey;

	@Enumerated(EnumType.STRING)
	@Column(updatable = false)
	private UploadPurpose purpose;

	private String originalFileName;

	/** The type the client declared until finalize, then the type its bytes show. */
	private String detectedMimeType;

	private Long sizeBytes;

	/** The client's declared checksum until finalize, which must match the bytes (AF2). */
	private String checksumSha256;

	private Integer width;

	private Integer height;

	@Enumerated(EnumType.STRING)
	private StoredObjectStatus status;

	@Enumerated(EnumType.STRING)
	private Visibility visibility;

	@Column(updatable = false)
	private UUID uploadedByUserId;

	private Instant uploadExpiresAt;

	private Instant finalizedAt;

	@Column(updatable = false)
	private Instant createdAt;

	private Instant updatedAt;

	@Version
	private long version;

	protected StoredObject() {
	}

	/**
	 * A {@code PENDING_UPLOAD} intent (Decision AF1); the web layer has checked the declared type, size and checksum
	 * against the purpose's policy.
	 */
	public static StoredObject intent(UploadPurpose purpose, String provider, String bucket, String fileName,
			ImageType declaredType, long declaredSize, String checksumSha256, UUID userId, Instant now) {
		StoredObject object = new StoredObject();
		object.id = UuidV7.generate();
		ZonedDateTime utc = now.atZone(ZoneOffset.UTC);
		object.provider = provider;
		object.bucket = bucket;
		object.objectKey = "%s/%04d/%02d/%s.%s".formatted(purpose.keyPrefix(), utc.getYear(), utc.getMonthValue(),
				object.id, declaredType.extension());
		object.purpose = purpose;
		object.originalFileName = fileName;
		object.detectedMimeType = declaredType.mimeType();
		object.sizeBytes = declaredSize;
		object.checksumSha256 = checksumSha256.toLowerCase(Locale.ROOT);
		object.status = StoredObjectStatus.PENDING_UPLOAD;
		object.visibility = purpose.visibility();
		object.uploadedByUserId = userId;
		object.uploadExpiresAt = now.plus(UPLOAD_TTL);
		object.createdAt = now;
		object.updatedAt = now;
		return object;
	}

	/** Only a {@code PENDING_UPLOAD} intent can be finalized (AF2). */
	public void requirePendingUpload() {
		if (this.status != StoredObjectStatus.PENDING_UPLOAD) {
			throw new DomainException(StorageErrorCode.UPLOAD_ALREADY_FINALIZED,
					"Stored object " + this.id + " is " + this.status);
		}
	}

	/**
	 * Checks the uploaded bytes in the order of Decision AF2 and returns the new status. A failed check moves the
	 * intent to {@code FAILED} before throwing, so the caller must keep that change.
	 * @param actualSize the size storage reports
	 * @param bytes the object's bytes, read only when {@code actualSize} is within the limit
	 * @throws DomainException {@code UPLOAD_TOO_LARGE}, {@code UPLOAD_MIME_MISMATCH}, {@code UPLOAD_CHECKSUM_MISMATCH}
	 */
	public StoredObjectStatus finalizeWith(long actualSize, byte[] bytes, Instant now) {
		requirePendingUpload();
		if (actualSize > this.purpose.maxSizeBytes()) {
			throw fail(StorageErrorCode.UPLOAD_TOO_LARGE, now);
		}
		Optional<ImageType> detected = ImageType.detect(bytes);
		if (detected.isEmpty() || !detected.get().mimeType().equals(this.detectedMimeType)
				|| !this.purpose.allowedTypes().contains(detected.get())) {
			throw fail(StorageErrorCode.UPLOAD_MIME_MISMATCH, now);
		}
		if (!sha256(bytes).equals(this.checksumSha256)) {
			throw fail(StorageErrorCode.UPLOAD_CHECKSUM_MISMATCH, now);
		}
		Optional<ImageDimensions> dimensions = ImageDimensions.read(detected.get(), bytes);
		this.sizeBytes = actualSize;
		this.width = dimensions.map(ImageDimensions::width).orElse(null);
		this.height = dimensions.map(ImageDimensions::height).orElse(null);
		this.status = dimensions.isPresent() ? StoredObjectStatus.ACTIVE : StoredObjectStatus.QUARANTINED;
		this.finalizedAt = now;
		this.updatedAt = now;
		return this.status;
	}

	/** {@code ACTIVE → ARCHIVED} when the business context drops its link; again is a no-op (Decision W7, AF3). */
	public boolean archive(Instant now) {
		if (this.status == StoredObjectStatus.ARCHIVED) {
			return false;
		}
		if (this.status != StoredObjectStatus.ACTIVE) {
			throw new IllegalStateException("Only an ACTIVE object can be archived, " + this.id + " is " + this.status);
		}
		this.status = StoredObjectStatus.ARCHIVED;
		this.updatedAt = now;
		return true;
	}

	private DomainException fail(StorageErrorCode code, Instant now) {
		this.status = StoredObjectStatus.FAILED;
		this.finalizedAt = now;
		this.updatedAt = now;
		return new DomainException(code, code + " for stored object " + this.id);
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is always available", ex);
		}
	}

	public UUID getId() {
		return this.id;
	}

	public String getBucket() {
		return this.bucket;
	}

	public String getObjectKey() {
		return this.objectKey;
	}

	public UploadPurpose getPurpose() {
		return this.purpose;
	}

	public String getDetectedMimeType() {
		return this.detectedMimeType;
	}

	public Long getSizeBytes() {
		return this.sizeBytes;
	}

	public Integer getWidth() {
		return this.width;
	}

	public Integer getHeight() {
		return this.height;
	}

	public StoredObjectStatus getStatus() {
		return this.status;
	}

	public Visibility getVisibility() {
		return this.visibility;
	}

	public UUID getUploadedByUserId() {
		return this.uploadedByUserId;
	}

	public Instant getUploadExpiresAt() {
		return this.uploadExpiresAt;
	}

}
