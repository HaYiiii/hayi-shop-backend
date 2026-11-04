package vn.hayi.shop.branch;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.jayway.jsonpath.JsonPath;
import vn.hayi.shop.MutableClock;
import vn.hayi.shop.MutableClockConfiguration;
import vn.hayi.shop.TestcontainersConfiguration;
import vn.hayi.shop.branch.api.OpeningInterval;
import vn.hayi.shop.branch.application.BranchHoursService;
import vn.hayi.shop.branch.application.BranchService;
import vn.hayi.shop.branch.application.HourOverrideRequest;
import vn.hayi.shop.branch.application.NewBranch;
import vn.hayi.shop.branch.application.WeeklyIntervalRequest;
import vn.hayi.shop.fulfillment.application.FulfillmentConfigService;
import vn.hayi.shop.fulfillment.application.FulfillmentRules;
import vn.hayi.shop.fulfillment.application.PickupScheduleService;
import vn.hayi.shop.fulfillment.application.ScheduleWindowInput;
import vn.hayi.shop.platform.persistence.UuidBinary;
import vn.hayi.shop.shared.actor.ActorContext;
import vn.hayi.shop.shared.concurrency.ExpectedVersion;
import vn.hayi.shop.shared.time.BranchTime;

/**
 * Customers browse ACTIVE Branches without signing in (API 02 §1, Decision N5, U). Clock frozen on Monday 2030-01-07,
 * Branch time. The Branch opens Monday 07–14 (two touching ranges) and 16–21, Tuesday 07–21; PICKUP windows Monday
 * 16–21 and Tuesday 09–10, 30-minute slots, cutoff 180, lead 30.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MutableClockConfiguration.class })
class PublicBranchesTest {

	static final String BRANCHES = "/api/public/v1/branches";

	static final ActorContext OWNER = ActorContext.staff(UUID.randomUUID(), null);

	static final LocalDate MONDAY = LocalDate.of(2030, 1, 7);

	@Autowired
	MockMvc mvc;

	@Autowired
	BranchService branches;

	@Autowired
	BranchHoursService hours;

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
		at(MONDAY, 10, 0);
		this.branchId = activeBranchWithPickup();
	}

	@AfterEach
	void resetClock() {
		this.clock.reset();
	}

	@Test
	void onlyActiveBranchesAreListedAndShown() throws Exception {
		UUID draft = newBranch();
		UUID inactive = activeBranchWithPickup();
		this.branches.deactivate(inactive, null, OWNER, null, null);
		UUID closed = newBranch();
		this.jdbc.sql("UPDATE brn_branch SET status = 'CLOSED' WHERE id = ?").param(UuidBinary.toBytes(closed)).update();

		List<String> listed = JsonPath.read(get(BRANCHES).getContentAsString(), "$.items[*].branchId");

		assertThat(listed).contains(this.branchId.toString())
			.doesNotContain(draft.toString(), inactive.toString(), closed.toString());
		List<String> codes = JsonPath.read(get(BRANCHES).getContentAsString(), "$.items[*].branchCode");
		assertThat(codes).isSorted();
		for (UUID hidden : List.of(draft, inactive, closed, UUID.randomUUID())) {
			assertProblem(get(BRANCHES + "/" + hidden), 404, "BRANCH_NOT_FOUND");
		}
	}

	@Test
	void openNowInsideTodaysHours() throws Exception {
		String item = detail();

		assertThat(JsonPath.<Boolean>read(item, "$.openNow")).isTrue();
		assertThat(JsonPath.<List<String>>read(item, "$.todayHours[*].open")).containsExactly("07:00", "11:00", "16:00");
		assertThat(JsonPath.<String>read(item, "$.timezone")).isEqualTo("Asia/Ho_Chi_Minh");
		assertThat(JsonPath.<List<String>>read(item, "$.fulfillmentTypes")).containsExactly("PICKUP");
	}

	@Test
	void closedDuringTheBreakAndAtClosingTime() throws Exception {
		at(MONDAY, 15, 0);
		assertThat(JsonPath.<Boolean>read(detail(), "$.openNow")).isFalse();
		at(MONDAY, 21, 0);
		assertThat(JsonPath.<Boolean>read(detail(), "$.openNow")).isFalse();
	}

	@Test
	void closedOnAnOverrideDay() throws Exception {
		this.hours.putOverride(this.branchId, new HourOverrideRequest(MONDAY, true, List.of(), "Bảo trì"), null, OWNER,
				null, null);

		String item = detail();

		assertThat(JsonPath.<Boolean>read(item, "$.openNow")).isFalse();
		assertThat(JsonPath.<List<Object>>read(item, "$.todayHours")).isEmpty();
	}

	@Test
	void earliestPickupIsTodayWhenStillBookable() throws Exception {
		// 10:00 + 180 min cutoff → Monday 16:00 is the first bookable slot (09:00 UTC).
		assertThat(JsonPath.<String>read(detail(), "$.earliestPickupAt")).isEqualTo("2030-01-07T09:00:00Z");
		assertThat(JsonPath.<List<String>>read(get(BRANCHES).getContentAsString(),
				"$.items[?(@.branchId == '" + this.branchId + "')].earliestPickupAt"))
			.containsExactly("2030-01-07T09:00:00Z");
	}

	@Test
	void earliestPickupIsTomorrowOnceTodayIsPastCutoff() throws Exception {
		at(MONDAY, 19, 0);

		// Tuesday 09:00 Branch time = 02:00 UTC.
		assertThat(JsonPath.<String>read(detail(), "$.earliestPickupAt")).isEqualTo("2030-01-08T02:00:00Z");
	}

	@Test
	void noBookableSlotOrPickupDisabledGivesNoEarliestPickup() throws Exception {
		this.schedules.replaceWeekly(this.branchId, "PICKUP", List.of(), new ExpectedVersion(2), OWNER, null, null);
		assertThat(JsonPath.<Object>read(detail(), "$.earliestPickupAt")).isNull();
		assertThat(JsonPath.<List<String>>read(detail(), "$.fulfillmentTypes")).containsExactly("PICKUP");

		this.configs.replace(this.branchId, "PICKUP",
				new FulfillmentRules(false, false, 7, 30, 180, 30, null, null, null, null, null), new ExpectedVersion(3),
				OWNER, null, null);

		assertThat(JsonPath.<Object>read(detail(), "$.earliestPickupAt")).isNull();
		assertThat(JsonPath.<List<String>>read(detail(), "$.fulfillmentTypes")).isEmpty();
	}

	@Test
	void detailHasWeeklyHoursAndTheNextSevenDaysOverridesWithoutReason() throws Exception {
		this.hours.putOverride(this.branchId, new HourOverrideRequest(MONDAY.plusDays(6), false,
				List.of(new OpeningInterval(LocalTime.of(8, 0), LocalTime.of(12, 0))), "Nội bộ: thiếu người"), null,
				OWNER, null, null);
		this.hours.putOverride(this.branchId, new HourOverrideRequest(MONDAY.plusDays(7), true, List.of(), null), null,
				OWNER, null, null);

		String item = detail();

		assertThat(JsonPath.<List<Integer>>read(item, "$.weeklyHours[*].dayOfWeek")).containsExactly(1, 1, 1, 2);
		assertThat(JsonPath.<List<Map<String, Object>>>read(item, "$.overrides")).containsExactly(Map.of("date",
				MONDAY.plusDays(6).toString(), "isClosed", false, "intervals",
				List.of(Map.of("open", "08:00", "close", "12:00"))));
		assertThat(item).doesNotContain("reason", "Nội bộ", "email");
	}

	@Test
	void responsesAreCachedFiveMinutesAndNeedNoCookie() throws Exception {
		for (MockHttpServletResponse response : List.of(get(BRANCHES), get(BRANCHES + "/" + this.branchId))) {
			assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
			assertThat(response.getHeader("Cache-Control")).contains("max-age=300", "public");
		}
	}

	private UUID activeBranchWithPickup() {
		UUID id = newBranch();
		this.hours.replaceWeekly(id,
				List.of(new WeeklyIntervalRequest(1, LocalTime.of(7, 0), LocalTime.of(11, 0)),
						new WeeklyIntervalRequest(1, LocalTime.of(11, 0), LocalTime.of(14, 0)),
						new WeeklyIntervalRequest(1, LocalTime.of(16, 0), LocalTime.of(21, 0)),
						new WeeklyIntervalRequest(2, LocalTime.of(7, 0), LocalTime.of(21, 0))),
				new ExpectedVersion(0), OWNER, null, null);
		this.branches.activate(id, null, OWNER, null, null);
		this.configs.replace(id, "PICKUP",
				new FulfillmentRules(true, false, 7, 30, 180, 30, null, null, null, null, null), new ExpectedVersion(0),
				OWNER, null, null);
		this.schedules.replaceWeekly(id, "PICKUP",
				List.of(new ScheduleWindowInput(1, LocalTime.of(16, 0), LocalTime.of(21, 0), null, null),
						new ScheduleWindowInput(2, LocalTime.of(9, 0), LocalTime.of(10, 0), null, null)),
				new ExpectedVersion(1), OWNER, null, null);
		return id;
	}

	private UUID newBranch() {
		return this.branches.create(new NewBranch("T-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
				"HaYi test", "1 Lê Lợi", null, null, "TP.HCM", null, null, "02838000000", "branch@hayi.test"), OWNER,
				null, null)
			.branchId();
	}

	private void at(LocalDate date, int hour, int minute) {
		this.clock.freezeAt(BranchTime.toInstant(date, LocalTime.of(hour, minute), BranchTime.DEFAULT_ZONE));
	}

	private String detail() throws Exception {
		MockHttpServletResponse response = get(BRANCHES + "/" + this.branchId);
		assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
		return response.getContentAsString();
	}

	private MockHttpServletResponse get(String path) throws Exception {
		return this.mvc.perform(MockMvcRequestBuilders.get(path))
			.andReturn()
			.getResponse();
	}

	private static void assertProblem(MockHttpServletResponse response, int status, String code) throws Exception {
		assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
		assertThat(JsonPath.<String>read(response.getContentAsString(), "$.code")).isEqualTo(code);
	}

}
