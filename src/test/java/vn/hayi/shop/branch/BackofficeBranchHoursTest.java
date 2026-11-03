package vn.hayi.shop.branch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.time.LocalDate;
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

/** Owner/Admin set a Branch's weekly hours and date overrides (Branch §4.4, API 09 §1.4, Decision Q). */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MutableClockConfiguration.class, IdentityFixtures.class })
class BackofficeBranchHoursTest {

	static final String BRANCHES = "/api/backoffice/v1/branches";

	static final String WEEK = """
			{"weekly": [
			  {"dayOfWeek": 1, "open": "07:00", "close": "11:30"},
			  {"dayOfWeek": 1, "open": "13:00", "close": "21:00"},
			  {"dayOfWeek": 7, "open": "08:00", "close": "22:00"}]}
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

	@BeforeEach
	void setUp() throws Exception {
		this.auth = new BackofficeAuth(this.mvc, this.clock);
		this.adminId = this.fixtures.storeAdmin(IdentityFixtures.uniqueEmail());
		this.admin = this.fixtures.openSession(this.adminId);
		this.branchId = createBranch();
	}

	@Test
	void newBranchHasNoHours() throws Exception {
		MockHttpServletResponse hours = hours(this.admin);

		assertThat(hours.getStatus()).as(hours.getContentAsString()).isEqualTo(200);
		assertThat(hours.getHeader("ETag")).isEqualTo("\"v0\"");
		assertThat(JsonPath.<List<Object>>read(hours.getContentAsString(), "$.weekly")).isEmpty();
		assertThat(JsonPath.<List<Object>>read(hours.getContentAsString(), "$.overrides")).isEmpty();
	}

	@Test
	void weeklyHoursReplaceTheWholeWeek() throws Exception {
		MockHttpServletResponse first = replace("\"v0\"", WEEK);
		assertThat(first.getStatus()).as(first.getContentAsString()).isEqualTo(200);
		assertThat(first.getHeader("ETag")).isEqualTo("\"v1\"");
		assertThat(JsonPath.<List<String>>read(first.getContentAsString(), "$.weekly[*].open"))
			.containsExactly("07:00", "13:00", "08:00");
		// Wall-clock times are stored as entered, not shifted to UTC.
		assertThat(this.jdbc.sql("SELECT CAST(open_time AS CHAR) FROM brn_business_hour WHERE branch_id = ?"
				+ " ORDER BY open_time")
			.param(UuidBinary.toBytes(UUID.fromString(this.branchId)))
			.query(String.class)
			.list()).containsExactly("07:00:00", "08:00:00", "13:00:00");

		MockHttpServletResponse second = replace("\"v1\"",
				"{\"weekly\": [{\"dayOfWeek\": 3, \"open\": \"09:00\", \"close\": \"12:00\"},"
						+ " {\"dayOfWeek\": 3, \"open\": \"12:00\", \"close\": \"15:00\"}]}");

		assertThat(second.getStatus()).as(second.getContentAsString()).isEqualTo(200);
		assertThat(JsonPath.<List<Integer>>read(second.getContentAsString(), "$.weekly[*].dayOfWeek"))
			.containsExactly(3, 3);
		assertThat(hours(this.admin).getHeader("ETag")).isEqualTo("\"v2\"");
		assertThat(audits("BRANCH_HOURS_REPLACE")).hasSize(2);
	}

	@Test
	void identicalWeekKeepsVersionAndIsNotAudited() throws Exception {
		replace("\"v0\"", WEEK);

		// The same ranges in another order are the same week (Decision Q1).
		MockHttpServletResponse same = replace("\"v1\"", """
				{"weekly": [
				  {"dayOfWeek": 7, "open": "08:00", "close": "22:00"},
				  {"dayOfWeek": 1, "open": "13:00", "close": "21:00"},
				  {"dayOfWeek": 1, "open": "07:00", "close": "11:30"}]}
				""");

		assertThat(same.getStatus()).as(same.getContentAsString()).isEqualTo(200);
		assertThat(same.getHeader("ETag")).isEqualTo("\"v1\"");
		assertThat(audits("BRANCH_HOURS_REPLACE")).hasSize(1);
	}

	@Test
	void weeklyHoursRequireCurrentIfMatch() throws Exception {
		assertProblem(replace(null, WEEK), 428, "PRECONDITION_REQUIRED");
		assertProblem(replace("\"v5\"", WEEK), 412, "VERSION_CONFLICT");

		assertThat(JsonPath.<List<Object>>read(hours(this.admin).getContentAsString(), "$.weekly")).isEmpty();
	}

	@Test
	void editingInformationAfterHoursNeedsTheNewVersion() throws Exception {
		replace("\"v0\"", WEEK);

		MockHttpServletResponse stale = this.auth.perform(patch(BRANCHES + "/" + this.branchId)
			.contentType(MediaType.parseMediaType("application/merge-patch+json"))
			.header("If-Match", "\"v0\"")
			.content("{\"name\": \"Đổi tên\"}"), this.admin, true);

		assertProblem(stale, 412, "VERSION_CONFLICT");
	}

	@Test
	void invalidIntervalsAreRejectedWithTheirPosition() throws Exception {
		MockHttpServletResponse overlap = replace("\"v0\"", """
				{"weekly": [
				  {"dayOfWeek": 2, "open": "07:00", "close": "12:00"},
				  {"dayOfWeek": 2, "open": "11:00", "close": "14:00"},
				  {"dayOfWeek": 4, "open": "18:00", "close": "08:00"},
				  {"dayOfWeek": 5, "open": "09:00", "close": "09:00"}]}
				""");

		assertProblem(overlap, 422, "BRANCH_HOURS_INVALID");
		assertThat(JsonPath.<List<String>>read(overlap.getContentAsString(), "$.errors[*].field"))
			.containsExactly("weekly[1]", "weekly[2]", "weekly[3]");
		assertThat(JsonPath.<List<String>>read(overlap.getContentAsString(), "$.errors[*].code"))
			.containsExactly("OVERLAPPING", "OPEN_NOT_BEFORE_CLOSE", "OPEN_NOT_BEFORE_CLOSE");
		assertThat(hours(this.admin).getHeader("ETag")).isEqualTo("\"v0\"");
	}

	@Test
	void malformedTimesAreValidationErrors() throws Exception {
		assertProblem(replace("\"v0\"", "{\"weekly\": [{\"dayOfWeek\": 1, \"open\": \"07:00\", \"close\": \"24:00\"}]}"),
				400, "VALIDATION_FAILED");
		assertProblem(replace("\"v0\"", "{\"weekly\": [{\"dayOfWeek\": 8, \"open\": \"07:00\", \"close\": \"09:00\"}]}"),
				400, "VALIDATION_FAILED");
	}

	@Test
	void overrideClosesAWholeDay() throws Exception {
		LocalDate day = today().plusDays(3);

		MockHttpServletResponse set = override(null, """
				{"date": "%s", "isClosed": true, "intervals": [], "reason": "Bảo trì"}
				""".formatted(day));

		assertThat(set.getStatus()).as(set.getContentAsString()).isEqualTo(200);
		assertThat(set.getHeader("ETag")).isEqualTo("\"v1\"");
		String body = set.getContentAsString();
		assertThat(JsonPath.<String>read(body, "$.overrides[0].date")).isEqualTo(day.toString());
		assertThat(JsonPath.<Boolean>read(body, "$.overrides[0].isClosed")).isTrue();
		assertThat(JsonPath.<List<Object>>read(body, "$.overrides[0].intervals")).isEmpty();
		assertThat(JsonPath.<String>read(body, "$.overrides[0].reason")).isEqualTo("Bảo trì");
		assertThat(audits("BRANCH_HOUR_OVERRIDE_PUT")).hasSize(1);
	}

	@Test
	void newOverrideOfADateReplacesTheOldOne() throws Exception {
		LocalDate day = today().plusDays(2);
		override(null, "{\"date\": \"%s\", \"isClosed\": true, \"intervals\": []}".formatted(day));

		MockHttpServletResponse replaced = override("\"v1\"", """
				{"date": "%s", "isClosed": false,
				 "intervals": [{"open": "08:00", "close": "12:00"}, {"open": "14:00", "close": "23:00"}]}
				""".formatted(day));

		assertThat(replaced.getStatus()).as(replaced.getContentAsString()).isEqualTo(200);
		String body = replaced.getContentAsString();
		assertThat(JsonPath.<List<String>>read(body, "$.overrides[*].date")).containsExactly(day.toString());
		assertThat(JsonPath.<Boolean>read(body, "$.overrides[0].isClosed")).isFalse();
		assertThat(JsonPath.<List<String>>read(body, "$.overrides[0].intervals[*].close"))
			.containsExactly("12:00", "23:00");
		assertThat(JsonPath.<Object>read(body, "$.overrides[0].reason")).isNull();
	}

	@Test
	void overridesListFromTodaySortedByDate() throws Exception {
		LocalDate today = today();
		override(null, "{\"date\": \"%s\", \"isClosed\": true, \"intervals\": []}".formatted(today.plusDays(5)));
		override(null, "{\"date\": \"%s\", \"isClosed\": true, \"intervals\": []}".formatted(today));
		// An override that has passed (the API refuses to create one in the past).
		this.jdbc.sql("""
				INSERT INTO brn_business_hour_override (id, branch_id, override_date, is_closed, created_at)
				VALUES (?, ?, ?, 1, NOW(6))
				""")
			.params(UuidBinary.toBytes(UUID.randomUUID()), UuidBinary.toBytes(UUID.fromString(this.branchId)),
					today.minusDays(1))
			.update();

		MockHttpServletResponse listed = hours(this.admin);

		assertThat(listed.getStatus()).as(listed.getContentAsString()).isEqualTo(200);
		assertThat(JsonPath.<List<String>>read(listed.getContentAsString(), "$.overrides[*].date"))
			.containsExactly(today.toString(), today.plusDays(5).toString());
	}

	@Test
	void invalidOverridesAreRejected() throws Exception {
		LocalDate day = today().plusDays(1);
		assertHoursInvalid(override(null, "{\"date\": \"%s\", \"isClosed\": true, \"intervals\": []}"
			.formatted(today().minusDays(1))), "date", "DATE_IN_PAST");
		assertHoursInvalid(override(null, """
				{"date": "%s", "isClosed": true, "intervals": [{"open": "08:00", "close": "12:00"}]}
				""".formatted(day)), "intervals", "CLOSED_WITH_INTERVALS");
		assertHoursInvalid(override(null, "{\"date\": \"%s\", \"isClosed\": false, \"intervals\": []}".formatted(day)),
				"intervals", "OPEN_WITHOUT_INTERVALS");
		assertHoursInvalid(override(null, """
				{"date": "%s", "isClosed": false,
				 "intervals": [{"open": "08:00", "close": "12:00"}, {"open": "09:00", "close": "10:00"}]}
				""".formatted(day)), "intervals[1]", "OVERLAPPING");
		assertProblem(override("\"v9\"", "{\"date\": \"%s\", \"isClosed\": true, \"intervals\": []}".formatted(day)),
				412, "VERSION_CONFLICT");

		assertThat(hours(this.admin).getHeader("ETag")).isEqualTo("\"v0\"");
	}

	@Test
	void deleteOverride() throws Exception {
		LocalDate day = today().plusDays(4);
		override(null, "{\"date\": \"%s\", \"isClosed\": true, \"intervals\": []}".formatted(day));

		MockHttpServletResponse deleted = this.auth.perform(delete(overridePath(day)), this.admin, true);

		assertThat(deleted.getStatus()).as(deleted.getContentAsString()).isEqualTo(200);
		assertThat(deleted.getHeader("ETag")).isEqualTo("\"v2\"");
		assertThat(JsonPath.<List<Object>>read(deleted.getContentAsString(), "$.overrides")).isEmpty();
		assertThat(audits("BRANCH_HOUR_OVERRIDE_DELETE")).hasSize(1);
		assertProblem(this.auth.perform(delete(overridePath(day)), this.admin, true), 404,
				"BRANCH_HOURS_OVERRIDE_NOT_FOUND");
	}

	@Test
	void closedBranchHoursAreLocked() throws Exception {
		this.jdbc.sql("UPDATE brn_branch SET status = 'CLOSED' WHERE id = ?")
			.param(UuidBinary.toBytes(UUID.fromString(this.branchId)))
			.update();

		assertProblem(replace("\"v0\"", WEEK), 409, "BRANCH_INVALID_TRANSITION");
		assertProblem(override(null, "{\"date\": \"%s\", \"isClosed\": true, \"intervals\": []}"
			.formatted(today().plusDays(1))), 409, "BRANCH_INVALID_TRANSITION");
	}

	@Test
	void managerReadsButCannotEditHours() throws Exception {
		UUID managerId = this.fixtures.branchStaff();
		this.fixtures.assignToBranch(managerId, UUID.fromString(this.branchId), "MANAGER");
		Session manager = this.fixtures.openSession(managerId);

		assertThat(hours(manager).getStatus()).isEqualTo(200);
		MockHttpServletRequestBuilder replace = put(BRANCHES + "/" + this.branchId + "/business-hours")
			.contentType(MediaType.APPLICATION_JSON)
			.header("If-Match", "\"v0\"")
			.content(WEEK);
		assertProblem(this.auth.perform(replace, manager, true), 403, "PERMISSION_DENIED");
	}

	@Test
	void unknownBranchIsNotFound() throws Exception {
		assertProblem(this.auth.perform(get(BRANCHES + "/" + UUID.randomUUID() + "/business-hours"), this.admin,
				false), 404, "BRANCH_NOT_FOUND");
	}

	private LocalDate today() {
		return LocalDate.ofInstant(this.clock.instant(), BranchTime.DEFAULT_ZONE);
	}

	private MockHttpServletResponse hours(Session session) throws Exception {
		return this.auth.perform(get(BRANCHES + "/" + this.branchId + "/business-hours"), session, false);
	}

	private MockHttpServletResponse replace(String ifMatch, String json) throws Exception {
		MockHttpServletRequestBuilder request = put(BRANCHES + "/" + this.branchId + "/business-hours")
			.contentType(MediaType.APPLICATION_JSON)
			.content(json);
		if (ifMatch != null) {
			request.header("If-Match", ifMatch);
		}
		return this.auth.perform(request, this.admin, true);
	}

	private MockHttpServletResponse override(String ifMatch, String json) throws Exception {
		MockHttpServletRequestBuilder request = post(BRANCHES + "/" + this.branchId + "/business-hour-overrides")
			.contentType(MediaType.APPLICATION_JSON)
			.content(json);
		if (ifMatch != null) {
			request.header("If-Match", ifMatch);
		}
		return this.auth.perform(request, this.admin, true);
	}

	private String overridePath(LocalDate date) {
		return BRANCHES + "/" + this.branchId + "/business-hour-overrides/" + date;
	}

	private List<Map<String, Object>> audits(String action) {
		return this.jdbc.sql("SELECT id FROM aud_audit_log WHERE entity_id = ? AND action = ?")
			.param(UuidBinary.toBytes(UUID.fromString(this.branchId)))
			.param(action)
			.query()
			.listOfRows();
	}

	private String createBranch() throws Exception {
		String code = "T-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
		MockHttpServletResponse created = this.auth.perform(post(BRANCHES).contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"branchCode": "%s", "name": "HaYi test", "address": {"line": "1 Lê Lợi", "province": "TP.HCM"}}
					""".formatted(code)), this.admin, true);
		assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
		return JsonPath.read(created.getContentAsString(), "$.branchId");
	}

	private static void assertHoursInvalid(MockHttpServletResponse response, String field, String code)
			throws Exception {
		assertProblem(response, 422, "BRANCH_HOURS_INVALID");
		assertThat(JsonPath.<List<String>>read(response.getContentAsString(), "$.errors[*].field")).containsExactly(field);
		assertThat(JsonPath.<List<String>>read(response.getContentAsString(), "$.errors[*].code")).containsExactly(code);
	}

	private static void assertProblem(MockHttpServletResponse response, int status, String code) throws Exception {
		assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
		assertThat(JsonPath.<String>read(response.getContentAsString(), "$.code")).isEqualTo(code);
	}

}
