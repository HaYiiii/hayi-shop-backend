package vn.hayi.shop.fulfillment.web.publicapi;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import vn.hayi.shop.fulfillment.api.OrderType;
import vn.hayi.shop.fulfillment.api.PickupSlots;
import vn.hayi.shop.fulfillment.api.SlotCalendar;
import vn.hayi.shop.fulfillment.api.SlotQuery;
import vn.hayi.shop.platform.web.InvalidFieldsException;
import vn.hayi.shop.platform.web.spec.publicapi.api.FulfillmentAvailabilityApi;
import vn.hayi.shop.platform.web.spec.publicapi.model.PickupSlotsDto;
import vn.hayi.shop.platform.web.spec.publicapi.model.SlotDayDto;
import vn.hayi.shop.platform.web.spec.publicapi.model.SlotDto;
import vn.hayi.shop.platform.web.spec.publicapi.model.SlotStatusDto;
import vn.hayi.shop.shared.time.BranchTime;

/**
 * Public pickup slots (API 02 §3, Decision T): status only, never capacity numbers, and never cached since capacity
 * changes with every order.
 */
@RestController
class PublicPickupSlotsController implements FulfillmentAvailabilityApi {

	private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

	private final PickupSlots slots;

	PublicPickupSlotsController(PickupSlots slots) {
		this.slots = slots;
	}

	@Override
	public ResponseEntity<PickupSlotsDto> listPickupSlots(UUID branchId, String fulfillmentType, LocalDate fromDate,
			Integer days, String orderType, Integer itemCount) {
		SlotCalendar calendar = this.slots
			.resolve(new SlotQuery(branchId, fromDate, days, orderType(orderType, fulfillmentType), itemCount));
		List<SlotDayDto> dayDtos = calendar.days().stream()
			.map(day -> new SlotDayDto(day.date(), day.open(), day.slots().stream()
				.map(slot -> new SlotDto(slot.start().format(HH_MM), slot.end().format(HH_MM),
						SlotStatusDto.valueOf(slot.status().name())))
				.toList()))
			.toList();
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(new PickupSlotsDto(branchId, PickupSlotsDto.FulfillmentTypeEnum.PICKUP,
					BranchTime.DEFAULT_ZONE.getId(), false, dayDtos));
	}

	/** The generated contract leaves these enums as text; anything else is a malformed request (400). */
	private static OrderType orderType(String orderType, String fulfillmentType) {
		List<InvalidFieldsException.Violation> violations = new ArrayList<>();
		if (!"PICKUP".equals(fulfillmentType)) {
			violations.add(new InvalidFieldsException.Violation("fulfillmentType", "NOT_ALLOWED"));
		}
		OrderType type = null;
		try {
			type = OrderType.valueOf(orderType);
		}
		catch (IllegalArgumentException ex) {
			violations.add(new InvalidFieldsException.Violation("orderType", "NOT_ALLOWED"));
		}
		if (!violations.isEmpty()) {
			throw new InvalidFieldsException(violations);
		}
		return type;
	}

}
