package vn.hayi.shop.storage.domain;

import java.util.Optional;

/**
 * Width and height read from an image header, without decoding the image (Storage §18.6, Decision AF4). Empty when
 * the header cannot be read: the file is broken or not what its magic bytes say, so it is quarantined (AF2).
 */
public record ImageDimensions(int width, int height) {

	public static Optional<ImageDimensions> read(ImageType type, byte[] bytes) {
		try {
			Optional<ImageDimensions> found = switch (type) {
				case PNG -> png(bytes);
				case JPEG -> jpeg(bytes);
				case WEBP -> webp(bytes);
			};
			return found.filter(dimensions -> dimensions.width > 0 && dimensions.height > 0);
		}
		catch (ArrayIndexOutOfBoundsException ex) {
			return Optional.empty();
		}
	}

	/** {@code IHDR} is the first chunk: width and height as big-endian ints at 16 and 20. */
	private static Optional<ImageDimensions> png(byte[] bytes) {
		if (!ImageType.startsWith(bytes, 12, 'I', 'H', 'D', 'R')) {
			return Optional.empty();
		}
		return Optional.of(new ImageDimensions(int32(bytes, 16), int32(bytes, 20)));
	}

	/** Walks the markers to the first start-of-frame ({@code SOF0}–{@code SOF15} but DHT, JPG, DAC). */
	private static Optional<ImageDimensions> jpeg(byte[] bytes) {
		int at = 2;
		while (at + 9 < bytes.length) {
			if ((bytes[at] & 0xFF) != 0xFF) {
				return Optional.empty();
			}
			int marker = bytes[at + 1] & 0xFF;
			if (marker == 0xFF) {
				at++;
				continue;
			}
			int length = uint16(bytes, at + 2);
			boolean startOfFrame = marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8
					&& marker != 0xCC;
			if (startOfFrame) {
				return Optional.of(new ImageDimensions(uint16(bytes, at + 7), uint16(bytes, at + 5)));
			}
			if (marker == 0xD9 || marker == 0xDA || length < 2) {
				return Optional.empty();
			}
			at += 2 + length;
		}
		return Optional.empty();
	}

	/** The first chunk: lossy {@code VP8 }, lossless {@code VP8L} or extended {@code VP8X}. */
	private static Optional<ImageDimensions> webp(byte[] bytes) {
		if (ImageType.startsWith(bytes, 12, 'V', 'P', '8', 'X')) {
			return Optional.of(new ImageDimensions(uint24le(bytes, 24) + 1, uint24le(bytes, 27) + 1));
		}
		if (ImageType.startsWith(bytes, 12, 'V', 'P', '8', 'L') && (bytes[20] & 0xFF) == 0x2F) {
			int bits = (bytes[21] & 0xFF) | (bytes[22] & 0xFF) << 8 | (bytes[23] & 0xFF) << 16
					| (bytes[24] & 0xFF) << 24;
			return Optional.of(new ImageDimensions((bits & 0x3FFF) + 1, ((bits >> 14) & 0x3FFF) + 1));
		}
		if (ImageType.startsWith(bytes, 12, 'V', 'P', '8', ' ') && ImageType.startsWith(bytes, 23, 0x9D, 0x01, 0x2A)) {
			return Optional.of(new ImageDimensions(uint16le(bytes, 26) & 0x3FFF, uint16le(bytes, 28) & 0x3FFF));
		}
		return Optional.empty();
	}

	private static int int32(byte[] bytes, int at) {
		return (bytes[at] & 0xFF) << 24 | (bytes[at + 1] & 0xFF) << 16 | (bytes[at + 2] & 0xFF) << 8
				| (bytes[at + 3] & 0xFF);
	}

	private static int uint16(byte[] bytes, int at) {
		return (bytes[at] & 0xFF) << 8 | (bytes[at + 1] & 0xFF);
	}

	private static int uint16le(byte[] bytes, int at) {
		return (bytes[at] & 0xFF) | (bytes[at + 1] & 0xFF) << 8;
	}

	private static int uint24le(byte[] bytes, int at) {
		return (bytes[at] & 0xFF) | (bytes[at + 1] & 0xFF) << 8 | (bytes[at + 2] & 0xFF) << 16;
	}

}
