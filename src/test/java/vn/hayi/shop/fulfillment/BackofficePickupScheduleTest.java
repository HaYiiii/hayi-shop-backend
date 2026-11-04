package vn.hayi.shop.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

import com.jayway.jsonpath.JsonPath;
import vn.hayi.shop.MutableClock;
import vn.hayi.shop.MutableClockConfiguration;
import vn.hayi.shop.TestcontainersConfiguration;
import vn.hayi.shop.identity.BackofficeAuth;
import vn.hayi.shop.identity.BackofficeAuth.Session;
import vn.hayi.shop.identity.IdentityFixtures;
import vn.hayi.shop.platform.persistence.UuidBinary;
import vn.hayi.shop.shared.time.BranchTime;

/**
 * Owner/Admin and the Branch's Manager set PICKUP windows per weekday with per-slot capacity, and date overrides;
 * windows must sit inside the Branch's open hours (Fulfillment §8.3, §8.5, API 09 §2.2, Decision N4, S).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MutableClockConfiguration.class, IdentityFixtures.class })
class BackofficePickupScheduleTest {

	static final String BRANCHES = "/api/backoffice/v1/branches";

	/** Monday 07:00–11:00 + 11:00–14:00 (touching, one open stretch) and 16:00–21:00; Tuesday 07:00–21:00. */
	static final String BRANCH_HOURS = """
			{"weekly": [
			  {"dayOfWeek": 1, "open": "07:00", "close": "11:00"},
			  {"dayOfWeek": 1, "open": "11:00", "close": "14:00"},
			  {"dayOfWeek": 1, "open": "16:00", "close": "21:00"},
			  {"dayOfWeek": 2, "open": "07:00", "close": "21:00"}]}
			""";

	static final String WEEK = """
			{"weekly": [
			  {"dayOfWeek": 1, "start": "08:00", "end": "13:00", "maxOrders": 10, "maxItems": 30},
			  {"dayOfWeek": 1, "start": "17:00", "end": "20:00"},
			  {"dayOfWeek": 2, "start": "07:00", "end": "21:00", "maxOrders": 5, "maxItems": null}]}
			""";

	@Autowired
	MockMvc mvc;

	@Autowired
	IdentityFixtures fixtures;

	@Autowired
	MutableClock clock;

	@Autowired
	JdbcClient jdbc;

	BackofficeAuth auth;

	UUID adminId;

	Session admin;

	String branchId;

	LocalDate nextMonday;

	@BeforeEach
	void setUp() throws Exception {
		this.auth = new BackofficeAuth(this.mvc, this.clock);
		this.adminId = this.fixtures.storeAdmin(IdentityFixtures.uniqueEmail());
		this.admin = this.fixtures.openSession(this.adminId);
		this.branchId = createBranchWithHours();
		this.nextMonday = LocalDate.ofInstant(this.clock.instant(), BranchTime.DEFAULT_ZONE)
			.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
	}

	@Test
	void weeklyScheduleReplacesTheWeek() throws Exception {
		MockHttpServletResponse saved = replaceWeek("\"v0\"", WEEK);

		assertThat(saved.getStatus()).as(saved.getContentAsString()).isEqualTo(200);
		assertThat(saved.getHeader("ETag")).isEqualTo("\"v1\"");
		String body = saved.getContentAsString();
		assertThat(JsonPath.<List<String>>read(body, "$.weekly[*].start")).containsExactly("08:00", "17:00", "07:00");
		assertThat(JsonPath.<List<Object>>read(body, "$.weekly[*].maxOrders")).containsExactly(10, null, 5);
		assertThat(JsonPath.<List<Object>>read(body, "$.weekly[*].maxItems")).containsExactly(30, null, null);
		// Wall-clock times are stored as entered.
		assertThat(this.jdbc.sql("SELECT CAST(s.start_time AS CHAR) FROM ful_weekly_schedule s"
				+ " JOIN ful_branch_fulfillment_config c ON c.id = s.config_id WHERE c.branch_id = ? ORDER BY 1")
			.param(UuidBinary.toBytes(UUID.fromString(this.branchId)))
			.query(String.class)
			.list()).containsExactly("07:00:00", "08:00:00", "17:00:00");

		MockHttpServletResponse replaced = replaceWeek("\"v1\"",
				"{\"weekly\": [{\"dayOfWeek\": 2, \"start\": \"09:00\", \"end\": \"10:00\"}]}");

		assertThat(replaced.getHeader("ETag")).isEqualTo("\"v2\"");
		assertThat(JsonPath.<List<Integer>>read(replaced.getContentAsString(), "$.weekly[*].dayOfWeek"))
			.containsExactly(2);
		assertThat(audits("FULFILLMENT_SCHEDULE_REPLACE")).hasSize(2);
	}

	@Test
	void scheduleOnUnsavedConfigSavesTheDefaultConfig() throws Exception {
		replaceWeek("\"v0\"", WEEK);

		String configs = this.auth.perform(get(BRANCHES + "/" + this.branchId + "/fulfillment/configs"), this.admin,
				false).getContentAsString();

		assertThat(JsonPath.<Integer>read(configs, "$.items[0].version")).isEqualTo(1);
		assertThat(JsonPath.<Boolean>read(configs, "$.items[0].isEnabled")).isFalse();
		assertThat(JsonPath.<Integer>read(configs, "$.items[0].slotDurationMinutes")).isEqualTo(15);
	}

	@Test
	void weeklyScheduleRequiresCurrentIfMatch() throws Exception {
		assertProblem(replaceWeek(null, WEEK), 428, "PRECONDITION_REQUIRED");
		assertProblem(replaceWeek("\"v3\"", WEEK), 412, "VERSION_CONFLICT");
		replaceWeek("\"v0\"", WEEK);

		assertProblem(replaceWeek("\"v0\"", WEEK), 412, "VERSION_CONFLICT");
	}

	@Test
	void identicalScheduleKeepsVersion() throws Exception {
		replaceWeek("\"v0\"", WEEK);

		MockHttpServletResponse same = replaceWeek("\"v1\"", """
				{"weekly": [
				  {"dayOfWeek": 2, "start": "07:00", "end": "21:00", "maxOrders": 5},
				  {"dayOfWeek": 1, "start": "17:00", "end": "20:00", "maxOrders": null, "maxItems": null},
				  {"dayOfWeek": 1, "start": "08:00", "end": "13:00", "maxOrders": 10, "maxItems": 30}]}
				""");

		assertThat(same.getHeader("ETag")).isEqualTo("\"v1\"");
		assertThat(audits("FULFILLMENT_SCHEDULE_REPLACE")).hasSize(1);
	}

	@Test
	void invalidValuesComeFirst() throws Exception {
		MockHttpServletResponse invalid = replaceWeek("\"v0\"", """
				{"weekly": [
				  {"dayOfWeek": 1, "start": "10:00", "end": "09:00"},
				  {"dayOfWeek": 1, "start": "08:00", "end": "12:00", "maxOrders": 0},
				  {"dayOfWeek": 1, "start": "09:00", "end": "11:00"}]}
				""");

		assertScheduleError(invalid, "FULFILLMENT_CONFIG_INVALID", "weekly[0]", "weekly[1].maxOrders");
	}

	@Test
	void overlappingWindowsAreRejected() throws Exception {
		MockHttpServletResponse overlap = replaceWeek("\"v0\"", """
				{"weekly": [
				  {"dayOfWeek": 1, "start": "08:00", "end": "11:00"},
				  {"dayOfWeek": 1, "start": "10:00", "end": "12:00"},
				  {"dayOfWeek": 1, "start": "12:00", "end": "13:00"},
				  {"dayOfWeek": 2, "start": "10:00", "end": "12:00"}]}
				""");

		assertScheduleError(overlap, "FULFILLMENT_SCHEDULE_OVERLAP", "weekly[1]");
	}

	@Test
	void windowsOutsideBranchHoursAreRejected() throws Exception {
		MockHttpServletResponse outside = replaceWeek("\"v0\"", """
				{"weekly": [
				  {"dayOfWeek": 1, "start": "06:00", "end": "08:00"},
				  {"dayOfWeek": 1, "start": "13:00", "end": "17:00"},
				  {"dayOfWeek": 1, "start": "18:00", "end": "20:00"},
				  {"dayOfWeek": 3, "start": "09:00", "end": "10:00"}]}
				""");

		// Before opening; across the 14:00–16:00 break; Wednesday has no Branch hours.
		assertScheduleError(outside, "FULFILLMENT_OUTSIDE_BRANCH_HOURS", "weekly[0]", "weekly[1]", "weekly[3]");
		assertThat(schedule(this.admin).getHeader("ETag")).isEqualTo("\"v0\"");
	}

	@Test
	void windowAcrossTouchingBranchRangesIsInside() throws Exception {
		MockHttpServletResponse saved = replaceWeek("\"v0\"",
				"{\"weekly\": [{\"dayOfWeek\": 1, \"start\": \"10:00\", \"end\": \"14:00\"}]}");

		assertThat(saved.getStatus()).as(saved.getContentAsString()).isEqualTo(200);
	}

	@Test
	void closedDayOverride() throws Exception {
		MockHttpServletResponse set = override(null, """
				{"date": "%s", "isClosed": true, "intervals": [], "reason": "Bảo trì"}
				""".formatted(this.nextMonday));

		assertThat(set.getStatus()).as(set.getContentAsString()).isEqualTo(200);
		assertThat(set.getHeader("ETag")).isEqualTo("\"v1\"");
		String body = set.getContentAsString();
		assertThat(JsonPath.<List<Boolean>>read(body, "$.overrides[*].isClosed")).containsExactly(true);
		assertThat(JsonPath.<Object>read(body, "$.overrides[0].start")).isNull();
		assertThat(JsonPath.<String>read(body, "$.overrides[0].overrideId")).isNotBlank();
		assertThat(audits("FULFILLMENT_OVERRIDE_PUT")).hasSize(1);
	}

	@Test
	void overrideWithOwnWindowsAndCapacityReplacesTheDate() throws Exception {
		override(null, "{\"date\": \"%s\", \"isClosed\": true, \"intervals\": []}".formatted(this.nextMonday));

		MockHttpServletResponse replaced = override("\"v1\"", """
				{"date": "%s", "isClosed": false, "maxOrders": 20, "maxItems": null, "reason": "Lễ",
				 "intervals": [{"start": "16:00", "end": "21:00"}, {"start": "07:00", "end": "14:00"}]}
				""".formatted(this.nextMonday));

		assertThat(replaced.getStatus()).as(replaced.getContentAsString()).isEqualTo(200);
		String body = replaced.getContentAsString();
		assertThat(JsonPath.<List<String>>read(body, "$.overrides[*].start")).containsExactly("07:00", "16:00");
		assertThat(JsonPath.<List<Integer>>read(body, "$.overrides[*].maxOrders")).containsExactly(20, 20);
		assertThat(JsonPath.<List<String>>read(body, "$.overrides[*].reason")).containsExactly("Lễ", "Lễ");
		assertThat(JsonPath.<List<String>>read(body, "$.overrides[*].overrideId")).doesNotHaveDuplicates();
	}

	@Test
	void overrideIsCheckedAgainstThatDaysBranchHours() throws Exception {
		// The Branch closes early on that Monday.
		this.auth.perform(post(BRANCHES + "/" + this.branchId + "/business-hour-overrides")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"date": "%s", "isClosed": false, "intervals": [{"open": "07:00", "close": "12:00"}]}
					""".formatted(this.nextMonday)), this.admin, true);

		assertScheduleError(override(null, """
				{"date": "%s", "isClosed": false, "intervals": [{"start": "16:00", "end": "20:00"}]}
				""".formatted(this.nextMonday)), "FULFILLMENT_OUTSIDE_BRANCH_HOURS", "intervals[0]");
		assertThat(override(null, """
				{"date": "%s", "isClosed": false, "intervals": [{"start": "08:00", "end": "11:00"}]}
				""".formatted(this.nextMonday)).getStatus()).isEqualTo(200);
	}

	@Test
	void invalidOverridesAreRejected() throws Exception {
		LocalDate yesterday = LocalDate.ofInstant(this.clock.instant(), BranchTime.DEFAULT_ZONE).minusDays(1);
		assertScheduleError(override(null, "{\"date\": \"%s\", \"isClosed\": true, \"intervals\": []}"
			.formatted(yesterday)), "FULFILLMENT_CONFIG_INVALID", "date");
		assertScheduleError(override(null, """
				{"date": "%s", "isClosed": true, "intervals": [{"start": "08:00", "end": "09:00"}]}
				""".formatted(this.nextMonday)), "FULFILLMENT_CONFIG_INVALID", "intervals");
		assertScheduleError(override(null, "{\"date\": \"%s\", \"isClosed\": false, \"intervals\": []}"
			.formatted(this.nextMonday)), "FULFILLMENT_CONFIG_INVALID", "intervals");
		assertScheduleError(override(null, """
				{"date": "%s", "isClosed": false, "maxItems": 0, "intervals": [{"start": "08:00", "end": "09:00"}]}
				""".formatted(this.nextMonday)), "FULFILLMENT_CONFIG_INVALID", "maxItems");
		assertScheduleError(override(null, """
				{"date": "%s", "isClosed": false,
				 "intervals": [{"start": "08:00", "end": "10:00"}, {"start": "09:00", "end": "11:00"}]}
				""".formatted(this.nextMonday)), "FULFILLMENT_SCHEDULE_OVERLAP", "intervals[1]");
	}

	@Test
	void deleteOneOverride() throws Exception {
		String body = override(null, """
				{"date": "%s", "isClosed": false,
				 "intervals": [{"start": "07:00", "end": "09:00"}, {"start": "10:00", "end": "12:00"}]}
				""".formatted(this.nextMonday)).getContentAsString();
		String first = JsonPath.read(body, "$.overrides[0].overrideId");

		MockHttpServletResponse deleted = this.auth.perform(delete(overridePath(this.branchId, first)), this.admin,
				true);

		assertThat(deleted.getStatus()).as(deleted.getContentAsString()).isEqualTo(200);
		assertThat(deleted.getHeader("ETag")).isEqualTo("\"v2\"");
		assertThat(JsonPath.<List<String>>read(deleted.getContentAsString(), "$.overrides[*].start"))
			.containsExactly("10:00");
		assertThat(audits("FULFILLMENT_OVERRIDE_DELETE")).hasSize(1);
		assertProblem(this.auth.perform(delete(overridePath(this.branchId, first)), this.admin, true), 404,
				"FULFILLMENT_OVERRIDE_NOT_FOUND");
	}

	@Test
	void overrideOfAnotherBranchIsNotFoundEvenWithScopeOnBoth() throws Exception {
		String other = createBranchWithHours();
		String foreign = JsonPath.read(this.auth.perform(post(BRANCHES + "/" + other
				+ "/fulfillment/configs/PICKUP/overrides")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"date\": \"%s\", \"isClosed\": true, \"intervals\": []}".formatted(this.nextMonday)),
				this.admin, true).getContentAsString(), "$.overrides[0].overrideId");
		// This Branch has a config too, so only the id can make the difference.
		override(null, "{\"date\": \"%s\", \"isClosed\": true, \"intervals\": []}".formatted(this.nextMonday));

		MockHttpServletResponse crossBranch = this.auth.perform(delete(overridePath(this.branchId, foreign)),
				this.admin, true);
		MockHttpServletResponse unknown = this.auth.perform(
				delete(overridePath(this.branchId, UUID.randomUUID().toString())), this.admin, true);

		assertProblem(crossBranch, 404, "FULFILLMENT_OVERRIDE_NOT_FOUND");
		assertThat(withoutRequestFields(crossBranch)).isEqualTo(withoutRequestFields(unknown));
		// The other Branch's override is untouched.
		assertThat(JsonPath.<List<String>>read(this.auth.perform(get(BRANCHES + "/" + other
				+ "/fulfillment/configs/PICKUP/schedule"), this.admin, false).getContentAsString(),
				"$.overrides[*].overrideId")).containsExactly(foreign);
	}

	@Test
	void closedBranchScheduleIsLocked() throws Exception {
		this.jdbc.sql("UPDATE brn_branch SET status = 'CLOSED' WHERE id = ?")
			.param(UuidBinary.toBytes(UUID.fromString(this.branchId)))
			.update();

		assertThat(schedule(this.admin).getStatus()).isEqualTo(200);
		assertProblem(replaceWeek("\"v0\"", WEEK), 409, "BRANCH_INVALID_TRANSITION");
	}

	@Test
	void managerSchedulesOnlyTheAssignedBranch() throws Exception {
		String other = createBranchWithHours();
		UUID managerId = this.fixtures.branchStaff();
		this.fixtures.assignToBranch(managerId, UUID.fromString(this.branchId), "MANAGER");
		Session manager = this.fixtures.openSession(managerId);

		MockHttpServletRequestBuilder own = put(BRANCHES + "/" + this.branchId
				+ "/fulfillment/configs/PICKUP/weekly-schedule")
			.contentType(MediaType.APPLICATION_JSON)
			.header("If-Match", "\"v0\"")
			.content(WEEK);
		assertThat(this.auth.perform(own, manager, true).getStatus()).isEqualTo(200);
		MockHttpServletRequestBuilder foreign = put(BRANCHES + "/" + other
				+ "/fulfillment/configs/PICKUP/weekly-schedule")
			.contentType(MediaType.APPLICATION_JSON)
			.header("If-Match", "\"v0\"")
			.content(WEEK);
		assertProblem(this.auth.perform(foreign, manager, true), 403, "BRANCH_SCOPE_DENIED");
	}

	@Test
	void onlyPickupHasASchedule() throws Exception {
		assertScheduleError(this.auth.perform(get(BRANCHES + "/" + this.branchId
				+ "/fulfillment/configs/DELIVERY/schedule"), this.admin, false), "FULFILLMENT_CONFIG_INVALID",
				"fulfillmentType");
	}

	private MockHttpServletResponse schedule(Session session) throws Exception {
		return this.auth.perform(get(BRANCHES + "/" + this.branchId + "/fulfillment/configs/PICKUP/schedule"),
				session, false);
	}

	private MockHttpServletResponse replaceWeek(String ifMatch, String json) throws Exception {
		MockHttpServletRequestBuilder request = put(BRANCHES + "/" + this.branchId
				+ "/fulfillment/configs/PICKUP/weekly-schedule")
			.contentType(MediaType.APPLICATION_JSON)
			.content(json);
		if (ifMatch != null) {
			request.header("If-Match", ifMatch);
		}
		return this.auth.perform(request, this.admin, true);
	}

	private MockHttpServletResponse override(String ifMatch, String json) throws Exception {
		MockHttpServletRequestBuilder request = post(BRANCHES + "/" + this.branchId
				+ "/fulfillment/configs/PICKUP/overrides")
			.contentType(MediaType.APPLICATION_JSON)
			.content(json);
		if (ifMatch != null) {
			request.header("If-Match", ifMatch);
		}
		return this.auth.perform(request, this.admin, true);
	}

	private static String overridePath(String branchId, String overrideId) {
		return BRANCHES + "/" + branchId + "/fulfillment/configs/PICKUP/overrides/" + overrideId;
	}

	private List<Map<String, Object>> audits(String action) {
		return this.jdbc.sql("SELECT id FROM aud_audit_log WHERE branch_id = ? AND action = ?")
			.param(UuidBinary.toBytes(UUID.fromString(this.branchId)))
			.param(action)
			.query()
			.listOfRows();
	}

	private String createBranchWithHours() throws Exception {
		String code = "T-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
		MockHttpServletResponse created = this.auth.perform(post(BRANCHES).contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"branchCode": "%s", "name": "HaYi test", "address": {"line": "1 Lê Lợi", "province": "TP.HCM"}}
					""".formatted(code)), this.admin, true);
		assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
		String id = JsonPath.read(created.getContentAsString(), "$.branchId");
		MockHttpServletResponse hours = this.auth.perform(put(BRANCHES + "/" + id + "/business-hours")
			.contentType(MediaType.APPLICATION_JSON)
			.header("If-Match", "\"v0\"")
			.content(BRANCH_HOURS), this.admin, true);
		assertThat(hours.getStatus()).as(hours.getContentAsString()).isEqualTo(200);
		return id;
	}

	/** The problem without what differs per request ({@code instance}, {@code traceId}). */
	private static Map<String, Object> withoutRequestFields(MockHttpServletResponse response) throws Exception {
		Map<String, Object> problem = new LinkedHashMap<>(JsonPath.read(response.getContentAsString(), "$"));
		problem.remove("instance");
		problem.remove("traceId");
		return problem;
	}

	private static void assertScheduleError(MockHttpServletResponse response, String code, String... fields)
			throws Exception {
		assertProblem(response, 422, code);
		assertThat(JsonPath.<List<String>>read(response.getContentAsString(), "$.errors[*].field")).containsExactly(fields);
	}

	private static void assertProblem(MockHttpServletResponse response, int status, String code) throws Exception {
		assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
		assertThat(JsonPath.<String>read(response.getContentAsString(), "$.code")).isEqualTo(code);
	}

}
