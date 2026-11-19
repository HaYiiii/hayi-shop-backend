package vn.hayi.shop.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.time.LocalDate;
import java.time.ZoneId;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;
import vn.hayi.shop.MutableClock;
import vn.hayi.shop.MutableClockConfiguration;
import vn.hayi.shop.TestcontainersConfiguration;
import vn.hayi.shop.identity.BackofficeAuth;
import vn.hayi.shop.identity.BackofficeAuth.Session;
import vn.hayi.shop.identity.IdentityFixtures;
import vn.hayi.shop.platform.persistence.UuidBinary;

/**
 * Owner/Admin set when a Product may be sold, and the public menu, detail and resolve follow it in Branch time
 * (Catalog §5.7, API 08 §1.9, API 02 §2, Decision AK).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MutableClockConfiguration.class, IdentityFixtures.class })
class SellingScheduleTest {

	static final String PRODUCTS = "/api/backoffice/v1/products";

	/** Monday 2030-01-07 09:00 in Asia/Ho_Chi_Minh. */
	static final String MONDAY_9AM = "2030-01-07T02:00:00Z";

	@Autowired
	MockMvc mvc;

	@Autowired
	IdentityFixtures fixtures;

	@Autowired
	MutableClock clock;

	@Autowired
	JdbcClient jdbc;

	BackofficeAuth auth;

	Session admin;

	String branch;

	String category;

	@BeforeEach
	void setUp() throws Exception {
		this.auth = new BackofficeAuth(this.mvc, this.clock);
		this.admin = this.fixtures.openSession(this.fixtures.storeAdmin(IdentityFixtures.uniqueEmail()));
		this.branch = branch();
		this.category = category();
	}

	@AfterEach
	void resetClock() {
		this.clock.reset();
	}

	// --- Backoffice (AK1) --------------------------------------------------------------------------------------

	@Test
	void replacesEverySchedulePerProductAndAuditsIt() throws Exception {
		Product product = product();

		MockHttpServletResponse replaced = putSchedules(product.id, "\"v1\"", """
				{"schedules": [{"startDate": "2030-12-01", "endDate": "2030-12-31", "windows": [
				                  {"dayOfWeek": 7, "start": "08:00", "end": "12:00"},
				                  {"dayOfWeek": 6, "start": "08:00", "end": "12:00"}]},
				               {"startDate": null, "endDate": null, "windows": []}]}
				""");

		assertThat(replaced.getStatus()).as(replaced.getContentAsString()).isEqualTo(200);
		assertThat(replaced.getHeader("ETag")).isEqualTo("\"v2\"");
		String body = replaced.getContentAsString();
		assertThat(JsonPath.<List<String>>read(body, "$.sellingSchedules[*].startDate"))
			.containsExactlyInAnyOrder("2030-12-01", null);
		List<Map<String, Object>> december = JsonPath.read(body, "$.sellingSchedules[?(@.startDate == '2030-12-01')]");
		assertThat(JsonPath.<List<Integer>>read(december.getFirst(), "$.windows[*].dayOfWeek"))
			.as("windows kept in day order")
			.containsExactly(6, 7);
		assertThat(JsonPath.<List<String>>read(december.getFirst(), "$.windows[*].start"))
			.containsExactly("08:00", "08:00");
		assertThat(audits(product.id, "PRODUCT_SELLING_SCHEDULE_REPLACE")).hasSize(1);
		assertThat(JsonPath.<List<Object>>read(detail(product.id).getContentAsString(), "$.sellingSchedules"))
			.hasSize(2);

		MockHttpServletResponse same = putSchedules(product.id, "\"v2\"", """
				{"schedules": [{"startDate": null, "endDate": null, "windows": []},
				               {"startDate": "2030-12-01", "endDate": "2030-12-31", "windows": [
				                  {"dayOfWeek": 6, "start": "08:00", "end": "12:00"},
				                  {"dayOfWeek": 7, "start": "08:00", "end": "12:00"}]}]}
				""");
		assertThat(same.getHeader("ETag")).as("no change keeps the version").isEqualTo("\"v2\"");
		assertThat(audits(product.id, "PRODUCT_SELLING_SCHEDULE_REPLACE")).hasSize(1);

		String a = "{\"startDate\": \"2031-01-01\", \"endDate\": null, \"windows\": []}";
		String b = "{\"startDate\": \"2031-02-01\", \"endDate\": null, \"windows\": []}";
		assertThat(putSchedules(product.id, "\"v2\"", "{\"schedules\": [" + a + ", " + a + ", " + b + "]}")
			.getHeader("ETag")).isEqualTo("\"v3\"");
		MockHttpServletResponse repeats = putSchedules(product.id, "\"v3\"",
				"{\"schedules\": [" + a + ", " + b + ", " + b + "]}");
		assertThat(repeats.getHeader("ETag")).as("other repeats are a change").isEqualTo("\"v4\"");
		assertThat(JsonPath.<List<String>>read(repeats.getContentAsString(), "$.sellingSchedules[*].startDate"))
			.containsExactlyInAnyOrder("2031-01-01", "2031-02-01", "2031-02-01");

		MockHttpServletResponse cleared = putSchedules(product.id, "\"v4\"", "{\"schedules\": []}");
		assertThat(cleared.getHeader("ETag")).isEqualTo("\"v5\"");
		assertThat(JsonPath.<List<Object>>read(cleared.getContentAsString(), "$.sellingSchedules")).isEmpty();
	}

	@Test
	void reversedRangesAndOverlappingWindowsAreRejectedWithEveryField() throws Exception {
		Product product = product();

		MockHttpServletResponse invalid = putSchedules(product.id, "\"v1\"", """
				{"schedules": [{"startDate": "2030-12-31", "endDate": "2030-12-01", "windows": []},
				               {"startDate": null, "endDate": null, "windows": [
				                  {"dayOfWeek": null, "start": "07:00", "end": "11:00"},
				                  {"dayOfWeek": 2, "start": "10:00", "end": "12:00"},
				                  {"dayOfWeek": 3, "start": "22:00", "end": "02:00"},
				                  {"dayOfWeek": 4, "start": "11:00", "end": "13:00"}]}]}
				""");

		assertProblem(invalid, 422, "CATALOG_SELLING_SCHEDULE_INVALID");
		assertThat(JsonPath.<List<String>>read(invalid.getContentAsString(), "$.errors[*].field")).containsExactly(
				"schedules[0].endDate", "schedules[1].windows[1]", "schedules[1].windows[2]");
		assertThat(JsonPath.<List<String>>read(invalid.getContentAsString(), "$.errors[*].code"))
			.containsExactly("RANGE", "OVERLAP", "RANGE");
		assertThat(audits(product.id, "PRODUCT_SELLING_SCHEDULE_REPLACE")).isEmpty();
	}

	@Test
	void theRequestNeedsIfMatchAValidShapeAndALiveProduct() throws Exception {
		Product product = product();
		String every = "{\"schedules\": [{\"startDate\": null, \"endDate\": null, \"windows\": []}]}";

		assertProblem(putSchedules(product.id, null, every), 428, "PRECONDITION_REQUIRED");
		assertProblem(putSchedules(product.id, "\"v9\"", every), 412, "VERSION_CONFLICT");
		assertProblem(putSchedules(UUID.randomUUID().toString(), "\"v1\"", every), 404, "CATALOG_PRODUCT_NOT_FOUND");
		assertProblem(putSchedules(product.id, "\"v1\"", """
				{"schedules": [{"startDate": null, "endDate": null,
				                "windows": [{"dayOfWeek": 8, "start": "7:00", "end": "24:00"}]}]}
				"""), 400, "VALIDATION_FAILED");
		assertThat(this.auth.perform(post(PRODUCTS + "/" + product.id + "/archive"), this.admin, true).getStatus())
			.isEqualTo(200);
		assertProblem(putSchedules(product.id, "\"v2\"", every), 409, "CATALOG_PRODUCT_INVALID_TRANSITION");
	}

	// --- public reads (AK2, AK3) -------------------------------------------------------------------------------

	@Test
	void theMenuShowsAProductOutsideItsWindowsAtTheAskedTime() throws Exception {
		Product breakfast = listed();
		Product weekend = listed();
		Product always = listed();
		schedules(breakfast, "{\"startDate\": null, \"endDate\": null, \"windows\": "
				+ "[{\"dayOfWeek\": null, \"start\": \"07:00\", \"end\": \"10:00\"}]}");
		schedules(weekend, "{\"startDate\": null, \"endDate\": null, \"windows\": "
				+ "[{\"dayOfWeek\": 6, \"start\": \"07:00\", \"end\": \"10:00\"}]}");

		String atNine = menu("?at=" + MONDAY_9AM);
		assertThat(availability(atNine, breakfast.id)).isEqualTo("AVAILABLE");
		assertThat(availability(atNine, weekend.id)).as("Saturdays only").isEqualTo("OUTSIDE_SELLING_HOURS");
		assertThat(availability(atNine, always.id)).isEqualTo("AVAILABLE");

		String atTen = menu("?at=2030-01-07T03:00:00Z");
		assertThat(availability(atTen, breakfast.id)).as("the end is exclusive").isEqualTo("OUTSIDE_SELLING_HOURS");
		assertThat(fromPrice(atTen, breakfast.id)).as("still priced").isEqualTo(30000);
	}

	@Test
	void aDateRangeLimitsTheScheduleAndAnyScheduleThatSellsWins() throws Exception {
		Product seasonal = listed();
		schedules(seasonal, "{\"startDate\": \"2030-12-01\", \"endDate\": \"2030-12-31\", \"windows\": []}");
		Product twoSchedules = listed();
		schedules(twoSchedules,
				"{\"startDate\": \"2030-12-01\", \"endDate\": \"2030-12-31\", \"windows\": []}, "
						+ "{\"startDate\": null, \"endDate\": \"2030-01-07\", \"windows\": "
						+ "[{\"dayOfWeek\": 1, \"start\": \"08:00\", \"end\": \"09:30\"}]}");

		String january = menu("?at=" + MONDAY_9AM);
		assertThat(availability(january, seasonal.id)).isEqualTo("OUTSIDE_SELLING_HOURS");
		assertThat(availability(january, twoSchedules.id)).isEqualTo("AVAILABLE");
		assertThat(availability(menu("?at=2030-12-31T16:59:00Z"), seasonal.id)).as("23:59 on the last day")
			.isEqualTo("AVAILABLE");
		assertThat(availability(menu("?at=2030-12-31T17:00:00Z"), seasonal.id)).as("the next local day")
			.isEqualTo("OUTSIDE_SELLING_HOURS");
	}

	@Test
	void pausedAndSoldOutComeBeforeSellingHours() throws Exception {
		Product paused = listed();
		schedules(paused, "{\"startDate\": \"2030-12-01\", \"endDate\": null, \"windows\": []}");
		this.jdbc.sql("UPDATE cat_branch_product_listing SET availability_state = 'PAUSED' WHERE branch_id = ? "
				+ "AND product_id = ?")
			.params(UuidBinary.toBytes(UUID.fromString(this.branch)), UuidBinary.toBytes(UUID.fromString(paused.id)))
			.update();

		assertThat(availability(menu("?at=" + MONDAY_9AM), paused.id)).isEqualTo("PAUSED");
	}

	@Test
	void theDetailStopsEverySizeOutsideHoursAndSummarisesTheSchedules() throws Exception {
		this.clock.freeze();
		LocalDate today = LocalDate.ofInstant(this.clock.instant(), ZoneId.of("Asia/Ho_Chi_Minh"));
		Product later = listed();
		schedules(later, "{\"startDate\": \"%s\", \"endDate\": null, \"windows\": []}".formatted(today.plusDays(1)));
		Product daily = listed();
		schedules(daily, "{\"startDate\": null, \"endDate\": null, \"windows\": ["
				+ "{\"dayOfWeek\": 6, \"start\": \"08:00\", \"end\": \"12:00\"}, "
				+ "{\"dayOfWeek\": 7, \"start\": \"08:00\", \"end\": \"12:00\"}, "
				+ "{\"dayOfWeek\": null, \"start\": \"14:00\", \"end\": \"16:00\"}]}, "
				+ "{\"startDate\": \"2030-12-01\", \"endDate\": \"2030-12-31\", \"windows\": []}");
		Product always = listed();

		String laterBody = productDetail(later.id);
		assertThat(JsonPath.<String>read(laterBody, "$.availability")).isEqualTo("OUTSIDE_SELLING_HOURS");
		assertThat(JsonPath.<List<String>>read(laterBody, "$.variants[*].availability"))
			.containsOnly("OUTSIDE_SELLING_HOURS");
		assertThat(JsonPath.<String>read(laterBody, "$.sellingSchedule.summary"))
			.isEqualTo("cả ngày · từ " + "%02d/%02d/%d".formatted(today.plusDays(1).getDayOfMonth(),
					today.plusDays(1).getMonthValue(), today.plusDays(1).getYear()));
		assertThat(JsonPath.<String>read(productDetail(daily.id), "$.sellingSchedule.summary"))
			.isEqualTo("14:00–16:00 hằng ngày, T7, CN 08:00–12:00; cả ngày · 01/12–31/12/2030");
		assertThat(JsonPath.<Object>read(productDetail(always.id), "$.sellingSchedule")).isNull();
	}

	@Test
	void resolveReportsALineOutsideSellingHours() throws Exception {
		Product breakfast = listed();
		schedules(breakfast, "{\"startDate\": null, \"endDate\": null, \"windows\": "
				+ "[{\"dayOfWeek\": null, \"start\": \"07:00\", \"end\": \"08:00\"}]}");
		String line = "{\"ref\": \"a\", \"productId\": \"%s\", \"variantId\": \"%s\", \"optionItemIds\": [], \"quantity\": 1}"
			.formatted(breakfast.id, breakfast.variants.getFirst());

		String outside = resolve(line, MONDAY_9AM);
		assertThat(JsonPath.<Boolean>read(outside, "$.lines[0].valid")).isFalse();
		assertThat(JsonPath.<List<String>>read(outside, "$.lines[0].issues[*].code"))
			.containsExactly("OUTSIDE_SELLING_HOURS");

		String inside = resolve(line, "2030-01-07T00:30:00Z");
		assertThat(JsonPath.<Boolean>read(inside, "$.lines[0].valid")).as(inside).isTrue();
	}

	// --- helpers ------------------------------------------------------------------------------------------------

	record Product(String id, List<String> variants) {
	}

	private MockHttpServletResponse putSchedules(String productId, String ifMatch, String json) throws Exception {
		var request = put(PRODUCTS + "/" + productId + "/selling-schedules").contentType(MediaType.APPLICATION_JSON)
			.content(json);
		if (ifMatch != null) {
			request.header("If-Match", ifMatch);
		}
		return this.auth.perform(request, this.admin, true);
	}

	private void schedules(Product product, String schedules) throws Exception {
		String etag = detail(product.id).getHeader("ETag");
		MockHttpServletResponse put = putSchedules(product.id, etag, "{\"schedules\": [" + schedules + "]}");
		assertThat(put.getStatus()).as(put.getContentAsString()).isEqualTo(200);
	}

	private MockHttpServletResponse detail(String productId) throws Exception {
		return this.auth.perform(get(PRODUCTS + "/" + productId), this.admin, false);
	}

	private String menu(String query) throws Exception {
		MockHttpServletResponse menu = this.mvc.perform(get("/api/public/v1/branches/" + this.branch + "/menu" + query))
			.andReturn()
			.getResponse();
		assertThat(menu.getStatus()).as(menu.getContentAsString()).isEqualTo(200);
		return menu.getContentAsString();
	}

	private String productDetail(String productId) throws Exception {
		MockHttpServletResponse detail = this.mvc
			.perform(get("/api/public/v1/branches/" + this.branch + "/products/" + productId))
			.andReturn()
			.getResponse();
		assertThat(detail.getStatus()).as(detail.getContentAsString()).isEqualTo(200);
		return detail.getContentAsString();
	}

	private String resolve(String line, String at) throws Exception {
		MockHttpServletResponse response = this.mvc
			.perform(post("/api/public/v1/branches/" + this.branch + "/catalog/resolve")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"lines\": [" + line + "], \"at\": \"" + at + "\"}"))
			.andReturn()
			.getResponse();
		assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
		return response.getContentAsString();
	}

	private static String availability(String menu, String productId) {
		List<String> found = JsonPath.read(menu, "$..products[?(@.productId == '" + productId + "')].availability");
		assertThat(found).as(menu).hasSize(1);
		return found.getFirst();
	}

	private static int fromPrice(String menu, String productId) {
		return JsonPath.<List<Integer>>read(menu, "$..products[?(@.productId == '" + productId + "')].fromPrice")
			.getFirst();
	}

	private List<Map<String, Object>> audits(String id, String action) {
		return this.jdbc.sql("SELECT action FROM aud_audit_log WHERE entity_id = ? AND action = ?")
			.params(UuidBinary.toBytes(UUID.fromString(id)), action)
			.query()
			.listOfRows();
	}

	private String branch() throws Exception {
		String code = "S-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
		MockHttpServletResponse created = this.auth.perform(post("/api/backoffice/v1/branches")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"branchCode": "%s", "name": "HaYi lịch", "address": {"line": "1 Lê Lợi", "province": "TP.HCM"}}
					""".formatted(code)), this.admin, true);
		assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
		String id = JsonPath.read(created.getContentAsString(), "$.branchId");
		this.jdbc.sql("UPDATE brn_branch SET status = 'ACTIVE' WHERE id = ?")
			.param(UuidBinary.toBytes(UUID.fromString(id)))
			.update();
		return id;
	}

	private String category() throws Exception {
		MockHttpServletResponse created = this.auth.perform(post("/api/backoffice/v1/categories")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"slug\": \"sc-" + UUID.randomUUID().toString().substring(0, 8) + "\", \"name\": \"Lịch\"}"),
				this.admin, true);
		assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
		return JsonPath.read(created.getContentAsString(), "$.categoryId");
	}

	/** An {@code ACTIVE} Product at version 1 with one 30 000 VND Variant. */
	private Product product() throws Exception {
		String code = ("sc-" + UUID.randomUUID().toString().substring(0, 8)).toUpperCase();
		MockHttpServletResponse created = this.auth.perform(post(PRODUCTS).contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"productCode": "%s", "name": "Món %s", "slug": "%s", "primaryCategoryId": "%s",
					 "variants": [{"sku": "%s-0", "name": "S", "basePrice": 30000, "isDefault": true}]}
					""".formatted(code, code, code.toLowerCase(), this.category, code)), this.admin, true);
		assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
		String body = created.getContentAsString();
		String id = JsonPath.read(body, "$.productId");
		MockHttpServletResponse activated = this.auth.perform(post(PRODUCTS + "/" + id + "/activate"), this.admin,
				true);
		assertThat(activated.getStatus()).as(activated.getContentAsString()).isEqualTo(200);
		assertThat(activated.getHeader("ETag")).isEqualTo("\"v1\"");
		return new Product(id, JsonPath.read(body, "$.variants[*].variantId"));
	}

	/** A Product listed {@code AVAILABLE} at the Branch. */
	private Product listed() throws Exception {
		Product product = product();
		MockHttpServletResponse listed = this.auth.perform(put("/api/backoffice/v1/branches/" + this.branch
				+ "/listings/" + product.id)
			.contentType(MediaType.APPLICATION_JSON)
			.header("If-Match", "\"v0\"")
			.content("{\"availabilityState\": \"AVAILABLE\"}"), this.admin, true);
		assertThat(listed.getStatus()).as(listed.getContentAsString()).isEqualTo(200);
		return product;
	}

	private static void assertProblem(MockHttpServletResponse response, int status, String code) throws Exception {
		assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
		assertThat(JsonPath.<String>read(response.getContentAsString(), "$.code")).isEqualTo(code);
	}

}
