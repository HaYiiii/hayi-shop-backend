package vn.hayi.shop.shared.id;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class UuidV7Test {

	@Test
	void generatesVersion7Ids() {
		UUID id = UuidV7.generate();

		assertThat(id.version()).isEqualTo(7);
		assertThat(id.variant()).isEqualTo(2);
	}

	@Test
	void idsGeneratedLaterSortAfterEarlierOnesAsStoredBytes() {
		List<String> ids = new ArrayList<>();
		for (int i = 0; i < 1000; i++) {
			ids.add(UuidV7.generate().toString());
		}

		// Lower-case hex string order equals BINARY(16) byte order, which keeps InnoDB inserts append-only.
		assertThat(ids).isSorted().doesNotHaveDuplicates();
	}

}
