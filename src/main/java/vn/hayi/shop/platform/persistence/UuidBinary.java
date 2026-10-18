package vn.hayi.shop.platform.persistence;

import java.nio.ByteBuffer;
import java.util.UUID;

/**
 * {@link UUID} ↔ {@code BINARY(16)} for {@code JdbcClient} SQL (ERD README §2.2). JPA maps UUIDs through
 * {@code hibernate.type.preferred_uuid_jdbc_type}; plain JDBC needs the bytes explicitly.
 */
public final class UuidBinary {

	private UuidBinary() {
	}

	public static byte[] toBytes(UUID uuid) {
		if (uuid == null) {
			return null;
		}
		return ByteBuffer.allocate(16).putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits()).array();
	}

	public static UUID fromBytes(byte[] bytes) {
		if (bytes == null) {
			return null;
		}
		ByteBuffer buffer = ByteBuffer.wrap(bytes);
		return new UUID(buffer.getLong(), buffer.getLong());
	}

}
