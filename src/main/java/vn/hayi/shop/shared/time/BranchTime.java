package vn.hayi.shop.shared.time;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * Conversions between stored instants (UTC) and a Branch's operational date/time (API README §4).
 * Read "now" from an injected {@link java.time.Clock}, never from the system clock directly.
 */
public final class BranchTime {

	/** Operational timezone of every Branch today. */
	public static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

	private BranchTime() {
	}

	public static LocalDate localDate(Instant instant, ZoneId branchZone) {
		return LocalDate.ofInstant(instant, branchZone);
	}

	public static Instant toInstant(LocalDate date, LocalTime time, ZoneId branchZone) {
		return date.atTime(time).atZone(branchZone).toInstant();
	}

}
