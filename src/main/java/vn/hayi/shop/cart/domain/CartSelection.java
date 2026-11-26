package vn.hayi.shop.cart.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * The identity of a Cart Item (Cart & Pricing §6.3): Product + Variant + the chosen options + the normalized note;
 * {@code quantity} is not part of it.
 * @param options the chosen items with their groups, without repeats
 * @param note already normalized ({@link #normalizeNote}), {@code null} when none
 */
public record CartSelection(UUID productId, UUID variantId, List<CartItemOption> options, String note) {

	/** Longest normalized note (ERD 06 {@code note_normalized}, Decision AV2). */
	public static final int MAX_NOTE_LENGTH = 255;

	public CartSelection {
		options = List.copyOf(options);
	}

	/** Strips the note and collapses inner whitespace to one space; blank → {@code null} (Decision AV2). */
	public static String normalizeNote(String note) {
		if (note == null) {
			return null;
		}
		String normalized = note.strip().replaceAll("\\s+", " ");
		return normalized.isEmpty() ? null : normalized;
	}

	/** SHA-256 hex of {@code product|variant|sorted option item ids|note}, the same whatever order the items came in. */
	public String equivalenceKey() {
		StringBuilder canonical = new StringBuilder().append(this.productId).append('|').append(this.variantId).append('|');
		canonical.append(String.join(",", this.options.stream()
			.map(CartItemOption::optionItemId)
			.sorted(Comparator.comparing(UUID::toString))
			.map(UUID::toString)
			.toList()));
		canonical.append('|').append((this.note != null) ? this.note : "");
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256")
					.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is always available", ex);
		}
	}

}
