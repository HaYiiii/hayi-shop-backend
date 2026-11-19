package vn.hayi.shop.storage.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * An accepted image type, told apart by its magic bytes, never by the client's file name or type (Storage §18.6).
 */
public enum ImageType {

	JPEG("image/jpeg", "jpg"), PNG("image/png", "png"), WEBP("image/webp", "webp");

	private final String mimeType;

	private final String extension;

	ImageType(String mimeType, String extension) {
		this.mimeType = mimeType;
		this.extension = extension;
	}

	public String mimeType() {
		return this.mimeType;
	}

	String extension() {
		return this.extension;
	}

	public static Optional<ImageType> ofMimeType(String mimeType) {
		return Arrays.stream(values()).filter(type -> type.mimeType.equals(mimeType)).findFirst();
	}

	/** The type the first bytes show, if any accepted one. */
	public static Optional<ImageType> detect(byte[] bytes) {
		if (startsWith(bytes, 0, 0xFF, 0xD8, 0xFF)) {
			return Optional.of(JPEG);
		}
		if (startsWith(bytes, 0, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
			return Optional.of(PNG);
		}
		if (startsWith(bytes, 0, 'R', 'I', 'F', 'F') && startsWith(bytes, 8, 'W', 'E', 'B', 'P')) {
			return Optional.of(WEBP);
		}
		return Optional.empty();
	}

	static boolean startsWith(byte[] bytes, int offset, int... expected) {
		if (bytes.length < offset + expected.length) {
			return false;
		}
		for (int i = 0; i < expected.length; i++) {
			if ((bytes[offset + i] & 0xFF) != expected[i]) {
				return false;
			}
		}
		return true;
	}

}
