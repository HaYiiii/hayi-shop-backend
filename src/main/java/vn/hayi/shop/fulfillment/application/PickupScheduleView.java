package vn.hayi.shop.fulfillment.application;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/** Weekly windows and overrides from today on, with the config version for {@code ETag} (Decision S4). */
public record PickupScheduleView(long version, List<ScheduleWindowInput> weekly, List<Override> overrides,
		List<OverbookedSlotView> overbookedSlots) {

	/** One override; {@code start}/{@code end} are {@code null} for a closed day. */
	public record Override(UUID overrideId, LocalDate date, boolean isClosed, LocalTime start, LocalTime end,
			Integer maxOrders, Integer maxItems, String reason) {
	}

}
