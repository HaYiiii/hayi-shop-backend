package vn.hayi.shop.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.jayway.jsonpath.JsonPath;
import vn.hayi.shop.MutableClock;
import vn.hayi.shop.MutableClockConfiguration;
import vn.hayi.shop.TestcontainersConfiguration;
import vn.hayi.shop.branch.application.BranchHoursService;
import vn.hayi.shop.branch.application.BranchService;
import vn.hayi.shop.branch.application.NewBranch;
import vn.hayi.shop.branch.application.WeeklyIntervalRequest;
import vn.hayi.shop.fulfillment.api.PickupSlots;
import vn.hayi.shop.fulfillment.api.SlotQuery;
import vn.hayi.shop.fulfillment.application.FulfillmentConfigService;
import vn.hayi.shop.fulfillment.application.FulfillmentRules;
import vn.hayi.shop.fulfillment.application.PickupScheduleService;
import vn.hayi.shop.fulfillment.application.ScheduleWindowInput;
import vn.hayi.shop.identity.BackofficeAuth;
import vn.hayi.shop.identity.BackofficeAuth.Session;
import vn.hayi.shop.identity.IdentityFixtures;
import vn.hayi.shop.platform.persistence.UuidBinary;
import vn.hayi.shop.shared.actor.ActorContext;
import vn.hayi.shop.shared.concurrency.ExpectedVersion;
import vn.hayi.shop.shared.time.BranchTime;

/**
 * Staff read real slot numbers, and editors are warned of overbooked slots when capacity drops below what is claimed
 * (Fulfillment §8.5, API 09 §2.3, Decision V). The Branch opens 07–21 every day; PICKUP 09–11 every day in 30-minute
 * slots, 4 orders per slot from the weekly row, items from the default 20; no cutoff or lead, so only capacity
 * decides the status of future slots.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MutableClockConfiguration.class, IdentityFixtures.class })
class BackofficeCapacityTest {

	static final String BRANCHES = "/api/backoffice/v1/branches";

	static final ActorContext OWNER = ActorContext.staff(UUID.randomUUID(), null);

	@Autowired
	MockMvc mvc;

	@Autowired
	IdentityFixtures fixtures;

	@Autowired
	MutableClock clock;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	BranchService branches;

	@Autowired
	BranchHoursService hours;

	@Autowired
	FulfillmentConfigService configs;

	@Autowired
	PickupScheduleService schedules;

	@Autowired
	PickupSlots slots;

	BackofficeAuth auth;

	Session admin;

	UUID branchId;

	LocalDate today;

	LocalDate tomorrow;

	@BeforeEach
	void setUp() {
		this.auth = new BackofficeAuth(this.mvc, this.clock);
		this.admin = this.fixtures.openSession(this.fixtures.storeAdmin(IdentityFixtures.uniqueEmail()));
		this.today = LocalDate.ofInstant(this.clock.instant(), BranchTime.DEFAULT_ZONE);
		this.tomorrow = this.today.plusDays(1);
		this.branchId = configuredBranch();
		seed(this.tomorrow, at(9, 0), at(9, 30), 3, 5);
	}

	@Test
	void capacityShowsRealNumbersPerSlot() throws Exception {
		MockHttpServletResponse capacity = capacity(this.admin, this.branchId, "?date=" + this.tomorrow);

		assertThat(capacity.getStatus()).as(capacity.getContentAsString()).isEqualTo(200);
		String body = capacity.getContentAsString();
		assertThat(JsonPath.<String>read(body, "$.date")).isEqualTo(this.tomorrow.toString());
		assertThat(JsonPath.<String>read(body, "$.fulfillmentType")).isEqualTo("PICKUP");
		assertThat(JsonPath.<List<String>>read(body, "$.slots[*].slotStart")).containsExactly("09:00", "09:30",
				"10:00", "10:30");
		assertThat(JsonPath.<Map<String, Object>>read(body, "$.slots[0]")).containsEntry("slotEnd", "09:30")
			.containsEntry("usedOrders", 3)
			.containsEntry("maxOrders", 4)
			.containsEntry("usedItems", 5)
			.containsEntry("maxItems", 20);
		assertThat(JsonPath.<Integer>read(body, "$.slots[1].usedOrders")).isZero();
		// The same resolver as the public slots (Decision V1).
		List<String> publicStatuses = this.slots.resolve(new SlotQuery(this.branchId, this.tomorrow, 1, null, null))
			.days()
			.getFirst()
			.slots()
			.stream()
			.map(slot -> slot.status().name())
			.toList();
		assertThat(JsonPath.<List<String>>read(body, "$.slots[*].status")).isEqualTo(publicStatuses);
	}

	@Test
	void anyDayOfAnyExistingBranchCanBeRead() throws Exception {
		assertThat(JsonPath.<List<String>>read(capacity(this.admin, this.branchId, "?date=" + this.today.minusDays(1))
			.getContentAsString(), "$.slots[*].status")).isNotEmpty().allMatch("CUTOFF_PASSED"::equals);
		assertThat(capacity(this.admin, this.branchId, "?date=" + this.today.plusDays(60)).getStatus())
			.isEqualTo(200);
		assertThat(JsonPath.<String>read(capacity(this.admin, this.branchId, "").getContentAsString(), "$.date"))
			.isEqualTo(this.today.toString());

		this.branches.deactivate(this.branchId, null, OWNER, null, null);

		assertThat(JsonPath.<List<Object>>read(capacity(this.admin, this.branchId, "?date=" + this.tomorrow)
			.getContentAsString(), "$.slots")).hasSize(4);
		assertProblem(capacity(this.admin, UUID.randomUUID(), ""), 404, "BRANCH_NOT_FOUND");
	}

	@Test
	void managerSeesOnlyTheAssignedBranch() throws Exception {
		UUID other = configuredBranch();
		UUID managerId = this.fixtures.branchStaff();
		this.fixtures.assignToBranch(managerId, this.branchId, "MANAGER");
		Session manager = this.fixtures.openSession(managerId);

		assertThat(capacity(manager, this.branchId, "").getStatus()).isEqualTo(200);
		assertProblem(capacity(manager, other, ""), 403, "BRANCH_SCOPE_DENIED");
	}

	@Test
	void loweringTheDefaultCapacityWarnsWithoutTouchingClaims() throws Exception {
		MockHttpServletResponse saved = this.auth.perform(MockMvcRequestBuilders
			.put(BRANCHES + "/" + this.branchId + "/fulfillment/configs/PICKUP")
			.contentType(MediaType.APPLICATION_JSON)
			.header("If-Match", "\"v2\"")
			.content("""
					{"isEnabled": true, "asapEnabled": false, "maxAdvanceDays": 14, "slotDurationMinutes": 30,
					 "sameDayCutoffMinutes": 0, "minLeadMinutes": 0, "defaultSlotMaxOrders": 10,
					 "defaultSlotMaxItems": 4}
					"""), this.admin, true);

		assertThat(saved.getStatus()).as(saved.getContentAsString()).isEqualTo(200);
		assertThat(JsonPath.<List<Map<String, Object>>>read(saved.getContentAsString(), "$.overbookedSlots"))
			.containsExactly(overbooked(this.tomorrow, "09:00", "09:30", 3, 4, 5, 4));
		assertThat(this.jdbc.sql("SELECT used_orders, used_items FROM ful_slot_capacity WHERE branch_id = ?")
			.param(UuidBinary.toBytes(this.branchId))
			.query()
			.singleRow()).containsEntry("used_orders", 3).containsEntry("used_items", 5);
	}

	@Test
	void loweringTheWeeklyCapacityWarns() throws Exception {
		MockHttpServletResponse saved = replaceWeek("\"v2\"", 2);

		assertThat(JsonPath.<List<Map<String, Object>>>read(saved.getContentAsString(), "$.overbookedSlots"))
			.containsExactly(overbooked(this.tomorrow, "09:00", "09:30", 3, 2, 5, 20));
	}

	@Test
	void overridesWarnOnLowerCapacityAndAgainWhenDeleted() throws Exception {
		replaceWeek("\"v2\"", 2);
		MockHttpServletResponse override = this.auth.perform(MockMvcRequestBuilders
			.post(BRANCHES + "/" + this.branchId + "/fulfillment/configs/PICKUP/overrides")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"date": "%s", "isClosed": false, "maxOrders": 10,
					 "intervals": [{"start": "09:00", "end": "11:00"}]}
					""".formatted(this.tomorrow)), this.admin, true);
		assertThat(JsonPath.<List<Object>>read(override.getContentAsString(), "$.overbookedSlots")).isEmpty();
		String overrideId = JsonPath.read(override.getContentAsString(), "$.overrides[0].overrideId");

		MockHttpServletResponse lower = this.auth.perform(MockMvcRequestBuilders
			.post(BRANCHES + "/" + this.branchId + "/fulfillment/configs/PICKUP/overrides")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"date": "%s", "isClosed": false, "maxOrders": 1,
					 "intervals": [{"start": "09:00", "end": "11:00"}]}
					""".formatted(this.tomorrow)), this.admin, true);
		assertThat(JsonPath.<List<Map<String, Object>>>read(lower.getContentAsString(), "$.overbookedSlots"))
			.containsExactly(overbooked(this.tomorrow, "09:00", "09:30", 3, 1, 5, 20));

		String current = JsonPath.read(lower.getContentAsString(), "$.overrides[0].overrideId");
		MockHttpServletResponse deleted = this.auth.perform(MockMvcRequestBuilders
			.delete(BRANCHES + "/" + this.branchId + "/fulfillment/configs/PICKUP/overrides/" + current), this.admin,
				true);
		// Back to the weekly row of 2 orders.
		assertThat(JsonPath.<List<Map<String, Object>>>read(deleted.getContentAsString(), "$.overbookedSlots"))
			.containsExactly(overbooked(this.tomorrow, "09:00", "09:30", 3, 2, 5, 20));
		assertThat(overrideId).isNotEqualTo(current);
	}

	@Test
	void aClaimedSlotThatNoLongerExistsIsOverbookedAtZero() throws Exception {
		// 15-minute windows: 09:00–09:15 is not the claimed 09:00–09:30 slot.
		MockHttpServletResponse saved = this.auth.perform(MockMvcRequestBuilders
			.put(BRANCHES + "/" + this.branchId + "/fulfillment/configs/PICKUP")
			.contentType(MediaType.APPLICATION_JSON)
			.header("If-Match", "\"v2\"")
			.content("""
					{"isEnabled": true, "asapEnabled": false, "maxAdvanceDays": 14, "slotDurationMinutes": 15,
					 "sameDayCutoffMinutes": 0, "minLeadMinutes": 0, "defaultSlotMaxOrders": 10,
					 "defaultSlotMaxItems": 20}
					"""), this.admin, true);

		assertThat(JsonPath.<List<Map<String, Object>>>read(saved.getContentAsString(), "$.overbookedSlots"))
			.containsExactly(overbooked(this.tomorrow, "09:00", "09:30", 3, 0, 5, 0));
	}

	@Test
	void pastClaimsAndEnoughCapacityRaiseNoWarning() throws Exception {
		seed(this.today.minusDays(1), at(9, 0), at(9, 30), 9, 9);

		assertThat(JsonPath.<List<Object>>read(replaceWeek("\"v2\"", 3).getContentAsString(), "$.overbookedSlots"))
			.isEmpty();
		assertThat(JsonPath.<List<Object>>read(this.auth.perform(MockMvcRequestBuilders
			.get(BRANCHES + "/" + this.branchId + "/fulfillment/configs/PICKUP/schedule"), this.admin, false)
			.getContentAsString(), "$.overbookedSlots")).isEmpty();
	}

	@Test
	void slotsThatAlreadyStartedAreNotReported() throws Exception {
		// Today 00:00 has started whenever the test runs; a capacity change no longer affects it (Decision V2).
		seed(this.today, at(0, 0), at(0, 30), 5, 5);

		List<Map<String, Object>> overbooked = JsonPath.read(replaceWeek("\"v2\"", 2).getContentAsString(),
				"$.overbookedSlots");

		assertThat(overbooked).extracting(slot -> slot.get("date")).doesNotContain(this.today.toString());
		assertThat(overbooked).containsExactly(overbooked(this.tomorrow, "09:00", "09:30", 3, 2, 5, 20));
	}

	private MockHttpServletResponse replaceWeek(String ifMatch, int maxOrders) throws Exception {
		String windows = String.join(",", IntStream.rangeClosed(1, 7)
			.mapToObj(day -> "{\"dayOfWeek\": %d, \"start\": \"09:00\", \"end\": \"11:00\", \"maxOrders\": %d}"
				.formatted(day, maxOrders))
			.toList());
		MockHttpServletRequestBuilder put = MockMvcRequestBuilders
			.put(BRANCHES + "/" + this.branchId + "/fulfillment/configs/PICKUP/weekly-schedule")
			.contentType(MediaType.APPLICATION_JSON)
			.header("If-Match", ifMatch)
			.content("{\"weekly\": [" + windows + "]}");
		MockHttpServletResponse response = this.auth.perform(put, this.admin, true);
		assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
		return response;
	}

	private UUID configuredBranch() {
		UUID id = this.branches.create(new NewBranch("T-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
				"HaYi test", "1 Lê Lợi", null, null, "TP.HCM", null, null, null, null), OWNER, null, null)
			.branchId();
		this.hours.replaceWeekly(id,
				IntStream.rangeClosed(1, 7).mapToObj(day -> new WeeklyIntervalRequest(day, at(7, 0), at(21, 0))).toList(),
				new ExpectedVersion(0), OWNER, null, null);
		this.branches.activate(id, null, OWNER, null, null);
		this.configs.replace(id, "PICKUP", new FulfillmentRules(true, false, 14, 30, 0, 0, null, null, 10, 20, null),
				new ExpectedVersion(0), OWNER, null, null);
		this.schedules.replaceWeekly(id, "PICKUP",
				IntStream.rangeClosed(1, 7).mapToObj(day -> new ScheduleWindowInput(day, at(9, 0), at(11, 0), 4, null))
					.toList(),
				new ExpectedVersion(1), OWNER, null, null);
		return id;
	}

	private void seed(LocalDate date, LocalTime start, LocalTime end, int orders, int items) {
		this.jdbc.sql("""
				INSERT INTO ful_slot_capacity (id, branch_id, fulfillment_type, slot_date, slot_start_time,
				  slot_end_time, used_orders, used_items, updated_at)
				VALUES (?, ?, 'PICKUP', ?, ?, ?, ?, ?, UTC_TIMESTAMP(6))
				""")
			.params(UuidBinary.toBytes(UUID.randomUUID()), UuidBinary.toBytes(this.branchId), date, start.toString(),
					end.toString(), orders, items)
			.update();
	}

	private MockHttpServletResponse capacity(Session session, UUID branch, String query) throws Exception {
		return this.auth.perform(MockMvcRequestBuilders.get(BRANCHES + "/" + branch + "/fulfillment/capacity" + query),
				session, false);
	}

	private static Map<String, Object> overbooked(LocalDate date, String start, String end, int usedOrders,
			int maxOrders, int usedItems, int maxItems) {
		return Map.of("date", date.toString(), "slotStart", start, "slotEnd", end, "usedOrders", usedOrders,
				"maxOrders", maxOrders, "usedItems", usedItems, "maxItems", maxItems);
	}

	private static void assertProblem(MockHttpServletResponse response, int status, String code) throws Exception {
		assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
		assertThat(JsonPath.<String>read(response.getContentAsString(), "$.code")).isEqualTo(code);
	}

	private static LocalTime at(int hour, int minute) {
		return LocalTime.of(hour, minute);
	}

}
