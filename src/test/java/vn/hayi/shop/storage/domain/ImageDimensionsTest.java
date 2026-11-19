package vn.hayi.shop.storage.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

/** Image type and size are read from the header bytes only (Storage §18.6, Decision AF2, AF4). */
class ImageDimensionsTest {

	@Test
	void webpLossyLosslessAndExtendedHeadersGiveTheirSize() {
		byte[] lossy = riff("VP8 ", new byte[] { 0, 0, 0, (byte) 0x9D, 0x01, 0x2A, 0x40, 0x01, (byte) 0xF0, 0x00 });
		byte[] lossless = riff("VP8L", new byte[] { 0x2F, (byte) ((320 - 1) & 0xFF),
				(byte) (((320 - 1) >> 8) | (((240 - 1) & 0x03) << 6)), (byte) ((240 - 1) >> 2), 0 });
		byte[] extended = riff("VP8X", new byte[] { 0, 0, 0, 0, (byte) 0x3F, 0x01, 0, (byte) 0xEF, 0x00, 0 });

		assertThat(ImageType.detect(lossy)).contains(ImageType.WEBP);
		assertThat(ImageDimensions.read(ImageType.WEBP, lossy)).contains(new ImageDimensions(320, 240));
		assertThat(ImageDimensions.read(ImageType.WEBP, lossless)).contains(new ImageDimensions(320, 240));
		assertThat(ImageDimensions.read(ImageType.WEBP, extended)).contains(new ImageDimensions(320, 240));
	}

	@Test
	void aTruncatedOrUnknownHeaderHasNoSize() {
		byte[] lossy = riff("VP8 ", new byte[] { 0, 0, 0, (byte) 0x9D, 0x01, 0x2A, 0x40, 0x01, (byte) 0xF0, 0x00 });

		assertThat(ImageDimensions.read(ImageType.WEBP, Arrays.copyOf(lossy, 25))).isEmpty();
		assertThat(ImageDimensions.read(ImageType.WEBP, riff("ABCD", new byte[10]))).isEmpty();
		assertThat(ImageDimensions.read(ImageType.JPEG, new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF })).isEmpty();
		assertThat(ImageType.detect(new byte[] { 'G', 'I', 'F', '8' })).isEmpty();
	}

	private static byte[] riff(String chunk, byte[] payload) {
		byte[] bytes = new byte[20 + payload.length];
		System.arraycopy("RIFF".getBytes(), 0, bytes, 0, 4);
		System.arraycopy("WEBP".getBytes(), 0, bytes, 8, 4);
		System.arraycopy(chunk.getBytes(), 0, bytes, 12, 4);
		System.arraycopy(payload, 0, bytes, 20, payload.length);
		return bytes;
	}

}
