package vn.hayi.shop.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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

/**
 * Owner/Admin manage the brand's Category tree, at most 2 levels (Catalog §5.3, §5.11, API 08 §1.2, Decision Y).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MutableClockConfiguration.class, IdentityFixtures.class })
class BackofficeCategoryTest {

	static final String CATEGORIES = "/api/backoffice/v1/categories";

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

	UUID adminId;

	@BeforeEach
	void setUp() {
		this.auth = new BackofficeAuth(this.mvc, this.clock);
		this.adminId = this.fixtures.storeAdmin(IdentityFixtures.uniqueEmail());
		this.admin = this.fixtures.openSession(this.adminId);
	}

	// --- create -------------------------------------------------------------------------------------------------

	@Test
	void createsRootAndChildCategoriesLastAmongTheirSiblings() throws Exception {
		String rootSlug = slug("matcha");
		MockHttpServletResponse root = create(Map.of("slug", rootSlug, "name", "Matcha", "description", "Trà xanh"));

		assertThat(root.getStatus()).as(root.getContentAsString()).isEqualTo(201);
		assertThat(root.getHeader("ETag")).isEqualTo("\"v0\"");
		String rootId = JsonPath.read(root.getContentAsString(), "$.categoryId");
		assertThat(root.getHeader("Location")).isEqualTo(CATEGORIES + "/" + rootId);
		assertThat(JsonPath.<String>read(root.getContentAsString(), "$.slug")).isEqualTo(rootSlug);
		assertThat(JsonPath.<String>read(root.getContentAsString(), "$.status")).isEqualTo("ACTIVE");
		assertThat(JsonPath.<Object>read(root.getContentAsString(), "$.parentId")).isNull();

		String first = createdId(Map.of("slug", slug("latte"), "name", "Latte", "parentId", rootId));
		String second = createdId(Map.of("slug", slug("da-xay"), "name", "Đá xay", "parentId", rootId));

		Map<String, Object> node = node(tree(false), rootId);
		assertThat(node).containsEntry("name", "Matcha").containsEntry("description", "Trà xanh");
		assertThat(JsonPath.<List<String>>read(node, "$.children[*].categoryId")).containsExactly(first, second);
		assertThat(JsonPath.<List<Integer>>read(node, "$.children[*].sortOrder")).containsExactly(0, 1);
		assertThat(JsonPath.<List<String>>read(node, "$.children[*].parentId")).containsOnly(rootId);
		assertThat(audits(rootId, "CATEGORY_CREATE")).hasSize(1);
	}

	@Test
	void childOfChildIsRejectedAsDepthExceeded() throws Exception {
		String root = createdId(Map.of("slug", slug("tra"), "name", "Trà"));
		String child = createdId(Map.of("slug", slug("tra-sua"), "name", "Trà sữa", "parentId", root));

		assertProblem(create(Map.of("slug", slug("chan-chau"), "name", "Trân châu", "parentId", child)), 422,
				"CATALOG_CATEGORY_DEPTH_EXCEEDED");
	}

	@Test
	void slugIsTakenEvenByAnArchivedCategory() throws Exception {
		String taken = slug("banh");
		String id = createdId(Map.of("slug", taken, "name", "Bánh"));
		archive(id, null);

		assertProblem(create(Map.of("slug", taken, "name", "Bánh 2")), 409, "CATALOG_SLUG_TAKEN");
	}

	@Test
	void slugMustBeLowerCaseKebab() throws Exception {
		assertInvalid(create(Map.of("slug", "Matcha Latte", "name", "X")), "slug");
		assertInvalid(create(Map.of("slug", "-matcha", "name", "X")), "slug");
		assertInvalid(create(Map.of("slug", slug("ok"), "name", "  ")), "name");
	}

	@Test
	void parentMustExistAndBeActive() throws Exception {
		assertProblem(create(Map.of("slug", slug("x"), "name", "X", "parentId", UUID.randomUUID().toString())), 404,
				"CATALOG_CATEGORY_NOT_FOUND");

		String archived = createdId(Map.of("slug", slug("cu"), "name", "Cũ"));
		archive(archived, null);
		assertProblem(create(Map.of("slug", slug("x"), "name", "X", "parentId", archived)), 409,
				"CATALOG_CATEGORY_ARCHIVED");
	}

	@Test
	void concurrentCreatesOfOneSlugLetOnlyOneWin() throws Exception {
		String contested = slug("song-song");
		CountDownLatch start = new CountDownLatch(1);
		try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
			List<Future<Integer>> results = new ArrayList<>();
			for (int i = 0; i < 2; i++) {
				results.add(pool.submit(() -> {
					start.await();
					return create(Map.of("slug", contested, "name", "Song song")).getStatus();
				}));
			}
			start.countDown();
			List<Integer> statuses = new ArrayList<>();
			for (Future<Integer> result : results) {
				statuses.add(result.get(30, TimeUnit.SECONDS));
			}
			assertThat(statuses).containsExactlyInAnyOrder(201, 409);
		}
	}

	// --- read ---------------------------------------------------------------------------------------------------

	@Test
	void archivedCategoriesAreHiddenUnlessAsked() throws Exception {
		String kept = createdId(Map.of("slug", slug("giu"), "name", "Giữ"));
		String archived = createdId(Map.of("slug", slug("bo"), "name", "Bỏ"));
		archive(archived, null);

		assertThat(rootIds(tree(false))).contains(kept).doesNotContain(archived);
		assertThat(rootIds(tree(true))).contains(kept, archived);
		MockHttpServletResponse detail = detail(archived);
		assertThat(JsonPath.<String>read(detail.getContentAsString(), "$.status")).isEqualTo("ARCHIVED");
		assertThat(detail.getHeader("ETag")).isEqualTo("\"v1\"");
	}

	@Test
	void unknownCategoryIsNotFound() throws Exception {
		MockHttpServletResponse response = this.auth.perform(get(CATEGORIES + "/" + UUID.randomUUID()), this.admin,
				false);
		assertProblem(response, 404, "CATALOG_CATEGORY_NOT_FOUND");
	}

	// --- update -------------------------------------------------------------------------------------------------

	@Test
	void patchChangesOnlySentFieldsAndAuditsTheDiff() throws Exception {
		String id = createdId(Map.of("slug", slug("ca-phe"), "name", "Cà phê", "description", "Đậm"));
		String newSlug = slug("coffee");

		MockHttpServletResponse updated = update(id, "\"v0\"",
				"{\"name\": \"Coffee\", \"slug\": \"" + newSlug + "\", \"description\": null}");

		assertThat(updated.getStatus()).as(updated.getContentAsString()).isEqualTo(200);
		assertThat(updated.getHeader("ETag")).isEqualTo("\"v1\"");
		assertThat(JsonPath.<String>read(updated.getContentAsString(), "$.name")).isEqualTo("Coffee");
		assertThat(JsonPath.<String>read(updated.getContentAsString(), "$.slug")).isEqualTo(newSlug);
		assertThat(JsonPath.<Object>read(updated.getContentAsString(), "$.description")).isNull();
		List<Map<String, Object>> audits = audits(id, "CATEGORY_UPDATE");
		assertThat(audits).hasSize(1);
		Map<String, Object> oldValue = JsonPath.read((String) audits.getFirst().get("old_value"), "$");
		Map<String, Object> newValue = JsonPath.read((String) audits.getFirst().get("new_value"), "$");
		assertThat(oldValue).containsOnlyKeys("name", "slug", "description").containsEntry("name", "Cà phê");
		assertThat(newValue).containsOnlyKeys("name", "slug", "description").containsEntry("description", null);
		assertThat(audits.getFirst()).containsEntry("actor_user_id", UuidBinary.toBytes(this.adminId))
			.containsEntry("entity_type", "CATEGORY");
	}

	@Test
	void patchThatChangesNothingKeepsVersionAndIsNotAudited() throws Exception {
		String id = createdId(Map.of("slug", slug("same"), "name", "Như cũ"));

		MockHttpServletResponse same = update(id, "\"v0\"", "{\"name\": \"Như cũ\", \"parentId\": null}");

		assertThat(same.getStatus()).as(same.getContentAsString()).isEqualTo(200);
		assertThat(same.getHeader("ETag")).isEqualTo("\"v0\"");
		assertThat(audits(id, "CATEGORY_UPDATE")).isEmpty();
	}

	@Test
	void patchRequiresTheCurrentVersion() throws Exception {
		String id = createdId(Map.of("slug", slug("ver"), "name", "Phiên bản"));

		assertProblem(update(id, null, "{\"name\": \"X\"}"), 428, "PRECONDITION_REQUIRED");
		assertProblem(update(id, "\"v3\"", "{\"name\": \"X\"}"), 412, "VERSION_CONFLICT");
		assertThat(detail(id).getHeader("ETag")).isEqualTo("\"v0\"");
	}

	@Test
	void invalidHeaderIsReportedByItsHttpName() throws Exception {
		String id = createdId(Map.of("slug", slug("header"), "name", "Header"));

		assertInvalid(update(id, "\"v" + "0".repeat(70) + "\"", "{\"name\": \"X\"}"), "If-Match");
	}

	@Test
	void patchRefusesReadOnlyKeysAndBlankRequiredFields() throws Exception {
		String id = createdId(Map.of("slug", slug("ro"), "name", "Chỉ đọc"));

		assertInvalid(update(id, "\"v0\"", "{\"status\": \"ARCHIVED\"}"), "status");
		assertInvalid(update(id, "\"v0\"", "{\"sortOrder\": 3}"), "sortOrder");
		assertInvalid(update(id, "\"v0\"", "{\"name\": null}"), "name");
		assertInvalid(update(id, "\"v0\"", "{\"slug\": \"Không Hợp Lệ\"}"), "slug");
		assertInvalid(update(id, "\"v0\"", "{\"parentId\": \"not-a-uuid\"}"), "parentId");
	}

	@Test
	void slugChangeToATakenSlugConflicts() throws Exception {
		String taken = slug("da-co");
		createdId(Map.of("slug", taken, "name", "Đã có"));
		String id = createdId(Map.of("slug", slug("doi"), "name", "Đổi"));

		assertProblem(update(id, "\"v0\"", "{\"slug\": \"" + taken + "\"}"), 409, "CATALOG_SLUG_TAKEN");
	}

	@Test
	void movingUnderAnotherRootPutsItLastThereAndRootMovesUp() throws Exception {
		String from = createdId(Map.of("slug", slug("from"), "name", "Từ"));
		String to = createdId(Map.of("slug", slug("to"), "name", "Đến"));
		String existing = createdId(Map.of("slug", slug("existing"), "name", "Có sẵn", "parentId", to));
		String moved = createdId(Map.of("slug", slug("moved"), "name", "Chuyển", "parentId", from));

		MockHttpServletResponse response = update(moved, "\"v0\"", "{\"parentId\": \"" + to + "\"}");

		assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
		Map<String, Object> target = node(tree(false), to);
		assertThat(JsonPath.<List<String>>read(target, "$.children[*].categoryId")).containsExactly(existing, moved);
		assertThat(JsonPath.<List<Object>>read(node(tree(false), from), "$.children")).isEmpty();

		MockHttpServletResponse promoted = update(moved, "\"v1\"", "{\"parentId\": null}");
		assertThat(promoted.getStatus()).as(promoted.getContentAsString()).isEqualTo(200);
		assertThat(rootIds(tree(false))).contains(moved);
	}

	@Test
	void newParentMustExistAndBeActive() throws Exception {
		String id = createdId(Map.of("slug", slug("lac"), "name", "Lạc"));
		String archived = createdId(Map.of("slug", slug("da-luu"), "name", "Đã lưu"));
		archive(archived, null);

		assertProblem(update(id, "\"v0\"", "{\"parentId\": \"" + UUID.randomUUID() + "\"}"), 404,
				"CATALOG_CATEGORY_NOT_FOUND");
		assertProblem(update(id, "\"v0\"", "{\"parentId\": \"" + archived + "\"}"), 409,
				"CATALOG_CATEGORY_ARCHIVED");
		assertThat(detail(id).getHeader("ETag")).isEqualTo("\"v0\"");
	}

	@Test
	void concurrentRenamesToOneSlugWhileMovingLetOnlyOneWin() throws Exception {
		for (int round = 0; round < 5; round++) {
			String contested = slug("dua");
			List<String> ids = new ArrayList<>();
			List<String> parents = new ArrayList<>();
			for (int i = 0; i < 2; i++) {
				ids.add(createdId(Map.of("slug", slug("nguon"), "name", "Nguồn")));
				parents.add(createdId(Map.of("slug", slug("dich"), "name", "Đích")));
			}
			CountDownLatch start = new CountDownLatch(1);
			try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
				List<Future<Integer>> results = new ArrayList<>();
				for (int i = 0; i < 2; i++) {
					String id = ids.get(i);
					String parent = parents.get(i);
					results.add(pool.submit(() -> {
						start.await();
						return update(id, "\"v0\"",
								"{\"slug\": \"" + contested + "\", \"parentId\": \"" + parent + "\"}").getStatus();
					}));
				}
				start.countDown();
				List<Integer> statuses = new ArrayList<>();
				for (Future<Integer> result : results) {
					statuses.add(result.get(30, TimeUnit.SECONDS));
				}
				assertThat(statuses).as("round " + round).containsExactlyInAnyOrder(200, 409);
			}
		}
	}

	@Test
	void movingACategoryWithChildrenUnderAnotherIsRejected() throws Exception {
		String parent = createdId(Map.of("slug", slug("cha"), "name", "Cha"));
		createdId(Map.of("slug", slug("con"), "name", "Con", "parentId", parent));
		String other = createdId(Map.of("slug", slug("khac"), "name", "Khác"));

		assertProblem(update(parent, "\"v0\"", "{\"parentId\": \"" + other + "\"}"), 422,
				"CATALOG_CATEGORY_DEPTH_EXCEEDED");
		assertProblem(update(other, "\"v0\"", "{\"parentId\": \"" + other + "\"}"), 422,
				"CATALOG_CATEGORY_DEPTH_EXCEEDED");
	}

	@Test
	void categoryWithOnlyArchivedChildrenStillCannotBecomeAChild() throws Exception {
		String parent = createdId(Map.of("slug", slug("con-luu"), "name", "Còn lưu trữ"));
		String child = createdId(Map.of("slug", slug("con"), "name", "Con", "parentId", parent));
		archive(child, null);
		String other = createdId(Map.of("slug", slug("khac"), "name", "Khác"));

		assertProblem(update(parent, "\"v0\"", "{\"parentId\": \"" + other + "\"}"), 422,
				"CATALOG_CATEGORY_DEPTH_EXCEEDED");
		Map<String, Object> node = node(tree(true), parent);
		assertThat(JsonPath.<List<String>>read(node, "$.children[*].categoryId")).containsExactly(child);
	}

	@Test
	void archivedCategoryCannotBeEdited() throws Exception {
		String id = createdId(Map.of("slug", slug("luu-tru"), "name", "Lưu trữ"));
		archive(id, null);

		assertProblem(update(id, "\"v1\"", "{\"name\": \"X\"}"), 409, "CATALOG_CATEGORY_ARCHIVED");
	}

	// --- archive ------------------------------------------------------------------------------------------------

	@Test
	void archiveIsBlockedWhileTheCategoryHasActiveChildren() throws Exception {
		String parent = createdId(Map.of("slug", slug("goc"), "name", "Gốc"));
		String child = createdId(Map.of("slug", slug("nhanh"), "name", "Nhánh", "parentId", parent));

		assertProblem(archive(parent, null), 409, "CATALOG_CATEGORY_IN_USE");

		assertThat(archive(child, "\"v0\"").getStatus()).isEqualTo(200);
		MockHttpServletResponse archived = archive(parent, null);
		assertThat(archived.getStatus()).as(archived.getContentAsString()).isEqualTo(200);
		assertThat(JsonPath.<String>read(archived.getContentAsString(), "$.status")).isEqualTo("ARCHIVED");
		assertThat(audits(parent, "CATEGORY_ARCHIVE")).hasSize(1);
	}

	@Test
	void archiveTwiceOrWithStaleVersionIsRejected() throws Exception {
		String id = createdId(Map.of("slug", slug("hai-lan"), "name", "Hai lần"));

		assertProblem(archive(id, "\"v5\""), 412, "VERSION_CONFLICT");
		assertThat(archive(id, null).getStatus()).isEqualTo(200);
		assertProblem(archive(id, null), 409, "CATALOG_CATEGORY_ARCHIVED");
	}

	// --- reorder ------------------------------------------------------------------------------------------------

	@Test
	void reorderSetsTheOrderOfSiblingsAndAuditsOldAndNew() throws Exception {
		String parent = createdId(Map.of("slug", slug("nhom"), "name", "Nhóm"));
		String a = createdId(Map.of("slug", slug("a"), "name", "A", "parentId", parent));
		String b = createdId(Map.of("slug", slug("b"), "name", "B", "parentId", parent));
		String c = createdId(Map.of("slug", slug("c"), "name", "C", "parentId", parent));

		MockHttpServletResponse response = reorder(parent, List.of(c, a, b));

		assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
		Map<String, Object> node = node(response.getContentAsString(), parent);
		assertThat(JsonPath.<List<String>>read(node, "$.children[*].categoryId")).containsExactly(c, a, b);
		assertThat(detail(a).getHeader("ETag")).as("moved").isEqualTo("\"v1\"");
		List<Map<String, Object>> audits = audits(parent, "CATEGORY_REORDER");
		assertThat(audits).hasSize(1);
		assertThat(JsonPath.<List<String>>read((String) audits.getFirst().get("old_value"), "$.categoryIds"))
			.containsExactly(a, b, c);
		assertThat(JsonPath.<List<String>>read((String) audits.getFirst().get("new_value"), "$.categoryIds"))
			.containsExactly(c, a, b);

		assertThat(reorder(parent, List.of(c, a, b)).getStatus()).isEqualTo(200);
		assertThat(audits(parent, "CATEGORY_REORDER")).as("unchanged order").hasSize(1);
	}

	@Test
	void reorderKeepsTheVersionOfCategoriesThatStayInPlace() throws Exception {
		String parent = createdId(Map.of("slug", slug("khe"), "name", "Có khe"));
		String a = createdId(Map.of("slug", slug("a"), "name", "A", "parentId", parent));
		String b = createdId(Map.of("slug", slug("b"), "name", "B", "parentId", parent));
		String c = createdId(Map.of("slug", slug("c"), "name", "C", "parentId", parent));
		String d = createdId(Map.of("slug", slug("d"), "name", "D", "parentId", parent));
		archive(b, null);

		MockHttpServletResponse response = reorder(parent, List.of(c, a, d));

		assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
		Map<String, Object> node = node(response.getContentAsString(), parent);
		assertThat(JsonPath.<List<String>>read(node, "$.children[*].categoryId")).containsExactly(c, a, d);
		assertThat(detail(d).getHeader("ETag")).as("stays last").isEqualTo("\"v0\"");
		assertThat(detail(c).getHeader("ETag")).as("moved").isEqualTo("\"v1\"");
	}

	@Test
	void reorderOfTheRootsUsesANullParent() throws Exception {
		createdId(Map.of("slug", slug("goc-1"), "name", "Gốc 1"));
		createdId(Map.of("slug", slug("goc-2"), "name", "Gốc 2"));
		List<String> roots = new ArrayList<>(rootIds(tree(false)));
		java.util.Collections.reverse(roots);

		MockHttpServletResponse response = reorder(null, roots);

		assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
		assertThat(rootIds(response.getContentAsString())).containsExactlyElementsOf(roots);
		assertThat(audits(new UUID(0, 0).toString(), "CATEGORY_REORDER")).isNotEmpty();
	}

	@Test
	void reorderWithAStaleSetOfSiblingsConflicts() throws Exception {
		String parent = createdId(Map.of("slug", slug("cu-moi"), "name", "Cũ mới"));
		String a = createdId(Map.of("slug", slug("a"), "name", "A", "parentId", parent));
		String b = createdId(Map.of("slug", slug("b"), "name", "B", "parentId", parent));

		assertProblem(reorder(parent, List.of(b)), 409, "CATALOG_CATEGORY_ORDER_STALE");
		assertProblem(reorder(parent, List.of(b, a, UUID.randomUUID().toString())), 409,
				"CATALOG_CATEGORY_ORDER_STALE");
		assertInvalid(reorder(parent, List.of(b, a, a)), "categoryIds");
		assertProblem(reorder(UUID.randomUUID().toString(), List.of()), 404, "CATALOG_CATEGORY_NOT_FOUND");
	}

	// --- permissions --------------------------------------------------------------------------------------------

	@Test
	void writesNeedCatalogManageWhileCatalogViewCanRead() throws Exception {
		String id = createdId(Map.of("slug", slug("quyen"), "name", "Quyền"));
		UUID managerId = this.fixtures.branchStaff();
		this.fixtures.assignToBranch(managerId, anyBranch(), "MANAGER");
		Session manager = this.fixtures.openSession(managerId);

		MockHttpServletResponse read = this.auth.perform(get(CATEGORIES), manager, false);
		assertThat(read.getStatus()).as(read.getContentAsString()).isEqualTo(200);
		assertProblem(this.auth.perform(post(CATEGORIES).contentType(MediaType.APPLICATION_JSON)
			.content("{\"slug\": \"" + slug("no") + "\", \"name\": \"Không\"}"), manager, true), 403,
				"PERMISSION_DENIED");
		assertProblem(this.auth.perform(post(CATEGORIES + "/" + id + "/archive"), manager, true), 403,
				"PERMISSION_DENIED");
	}

	// --- helpers ------------------------------------------------------------------------------------------------

	private UUID anyBranch() throws Exception {
		MockHttpServletResponse created = this.auth.perform(post("/api/backoffice/v1/branches")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"branchCode": "C-%s", "name": "HaYi Test",
					 "address": {"line": "1 Lê Lợi", "province": "TP.HCM"}}
					""".formatted(UUID.randomUUID().toString().substring(0, 8))), this.admin, true);
		assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
		return UUID.fromString(JsonPath.read(created.getContentAsString(), "$.branchId"));
	}

	private static String slug(String prefix) {
		return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
	}

	private MockHttpServletResponse create(Map<String, String> fields) throws Exception {
		return this.auth.perform(post(CATEGORIES).contentType(MediaType.APPLICATION_JSON).content(json(fields)),
				this.admin, true);
	}

	private String createdId(Map<String, String> fields) throws Exception {
		MockHttpServletResponse created = create(fields);
		assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
		return JsonPath.read(created.getContentAsString(), "$.categoryId");
	}

	private MockHttpServletResponse update(String id, String ifMatch, String json) throws Exception {
		MockHttpServletRequestBuilder request = patch(CATEGORIES + "/" + id).contentType(MERGE_PATCH).content(json);
		if (ifMatch != null) {
			request.header("If-Match", ifMatch);
		}
		return this.auth.perform(request, this.admin, true);
	}

	private MockHttpServletResponse archive(String id, String ifMatch) throws Exception {
		MockHttpServletRequestBuilder request = post(CATEGORIES + "/" + id + "/archive");
		if (ifMatch != null) {
			request.header("If-Match", ifMatch);
		}
		return this.auth.perform(request, this.admin, true);
	}

	private MockHttpServletResponse reorder(String parentId, List<String> ids) throws Exception {
		String parent = (parentId != null) ? "\"" + parentId + "\"" : "null";
		String list = String.join(",", ids.stream().map(id -> "\"" + id + "\"").toList());
		return this.auth.perform(put(CATEGORIES + "/order").contentType(MediaType.APPLICATION_JSON)
			.content("{\"parentId\": " + parent + ", \"categoryIds\": [" + list + "]}"), this.admin, true);
	}

	private MockHttpServletResponse detail(String id) throws Exception {
		MockHttpServletResponse response = this.auth.perform(get(CATEGORIES + "/" + id), this.admin, false);
		assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
		return response;
	}

	private String tree(boolean includeArchived) throws Exception {
		MockHttpServletResponse response = this.auth
			.perform(get(CATEGORIES).param("includeArchived", Boolean.toString(includeArchived)), this.admin, false);
		assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
		return response.getContentAsString();
	}

	private static List<String> rootIds(String tree) {
		return JsonPath.read(tree, "$.items[*].categoryId");
	}

	private static Map<String, Object> node(String tree, String rootId) {
		List<Map<String, Object>> nodes = JsonPath.read(tree, "$.items[?(@.categoryId == '" + rootId + "')]");
		assertThat(nodes).as("root " + rootId).hasSize(1);
		return nodes.getFirst();
	}

	private List<Map<String, Object>> audits(String entityId, String action) {
		return this.jdbc
			.sql("SELECT entity_type, actor_user_id, CAST(old_value AS CHAR) AS old_value,"
					+ " CAST(new_value AS CHAR) AS new_value FROM aud_audit_log WHERE entity_id = ? AND action = ?")
			.param(UuidBinary.toBytes(UUID.fromString(entityId)))
			.param(action)
			.query()
			.listOfRows();
	}

	private static String json(Map<String, String> fields) {
		List<String> pairs = fields.entrySet().stream()
			.map(field -> "\"" + field.getKey() + "\": \"" + field.getValue() + "\"")
			.toList();
		return "{" + String.join(", ", pairs) + "}";
	}

	private static void assertInvalid(MockHttpServletResponse response, String field) throws Exception {
		assertProblem(response, 400, "VALIDATION_FAILED");
		assertThat(JsonPath.<List<String>>read(response.getContentAsString(), "$.errors[*].field"))
			.as(response.getContentAsString())
			.containsExactly(field);
	}

	private static void assertProblem(MockHttpServletResponse response, int status, String code) throws Exception {
		assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
		assertThat(JsonPath.<String>read(response.getContentAsString(), "$.code")).isEqualTo(code);
	}

}
