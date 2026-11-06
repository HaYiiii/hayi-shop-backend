package vn.hayi.shop.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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

/** Owner/Admin manage the brand-level option library (Catalog §5.4, API 08 §1.5, Decision AB). */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MutableClockConfiguration.class, IdentityFixtures.class })
class BackofficeOptionGroupTest {

	static final String GROUPS = "/api/backoffice/v1/option-groups";

	static final MediaType MERGE_PATCH = MediaType.parseMediaType("application/merge-patch+json");

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

	@BeforeEach
	void setUp() {
		this.auth = new BackofficeAuth(this.mvc, this.clock);
		this.admin = this.fixtures.openSession(this.fixtures.storeAdmin(IdentityFixtures.uniqueEmail()));
	}

	// --- create -------------------------------------------------------------------------------------------------

	@Test
	void createsAGroupWithItemsAndAuditsIt() throws Exception {
		String code = code("sua");
		MockHttpServletResponse created = create("""
				{"code": "%s", "name": "Sữa", "selectionType": "SINGLE", "minSelect": 1, "maxSelect": 1,
				 "items": [{"code": "tuoi", "name": "Sữa tươi", "isDefaultSelected": true},
				           {"code": "yen-mach", "name": "Sữa yến mạch", "defaultExtraPrice": 10000}]}
				""".formatted(code.toLowerCase()));

		assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
		assertThat(created.getHeader("ETag")).isEqualTo("\"v0\"");
		String body = created.getContentAsString();
		String id = JsonPath.read(body, "$.optionGroupId");
		assertThat(created.getHeader("Location")).isEqualTo(GROUPS + "/" + id);
		assertThat(JsonPath.<String>read(body, "$.code")).isEqualTo(code);
		assertThat(JsonPath.<String>read(body, "$.status")).isEqualTo("ACTIVE");
		assertThat(JsonPath.<List<String>>read(body, "$.items[*].code")).containsExactly("TUOI", "YEN-MACH");
		assertThat(JsonPath.<List<Integer>>read(body, "$.items[*].defaultExtraPrice")).containsExactly(0, 10000);
		assertThat(JsonPath.<List<Boolean>>read(body, "$.items[*].isDefaultSelected")).containsExactly(true, false);
		assertThat(JsonPath.<List<Integer>>read(body, "$.items[*].sortOrder")).containsExactly(0, 1);
		assertThat(audits(id, "OPTION_GROUP_CREATE")).hasSize(1);
	}

	@Test
	void minMaxMustMakeSense() throws Exception {
		assertProblem(create(group("MULTIPLE", -1, 2)), 422, "OPTION_GROUP_MINMAX_INVALID");
		assertProblem(create(group("MULTIPLE", 2, 1)), 422, "OPTION_GROUP_MINMAX_INVALID");
		assertProblem(create(group("MULTIPLE", 0, 0)), 422, "OPTION_GROUP_MINMAX_INVALID");
		assertProblem(create(group("SINGLE", 0, 2)), 422, "OPTION_GROUP_MINMAX_INVALID");
		assertProblem(create("""
				{"code": "%s", "name": "Đá", "selectionType": "SINGLE", "minSelect": 0, "maxSelect": 1,
				 "items": [{"code": "it", "name": "Ít", "isDefaultSelected": true},
				           {"code": "nhieu", "name": "Nhiều", "isDefaultSelected": true}]}
				""".formatted(code("da"))), 422, "OPTION_GROUP_MINMAX_INVALID");
	}

	@Test
	void codesAreUniqueGroupWideAndItemWithinTheGroup() throws Exception {
		String code = code("size");
		assertThat(create(group("SINGLE", 1, 1, code)).getStatus()).isEqualTo(201);

		assertProblem(create(group("SINGLE", 1, 1, code.toLowerCase())), 409, "CATALOG_CODE_TAKEN");
		assertProblem(create("""
				{"code": "%s", "name": "Lặp", "selectionType": "MULTIPLE", "minSelect": 0, "maxSelect": 3,
				 "items": [{"code": "a", "name": "A"}, {"code": "A", "name": "A2"}]}
				""".formatted(code("lap"))), 409, "CATALOG_CODE_TAKEN");
	}

	@Test
	void aNegativePriceIsRefused() throws Exception {
		MockHttpServletResponse refused = create("""
				{"code": "%s", "name": "Topping", "selectionType": "MULTIPLE", "minSelect": 0, "maxSelect": 3,
				 "items": [{"code": "tc", "name": "Trân châu", "defaultExtraPrice": -1}]}
				""".formatted(code("top")));

		assertProblem(refused, 422, "CATALOG_PRICE_NEGATIVE");
		assertThat(JsonPath.<List<String>>read(refused.getContentAsString(), "$.errors[*].field"))
			.containsExactly("items[0].defaultExtraPrice");
	}

	// --- prices need CATALOG_PRICE_MANAGE -----------------------------------------------------------------------

	@Test
	void settingOrChangingAPriceNeedsCatalogPriceManage() throws Exception {
		Session noPrice = this.fixtures
			.openSession(CatalogFixtures.catalogManagerWithoutPricePermission(this.jdbc, this.fixtures));
		String priced = """
				{"code": "%s", "name": "Topping", "selectionType": "MULTIPLE", "minSelect": 0, "maxSelect": 3,
				 "items": [{"code": "tc", "name": "Trân châu", "defaultExtraPrice": 5000}]}
				""".formatted(code("gia"));
		assertProblem(this.auth.perform(post(GROUPS).contentType(MediaType.APPLICATION_JSON).content(priced), noPrice,
				true), 403, "PERMISSION_DENIED");

		MockHttpServletResponse free = this.auth.perform(post(GROUPS).contentType(MediaType.APPLICATION_JSON)
			.content(group("MULTIPLE", 0, 3)), noPrice, true);
		assertThat(free.getStatus()).as("free items need no price permission").isEqualTo(201);
		String id = JsonPath.read(free.getContentAsString(), "$.optionGroupId");
		MockHttpServletResponse added = this.auth.perform(post(GROUPS + "/" + id + "/items")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"code\": \"free\", \"name\": \"Miễn phí\"}"), noPrice, true);
		assertThat(added.getStatus()).as(added.getContentAsString()).isEqualTo(201);
		String itemId = JsonPath.read(added.getContentAsString(), "$.items[0].optionItemId");

		assertProblem(this.auth.perform(patch(GROUPS + "/" + id + "/items/" + itemId).contentType(MERGE_PATCH)
			.header("If-Match", "\"v1\"")
			.content("{\"defaultExtraPrice\": 3000}"), noPrice, true), 403, "PERMISSION_DENIED");
		assertThat(this.auth.perform(patch(GROUPS + "/" + id + "/items/" + itemId).contentType(MERGE_PATCH)
			.header("If-Match", "\"v1\"")
			.content("{\"defaultExtraPrice\": 0, \"name\": \"Không giá\"}"), noPrice, true).getStatus())
			.as("same price is not a change")
			.isEqualTo(200);
	}

	@Test
	void writesNeedCatalogManageWhileCatalogViewCanRead() throws Exception {
		UUID managerId = this.fixtures.branchStaff();
		this.fixtures.assignToBranch(managerId, anyBranch(), "MANAGER");
		Session manager = this.fixtures.openSession(managerId);

		assertProblem(this.auth.perform(post(GROUPS).contentType(MediaType.APPLICATION_JSON)
			.content(group("SINGLE", 1, 1)), manager, true), 403, "PERMISSION_DENIED");
		assertThat(this.auth.perform(get(GROUPS), manager, false).getStatus()).isEqualTo(200);
	}

	// --- edit the group -----------------------------------------------------------------------------------------

	@Test
	void patchChangesTheGroupAndAuditsOnlyChangedFields() throws Exception {
		String id = createdId(group("MULTIPLE", 0, 3));

		MockHttpServletResponse updated = updateGroup(id, "\"v0\"", "{\"name\": \"Topping mới\", \"maxSelect\": 5}");

		assertThat(updated.getStatus()).as(updated.getContentAsString()).isEqualTo(200);
		assertThat(updated.getHeader("ETag")).isEqualTo("\"v1\"");
		assertThat(JsonPath.<Integer>read(updated.getContentAsString(), "$.maxSelect")).isEqualTo(5);
		List<Map<String, Object>> audits = audits(id, "OPTION_GROUP_UPDATE");
		assertThat(audits).hasSize(1);
		assertThat(JsonPath.<Map<String, Object>>read((String) audits.getFirst().get("new_value"), "$"))
			.containsOnlyKeys("name", "maxSelect");

		MockHttpServletResponse same = updateGroup(id, "\"v1\"", "{\"maxSelect\": 5}");
		assertThat(same.getHeader("ETag")).as("no change keeps the version").isEqualTo("\"v1\"");
		assertThat(audits(id, "OPTION_GROUP_UPDATE")).hasSize(1);
	}

	@Test
	void patchIsCheckedAgainstTheItemsAndTheVersion() throws Exception {
		String id = createdId("""
				{"code": "%s", "name": "Đá", "selectionType": "MULTIPLE", "minSelect": 0, "maxSelect": 2,
				 "items": [{"code": "a", "name": "A", "isDefaultSelected": true},
				           {"code": "b", "name": "B", "isDefaultSelected": true}]}
				""".formatted(code("check")));

		assertProblem(updateGroup(id, "\"v0\"", "{\"selectionType\": \"SINGLE\", \"maxSelect\": 1}"), 422,
				"OPTION_GROUP_MINMAX_INVALID");
		assertProblem(updateGroup(id, null, "{\"name\": \"X\"}"), 428, "PRECONDITION_REQUIRED");
		assertProblem(updateGroup(id, "\"v9\"", "{\"name\": \"X\"}"), 412, "VERSION_CONFLICT");
		assertInvalid(updateGroup(id, "\"v0\"", "{\"code\": \"OTHER\"}"), "code");
		assertInvalid(updateGroup(id, "\"v0\"", "{\"minSelect\": null}"), "minSelect");
	}

	// --- items --------------------------------------------------------------------------------------------------

	@Test
	void anItemChangeMovesTheGroupVersion() throws Exception {
		String id = createdId(group("MULTIPLE", 0, 3));

		MockHttpServletResponse added = addItem(id, "{\"code\": \"tc\", \"name\": \"Trân châu\", \"defaultExtraPrice\": 5000}");
		assertThat(added.getStatus()).as(added.getContentAsString()).isEqualTo(201);
		assertThat(added.getHeader("ETag")).isEqualTo("\"v1\"");
		String itemId = JsonPath.read(added.getContentAsString(), "$.items[0].optionItemId");
		assertThat(audits(id, "OPTION_ITEM_CREATE")).hasSize(1);

		MockHttpServletResponse edited = updateItem(id, itemId, "\"v1\"",
				"{\"defaultExtraPrice\": 6000, \"isDefaultSelected\": true}");
		assertThat(edited.getStatus()).as(edited.getContentAsString()).isEqualTo(200);
		assertThat(edited.getHeader("ETag")).isEqualTo("\"v2\"");
		assertThat(JsonPath.<List<Integer>>read(edited.getContentAsString(), "$.items[*].defaultExtraPrice"))
			.containsExactly(6000);
		assertProblem(updateItem(id, itemId, "\"v1\"", "{\"name\": \"Cũ\"}"), 412, "VERSION_CONFLICT");
		List<Map<String, Object>> audits = audits(id, "OPTION_ITEM_UPDATE");
		assertThat(audits).hasSize(1);
		assertThat(JsonPath.<String>read((String) audits.getFirst().get("new_value"), "$.optionItemId"))
			.isEqualTo(itemId);
	}

	@Test
	void itemRulesMatchTheGroup() throws Exception {
		String id = createdId("""
				{"code": "%s", "name": "Size", "selectionType": "SINGLE", "minSelect": 1, "maxSelect": 1,
				 "items": [{"code": "m", "name": "M", "isDefaultSelected": true}, {"code": "l", "name": "L"}]}
				""".formatted(code("rule")));
		String l = JsonPath.read(detail(id).getContentAsString(), "$.items[1].optionItemId");

		assertProblem(addItem(id, "{\"code\": \"M\", \"name\": \"M2\"}"), 409, "CATALOG_CODE_TAKEN");
		assertProblem(addItem(id, "{\"code\": \"xl\", \"name\": \"XL\", \"isDefaultSelected\": true}"), 422,
				"OPTION_GROUP_MINMAX_INVALID");
		assertProblem(updateItem(id, l, "\"v0\"", "{\"isDefaultSelected\": true}"), 422,
				"OPTION_GROUP_MINMAX_INVALID");
		assertProblem(updateItem(id, l, "\"v0\"", "{\"defaultExtraPrice\": -5}"), 422, "CATALOG_PRICE_NEGATIVE");
		assertProblem(updateItem(id, UUID.randomUUID().toString(), "\"v0\"", "{\"name\": \"X\"}"), 404,
				"CATALOG_OPTION_ITEM_NOT_FOUND");
	}

	// --- archive ------------------------------------------------------------------------------------------------

	@Test
	void archivingAnItemFreesItsDefaultSlotAndIsFinal() throws Exception {
		String id = createdId("""
				{"code": "%s", "name": "Size", "selectionType": "SINGLE", "minSelect": 1, "maxSelect": 1,
				 "items": [{"code": "m", "name": "M", "isDefaultSelected": true}, {"code": "l", "name": "L"}]}
				""".formatted(code("arch")));
		String body = detail(id).getContentAsString();
		String m = JsonPath.read(body, "$.items[0].optionItemId");
		String l = JsonPath.read(body, "$.items[1].optionItemId");

		MockHttpServletResponse archived = this.auth.perform(post(GROUPS + "/" + id + "/items/" + m + "/archive"),
				this.admin, true);
		assertThat(archived.getStatus()).as(archived.getContentAsString()).isEqualTo(200);
		assertThat(JsonPath.<List<String>>read(archived.getContentAsString(), "$.items[*].status"))
			.containsExactly("ARCHIVED", "ACTIVE");
		assertThat(updateItem(id, l, "\"v1\"", "{\"isDefaultSelected\": true}").getStatus()).isEqualTo(200);
		assertProblem(updateItem(id, m, "\"v2\"", "{\"name\": \"X\"}"), 409, "CATALOG_OPTION_ARCHIVED");
		assertProblem(this.auth.perform(post(GROUPS + "/" + id + "/items/" + m + "/archive"), this.admin, true), 409,
				"CATALOG_OPTION_ARCHIVED");
		assertThat(audits(id, "OPTION_ITEM_ARCHIVE")).hasSize(1);
	}

	@Test
	void anArchivedGroupIsHiddenAndFrozen() throws Exception {
		String id = createdId(group("MULTIPLE", 0, 3));

		MockHttpServletResponse archived = this.auth.perform(post(GROUPS + "/" + id + "/archive"), this.admin, true);
		assertThat(archived.getStatus()).as(archived.getContentAsString()).isEqualTo(200);
		assertThat(JsonPath.<String>read(archived.getContentAsString(), "$.status")).isEqualTo("ARCHIVED");
		assertThat(audits(id, "OPTION_GROUP_ARCHIVE")).hasSize(1);

		assertThat(groupIds(false)).doesNotContain(id);
		assertThat(groupIds(true)).contains(id);
		assertProblem(updateGroup(id, "\"v1\"", "{\"name\": \"X\"}"), 409, "CATALOG_OPTION_ARCHIVED");
		assertProblem(addItem(id, "{\"code\": \"x\", \"name\": \"X\"}"), 409, "CATALOG_OPTION_ARCHIVED");
		assertProblem(this.auth.perform(post(GROUPS + "/" + id + "/archive"), this.admin, true), 409,
				"CATALOG_OPTION_ARCHIVED");
	}

	@Test
	void unknownGroupIsNotFound() throws Exception {
		assertProblem(this.auth.perform(get(GROUPS + "/" + UUID.randomUUID()), this.admin, false), 404,
				"CATALOG_OPTION_GROUP_NOT_FOUND");
	}

	// --- helpers ------------------------------------------------------------------------------------------------

	private UUID anyBranch() throws Exception {
		MockHttpServletResponse created = this.auth.perform(post("/api/backoffice/v1/branches")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"branchCode": "O-%s", "name": "HaYi Test",
					 "address": {"line": "1 Lê Lợi", "province": "TP.HCM"}}
					""".formatted(UUID.randomUUID().toString().substring(0, 8))), this.admin, true);
		assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
		return UUID.fromString(JsonPath.read(created.getContentAsString(), "$.branchId"));
	}

	private static String code(String prefix) {
		return (prefix + "-" + UUID.randomUUID().toString().substring(0, 8)).toUpperCase();
	}

	private static String group(String type, int min, int max) {
		return group(type, min, max, code("grp"));
	}

	private static String group(String type, int min, int max, String code) {
		return """
				{"code": "%s", "name": "Nhóm", "selectionType": "%s", "minSelect": %d, "maxSelect": %d}
				""".formatted(code, type, min, max);
	}

	private MockHttpServletResponse create(String json) throws Exception {
		return this.auth.perform(post(GROUPS).contentType(MediaType.APPLICATION_JSON).content(json), this.admin, true);
	}

	private String createdId(String json) throws Exception {
		MockHttpServletResponse created = create(json);
		assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
		return JsonPath.read(created.getContentAsString(), "$.optionGroupId");
	}

	private MockHttpServletResponse detail(String id) throws Exception {
		MockHttpServletResponse response = this.auth.perform(get(GROUPS + "/" + id), this.admin, false);
		assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
		return response;
	}

	private List<String> groupIds(boolean includeArchived) throws Exception {
		MockHttpServletResponse response = this.auth
			.perform(get(GROUPS).param("includeArchived", Boolean.toString(includeArchived)), this.admin, false);
		assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
		return JsonPath.read(response.getContentAsString(), "$.items[*].optionGroupId");
	}

	private MockHttpServletResponse updateGroup(String id, String ifMatch, String json) throws Exception {
		return withIfMatch(patch(GROUPS + "/" + id).contentType(MERGE_PATCH).content(json), ifMatch);
	}

	private MockHttpServletResponse addItem(String id, String json) throws Exception {
		return this.auth.perform(post(GROUPS + "/" + id + "/items").contentType(MediaType.APPLICATION_JSON)
			.content(json), this.admin, true);
	}

	private MockHttpServletResponse updateItem(String id, String itemId, String ifMatch, String json)
			throws Exception {
		return withIfMatch(patch(GROUPS + "/" + id + "/items/" + itemId).contentType(MERGE_PATCH).content(json),
				ifMatch);
	}

	private MockHttpServletResponse withIfMatch(MockHttpServletRequestBuilder request, String ifMatch)
			throws Exception {
		if (ifMatch != null) {
			request.header("If-Match", ifMatch);
		}
		return this.auth.perform(request, this.admin, true);
	}

	private List<Map<String, Object>> audits(String id, String action) {
		return this.jdbc
			.sql("SELECT CAST(old_value AS CHAR) AS old_value, CAST(new_value AS CHAR) AS new_value"
					+ " FROM aud_audit_log WHERE entity_id = ? AND action = ?")
			.param(UuidBinary.toBytes(UUID.fromString(id)))
			.param(action)
			.query()
			.listOfRows();
	}

	private static void assertInvalid(MockHttpServletResponse response, String field) throws Exception {
		assertProblem(response, 400, "VALIDATION_FAILED");
		assertThat(JsonPath.<List<String>>read(response.getContentAsString(), "$.errors[*].field")).contains(field);
	}

	private static void assertProblem(MockHttpServletResponse response, int status, String code) throws Exception {
		assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
		assertThat(JsonPath.<String>read(response.getContentAsString(), "$.code")).isEqualTo(code);
	}

}
