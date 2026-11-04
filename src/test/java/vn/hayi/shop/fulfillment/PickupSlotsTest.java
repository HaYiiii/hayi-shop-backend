package vn.hayi.shop.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import vn.hayi.shop.MutableClock;
import vn.hayi.shop.MutableClockConfiguration;
import vn.hayi.shop.TestcontainersConfiguration;
import vn.hayi.shop.branch.api.OpeningInterval;
import vn.hayi.shop.branch.application.BranchHoursService;
import vn.hayi.shop.branch.application.BranchService;
import vn.hayi.shop.branch.application.HourOverrideRequest;
import vn.hayi.shop.branch.application.NewBranch;
import vn.hayi.shop.branch.application.WeeklyIntervalRequest;
import vn.hayi.shop.fulfillment.api.OrderType;
import vn.hayi.shop.fulfillment.api.PickupSlot;
import vn.hayi.shop.fulfillment.api.PickupSlots;
import vn.hayi.shop.fulfillment.api.SlotCalendar;
import vn.hayi.shop.fulfillment.api.SlotDay;
import vn.hayi.shop.fulfillment.api.SlotQuery;
import vn.hayi.shop.fulfillment.api.SlotStatus;
import vn.hayi.shop.fulfillment.application.FulfillmentConfigService;
import vn.hayi.shop.fulfillment.application.FulfillmentRules;
import vn.hayi.shop.fulfillment.application.OverrideInput;
import vn.hayi.shop.fulfillment.application.PickupScheduleService;
import vn.hayi.shop.fulfillment.application.PickupWindowInput;
import vn.hayi.shop.fulfillment.application.ScheduleWindowInput;
import vn.hayi.shop.platform.persistence.UuidBinary;
import vn.hayi.shop.shared.actor.ActorContext;
import vn.hayi.shop.shared.concurrency.ExpectedVersion;
import vn.hayi.shop.shared.error.DomainException;
import vn.hayi.shop.shared.time.BranchTime;

/**
 * The slot resolver of {@code fulfillment.api} (Fulfillment §8.3–8.5, Decision N1–N4, T), with the clock frozen on
 * Monday 2030-01-07 10:00 Branch time. Config: 30-minute slots, same-day cutoff 180, lead 30, Group lead 120,
 * default capacity 10 orders / 20 items, 14 days ahead.
 */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, MutableClockConfiguration.class })
class PickupSlotsTest {

	static final ActorContext OWNER = ActorContext.staff(UUID.randomUUID(), null);

	static final LocalDate MONDAY = LocalDate.of(2030, 1, 7);

	static final LocalDate TUESDAY = MONDAY.plusDays(1);

	static final LocalDate SUNDAY = MONDAY.plusDays(6);

	static final LocalDate NEXT_MONDAY = MONDAY.plusDays(7);

	@Autowired
	PickupSlots slots;

	@Autowired
	BranchService branches;

	@Autowired
	BranchHoursService branchHours;

	@Autowired
	FulfillmentConfigService configs;

	@Autowired
	PickupScheduleService schedules;

	@Autowired
	MutableClock clock;

	@Autowired
	JdbcClient jdbc;

	UUID branchId;

	@BeforeEach
	void setUp() {
		this.clock.freezeAt(BranchTime.toInstant(MONDAY, at(10, 0), BranchTime.DEFAULT_ZONE));
		this.branchId = this.branches.create(new NewBranch("T-" + UUID.randomUUID().toString().substring(0, 8)
			.toUpperCase(), "HaYi test", "1 Lê Lợi", null, null, "TP.HCM", null, null, null, null), OWNER, null, null)
			.branchId();
		// Monday 07–14 (two touching ranges) and 16–21; Tuesday 07–21; nothing on Sunday.
		this.branchHours.replaceWeekly(this.branchId,
				List.of(new WeeklyIntervalRequest(1, at(7, 0), at(11, 0)),
						new WeeklyIntervalRequest(1, at(11, 0), at(14, 0)),
						new WeeklyIntervalRequest(1, at(16, 0), at(21, 0)),
						new WeeklyIntervalRequest(2, at(7, 0), at(21, 0))),
				new ExpectedVersion(0), OWNER, null, null);
		this.branches.activate(this.branchId, null, OWNER, null, null);
		configure(new FulfillmentRules(true, false, 14, 30, 180, 30, 120, null, 10, 20, null));
		this.schedules.replaceWeekly(this.branchId, "PICKUP",
				List.of(new ScheduleWindowInput(1, at(8, 0), at(12, 15), 4, null),
						new ScheduleWindowInput(1, at(16, 0), at(21, 0), null, null),
						new ScheduleWindowInput(2, at(9, 0), at(10, 45), null, null)),
				new ExpectedVersion(1), OWNER, null, null);
	}

	@AfterEach
	void resetClock() {
		this.clock.reset();
	}

	@Test
	void slotsStartAtEachWindowAndDropTheTail() {
		assertThat(starts(day(TUESDAY))).containsExactly("09:00", "09:30", "10:00");
		assertThat(starts(day(NEXT_MONDAY))).containsExactly("08:00", "08:30", "09:00", "09:30", "10:00", "10:30",
				"11:00", "11:30", "16:00", "16:30", "17:00", "17:30", "18:00", "18:30", "19:00", "19:30", "20:00",
				"20:30");
	}

	@Test
	void slotsOutsideThatDaysBranchHoursAreDropped() {
		// The Branch closes at 19:00 on that Monday; the pickup schedule stays as it was (Decision N4).
		this.branchHours.putOverride(this.branchId, new HourOverrideRequest(NEXT_MONDAY, false,
				List.of(new OpeningInterval(at(7, 0), at(19, 0))), null), null, OWNER, null, null);

		assertThat(starts(day(NEXT_MONDAY))).endsWith("17:30", "18:00", "18:30").doesNotContain("19:00");
	}

	@Test
	void overrideWinsOverWeeklyWithItsCapacity() {
		this.schedules.putOverride(this.branchId, "PICKUP", new OverrideInput(TUESDAY, false,
				List.of(new PickupWindowInput(at(10, 0), at(11, 0))), 2, null, "Lễ"), null, OWNER, null, null);

		SlotDay tuesday = day(TUESDAY);

		assertThat(starts(tuesday)).containsExactly("10:00", "10:30");
		assertThat(tuesday.slots()).allSatisfy(slot -> {
			assertThat(slot.maxOrders()).isEqualTo(2);
			assertThat(slot.maxItems()).isEqualTo(20);
		});
	}

	@Test
	void capacityOfWeeklyRowBeatsTheDefault() {
		SlotDay nextMonday = day(NEXT_MONDAY);

		assertThat(nextMonday.slots().getFirst().maxOrders()).isEqualTo(4);
		assertThat(nextMonday.slots().getLast().maxOrders()).isEqualTo(10);
	}

	@Test
	void closedOverrideAndDayWithoutScheduleAreClosed() {
		this.schedules.putOverride(this.branchId, "PICKUP", new OverrideInput(TUESDAY, true, List.of(), null, null,
				"Bảo trì"), null, OWNER, null, null);

		assertThat(day(TUESDAY).open()).isFalse();
		assertThat(day(TUESDAY).slots()).isEmpty();
		assertThat(day(SUNDAY).open()).isFalse();
	}

	@Test
	void sameDayCutoffAppliesToTodayOnly() {
		// 10:00 now, cutoff 180 → today's slots before 13:00 are past cutoff.
		SlotDay today = day(MONDAY);

		assertThat(today.slots()).filteredOn(slot -> slot.start().isBefore(at(13, 0)))
			.isNotEmpty()
			.allMatch(slot -> slot.status() == SlotStatus.CUTOFF_PASSED);
		assertThat(today.slots()).filteredOn(slot -> !slot.start().isBefore(at(16, 0)))
			.allMatch(slot -> slot.status() == SlotStatus.AVAILABLE);
		assertThat(day(TUESDAY).slots()).allMatch(slot -> slot.status() == SlotStatus.AVAILABLE);
	}

	@Test
	void minLeadAppliesToEveryDay() {
		configure(new FulfillmentRules(true, false, 14, 30, 0, 24 * 60, null, null, 10, 20, null));

		// Tuesday 09:00–10:30 starts less than 24 h after Monday 10:00; 10:00 is exactly 24 h away.
		assertThat(day(TUESDAY).slots()).extracting(PickupSlot::status)
			.containsExactly(SlotStatus.CUTOFF_PASSED, SlotStatus.CUTOFF_PASSED, SlotStatus.AVAILABLE);
	}

	@Test
	void groupOrdersUseTheGroupLead() {
		configure(new FulfillmentRules(true, false, 14, 30, 0, 30, 24 * 60, null, 10, 20, null));

		assertThat(day(TUESDAY, OrderType.GROUP, null).slots().getFirst().status())
			.isEqualTo(SlotStatus.CUTOFF_PASSED);
		assertThat(day(TUESDAY, OrderType.INDIVIDUAL, null).slots().getFirst().status())
			.isEqualTo(SlotStatus.AVAILABLE);
	}

	@Test
	void fullAndNearlyFullComeFromSlotCapacity() {
		// Tuesday 09:00: 10/10 orders; 09:30: 16/20 items; 10:00: no row yet.
		seedUsage(TUESDAY, at(9, 0), at(9, 30), 10, 10);
		seedUsage(TUESDAY, at(9, 30), at(10, 0), 1, 16);

		assertThat(day(TUESDAY).slots()).extracting(PickupSlot::status)
			.containsExactly(SlotStatus.FULL, SlotStatus.NEARLY_FULL, SlotStatus.AVAILABLE);
		assertThat(day(TUESDAY).slots().get(1).usedItems()).isEqualTo(16);
		// 4 items left, the customer wants 5.
		assertThat(day(TUESDAY, OrderType.INDIVIDUAL, 5).slots().get(1).status()).isEqualTo(SlotStatus.FULL);
	}

	@Test
	void cutoffWinsOverFull() {
		seedUsage(MONDAY, at(8, 0), at(8, 30), 4, 0);

		assertThat(day(MONDAY).slots().getFirst().status()).isEqualTo(SlotStatus.CUTOFF_PASSED);
	}

	@Test
	void disabledPickupClosesEveryDay() {
		configure(new FulfillmentRules(false, false, 14, 30, 180, 30, 120, null, 10, 20, null));

		SlotCalendar calendar = this.slots.resolve(new SlotQuery(this.branchId, null, 7, OrderType.INDIVIDUAL, null));

		assertThat(calendar.days()).hasSize(7).allSatisfy(day -> {
			assertThat(day.open()).isFalse();
			assertThat(day.slots()).isEmpty();
		});
	}

	@Test
	void defaultsAreTodayAndSevenDays() {
		SlotCalendar calendar = this.slots.resolve(new SlotQuery(this.branchId, null, null, null, null));

		assertThat(calendar.days()).extracting(SlotDay::date).first().isEqualTo(MONDAY);
		assertThat(calendar.days()).hasSize(7);
	}

	@Test
	void horizonIsMaxAdvanceDaysIncludingToday() {
		assertThat(this.slots.resolve(new SlotQuery(this.branchId, MONDAY, 14, null, null)).days()).hasSize(14);
		assertCode(() -> this.slots.resolve(new SlotQuery(this.branchId, MONDAY, 15, null, null)), "VALIDATION_FAILED");
		assertCode(() -> this.slots.resolve(new SlotQuery(this.branchId, MONDAY.plusDays(10), 5, null, null)),
				"VALIDATION_FAILED");
		assertCode(() -> this.slots.resolve(new SlotQuery(this.branchId, MONDAY.minusDays(1), 2, null, null)),
				"VALIDATION_FAILED");
		assertCode(() -> this.slots.resolve(new SlotQuery(this.branchId, MONDAY, 0, null, null)), "VALIDATION_FAILED");
	}

	@Test
	void defaultDaysStayInsideAShortHorizon() {
		configure(new FulfillmentRules(true, false, 3, 30, 180, 30, null, null, 10, 20, null));

		assertThat(this.slots.resolve(new SlotQuery(this.branchId, null, null, null, null)).days()).hasSize(3);
		assertCode(() -> this.slots.resolve(new SlotQuery(this.branchId, null, 4, null, null)), "VALIDATION_FAILED");
	}

	@Test
	void noBookableDayGivesNoDays() {
		configure(new FulfillmentRules(true, false, 0, 30, 180, 30, null, null, 10, 20, null));

		assertThat(this.slots.resolve(new SlotQuery(this.branchId, null, null, null, null)).days()).isEmpty();
		assertCode(() -> this.slots.resolve(new SlotQuery(this.branchId, null, 1, null, null)), "VALIDATION_FAILED");
	}

	@Test
	void inactiveOrUnknownBranchIsNotFound() {
		this.branches.deactivate(this.branchId, null, OWNER, null, null);

		assertCode(() -> day(TUESDAY), "BRANCH_NOT_FOUND");
		assertCode(() -> this.slots.resolve(new SlotQuery(UUID.randomUUID(), null, null, null, null)),
				"BRANCH_NOT_FOUND");
	}

	private void configure(FulfillmentRules rules) {
		long version = this.configs.list(this.branchId).getFirst().version();
		this.configs.replace(this.branchId, "PICKUP", rules, new ExpectedVersion(version), OWNER, null, null);
	}

	private void seedUsage(LocalDate date, LocalTime start, LocalTime end, int orders, int items) {
		this.jdbc.sql("""
				INSERT INTO ful_slot_capacity (id, branch_id, fulfillment_type, slot_date, slot_start_time,
				  slot_end_time, used_orders, used_items, updated_at)
				VALUES (?, ?, 'PICKUP', ?, ?, ?, ?, ?, UTC_TIMESTAMP(6))
				""")
			.params(UuidBinary.toBytes(UUID.randomUUID()), UuidBinary.toBytes(this.branchId), date, start.toString(),
					end.toString(), orders, items)
			.update();
	}

	private SlotDay day(LocalDate date) {
		return day(date, OrderType.INDIVIDUAL, null);
	}

	private SlotDay day(LocalDate date, OrderType orderType, Integer itemCount) {
		return this.slots.resolve(new SlotQuery(this.branchId, date, 1, orderType, itemCount)).days().getFirst();
	}

	private static List<String> starts(SlotDay day) {
		return day.slots().stream().map(slot -> slot.start().toString()).toList();
	}

	private static void assertCode(Runnable call, String code) {
		assertThatThrownBy(call::run).isInstanceOfSatisfying(DomainException.class,
				ex -> assertThat(ex.code().name()).isEqualTo(code));
	}

	private static LocalTime at(int hour, int minute) {
		return LocalTime.of(hour, minute);
	}

}
