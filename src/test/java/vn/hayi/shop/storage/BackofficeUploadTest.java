package vn.hayi.shop.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.endsWith;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;
import vn.hayi.shop.MutableClock;
import vn.hayi.shop.MutableClockConfiguration;
import vn.hayi.shop.StorageTestcontainersConfiguration;
import vn.hayi.shop.TestcontainersConfiguration;
import vn.hayi.shop.identity.BackofficeAuth;
import vn.hayi.shop.identity.BackofficeAuth.Session;
import vn.hayi.shop.identity.IdentityFixtures;
import vn.hayi.shop.platform.persistence.UuidBinary;
import vn.hayi.shop.storage.api.StoredObjectInfo;
import vn.hayi.shop.storage.api.StoredObjects;
import vn.hayi.shop.storage.application.ObjectStore;

/**
 * Staff upload an image straight to object storage with a pre-signed URL, then the server checks it (Storage
 * §18.5–18.6, API 06 §1, Decision G8, AF). Runs against a real SeaweedFS.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, StorageTestcontainersConfiguration.class,
		MutableClockConfiguration.class, IdentityFixtures.class })
class BackofficeUploadTest {

	static final String UPLOADS = "/api/backoffice/v1/uploads";

	static final HttpClient HTTP = HttpClient.newHttpClient();

	@Autowired
	MockMvc mvc;

	@Autowired
	IdentityFixtures fixtures;

	@Autowired
	MutableClock clock;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	StoredObjects storedObjects;

	@MockitoSpyBean
	ObjectStore store;

	BackofficeAuth auth;

	Session admin;

	@BeforeEach
	void setUp() {
		this.auth = new BackofficeAuth(this.mvc, this.clock);
		this.admin = this.fixtures.openSession(this.fixtures.storeAdmin(IdentityFixtures.uniqueEmail()));
	}

	// --- intent (AF1) -------------------------------------------------------------------------------------------

	@Test
	void anIntentGivesAPresignedPutValidTenMinutes() throws Exception {
		byte[] png = image("png", 3, 2);

		MockHttpServletResponse created = intent(this.admin, "PRODUCT_IMAGE", "image/png", png.length, sha256(png));

		assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
		String body = created.getContentAsString();
		String id = JsonPath.read(body, "$.storedObjectId");
		assertThat(JsonPath.<String>read(body, "$.upload.method")).isEqualTo("PUT");
		assertThat(JsonPath.<String>read(body, "$.upload.url")).contains("hayi-public-test/product/")
			.contains(id + ".png")
			.contains("X-Amz-Signature");
		assertThat(JsonPath.<String>read(body, "$.upload.headers.Content-Type")).isEqualTo("image/png");
		assertThat(JsonPath.<Integer>read(body, "$.constraints.maxSizeBytes")).isEqualTo(5242880);
		assertThat(JsonPath.<List<String>>read(body, "$.constraints.allowedMimeTypes"))
			.containsExactly("image/jpeg", "image/png", "image/webp");
		assertThat(status(id)).isEqualTo("PENDING_UPLOAD");
		assertThat(this.jdbc.sql("SELECT TIMESTAMPDIFF(SECOND, created_at, upload_expires_at) FROM sto_stored_object"
				+ " WHERE id = ?").param(UuidBinary.toBytes(UUID.fromString(id))).query(Long.class).single())
			.isEqualTo(600);
	}

	@Test
	void anIntentOutsideThePolicyIsRefused() throws Exception {
		String sum = sha256(new byte[] { 1 });

		// an enum value outside the schema is an unreadable body: 400 without errors[], as on every endpoint
		assertProblem(intent(this.admin, "PAYMENT_EVIDENCE", "image/png", 10, sum), 400, "VALIDATION_FAILED");
		assertProblem(intent(this.admin, "PRODUCT_IMAGE", "image/gif", 10, sum), 400, "VALIDATION_FAILED");
		assertInvalid(intent(this.admin, "PRODUCT_IMAGE", "image/png", 5242881, sum), "sizeBytes");
		assertInvalid(intent(this.admin, "PRODUCT_IMAGE", "image/png", 10, "abc"), "checksumSha256");
	}

	@Test
	void anIntentNeedsCatalogManage() throws Exception {
		UUID staff = this.fixtures.branchStaff();
		Session noCatalog = this.fixtures.openSession(staff);

		assertProblem(intent(noCatalog, "PRODUCT_IMAGE", "image/png", 10, sha256(new byte[] { 1 })), 403,
				"PERMISSION_DENIED");
	}

	// --- finalize (AF2) -----------------------------------------------------------------------------------------

	@Test
	void anUploadedPngBecomesActiveWithItsSizeAndPublicUrl() throws Exception {
		byte[] png = image("png", 3, 2);
		String id = uploaded("CATEGORY_IMAGE", "image/png", png, png);

		MockHttpServletResponse done = finalizeUpload(this.admin, id);

		assertThat(done.getStatus()).as(done.getContentAsString()).isEqualTo(200);
		String body = done.getContentAsString();
		assertThat(JsonPath.<String>read(body, "$.status")).isEqualTo("ACTIVE");
		assertThat(JsonPath.<String>read(body, "$.detectedMimeType")).isEqualTo("image/png");
		assertThat(JsonPath.<Integer>read(body, "$.sizeBytes")).isEqualTo(png.length);
		assertThat(JsonPath.<Integer>read(body, "$.width")).isEqualTo(3);
		assertThat(JsonPath.<Integer>read(body, "$.height")).isEqualTo(2);
		String publicUrl = JsonPath.read(body, "$.publicUrl");
		assertThat(publicUrl).contains("/hayi-public-test/category/").endsWith(id + ".png");
		assertThat(status(id)).isEqualTo("ACTIVE");
		HttpResponse<byte[]> anonymous = HTTP.send(HttpRequest.newBuilder(URI.create(publicUrl)).GET().build(),
				HttpResponse.BodyHandlers.ofByteArray());
		assertThat(anonymous.statusCode()).as("public objects are read without credentials").isEqualTo(200);
		assertThat(anonymous.body()).isEqualTo(png);
	}

	@Test
	void theBrowserMayPutFromAnotherOrigin() throws Exception {
		byte[] png = image("png", 2, 2);
		String url = JsonPath.read(intent(this.admin, "PRODUCT_IMAGE", "image/png", png.length, sha256(png))
			.getContentAsString(), "$.upload.url");

		HttpResponse<String> preflight = HTTP.send(HttpRequest.newBuilder(URI.create(url))
			.method("OPTIONS", HttpRequest.BodyPublishers.noBody())
			.header("Origin", "http://localhost:3000")
			.header("Access-Control-Request-Method", "PUT")
			.header("Access-Control-Request-Headers", "content-type")
			.build(), HttpResponse.BodyHandlers.ofString());

		assertThat(preflight.statusCode()).as(preflight.body()).isLessThan(300);
		assertThat(preflight.headers().firstValue("Access-Control-Allow-Origin")).isPresent();
	}

	@Test
	void anIntentPastItsExpiryCanStillBeFinalizedUntilCleanup() throws Exception {
		byte[] png = image("png", 2, 2);
		String id = uploaded("PRODUCT_IMAGE", "image/png", png, png);
		this.jdbc.sql("UPDATE sto_stored_object SET upload_expires_at = ? WHERE id = ?")
			.params(this.clock.instant().minus(Duration.ofMinutes(1)), UuidBinary.toBytes(UUID.fromString(id)))
			.update();

		assertThat(finalizeUpload(this.admin, id).getStatus()).as("the orphan job (S7) has not run").isEqualTo(200);
	}

	@Test
	void aConcurrentFinalizeThatLosesIsAlreadyFinalized() throws Exception {
		byte[] png = image("png", 2, 2);
		String id = uploaded("PRODUCT_IMAGE", "image/png", png, png);
		UUID objectId = UUID.fromString(id);
		doAnswer(invocation -> {
			// the other finalize commits while this one reads the bytes
			this.jdbc.sql("UPDATE sto_stored_object SET version = version + 1 WHERE id = ?")
				.param(UuidBinary.toBytes(objectId))
				.update();
			return invocation.callRealMethod();
		}).when(this.store).read(any(), endsWith(id + ".png"), anyLong());

		assertProblem(finalizeUpload(this.admin, id), 409, "UPLOAD_ALREADY_FINALIZED");
	}

	@Test
	void aJpegIsMeasuredToo() throws Exception {
		byte[] jpeg = image("jpg", 5, 4);
		String id = uploaded("PRODUCT_IMAGE", "image/jpeg", jpeg, jpeg);

		String body = finalizeUpload(this.admin, id).getContentAsString();

		assertThat(JsonPath.<String>read(body, "$.status")).isEqualTo("ACTIVE");
		assertThat(JsonPath.<Integer>read(body, "$.width")).isEqualTo(5);
		assertThat(JsonPath.<Integer>read(body, "$.height")).isEqualTo(4);
	}

	@Test
	void nothingUploadedYetKeepsTheIntentForALaterFinalize() throws Exception {
		byte[] png = image("png", 2, 2);
		MockHttpServletResponse created = intent(this.admin, "PRODUCT_IMAGE", "image/png", png.length, sha256(png));
		String id = JsonPath.read(created.getContentAsString(), "$.storedObjectId");

		assertProblem(finalizeUpload(this.admin, id), 422, "UPLOAD_NOT_FOUND_IN_STORAGE");
		assertThat(status(id)).isEqualTo("PENDING_UPLOAD");

		put(created, png);
		assertThat(finalizeUpload(this.admin, id).getStatus()).isEqualTo(200);
	}

	@Test
	void bytesThatAreNotAnImageFailTheIntent() throws Exception {
		byte[] text = "not an image at all".getBytes(StandardCharsets.UTF_8);
		String id = uploaded("PRODUCT_IMAGE", "image/png", text, text);

		assertProblem(finalizeUpload(this.admin, id), 422, "UPLOAD_MIME_MISMATCH");
		assertThat(status(id)).isEqualTo("FAILED");
		assertProblem(finalizeUpload(this.admin, id), 409, "UPLOAD_ALREADY_FINALIZED");
	}

	@Test
	void anImageOfAnotherTypeThanDeclaredIsAMimeMismatch() throws Exception {
		byte[] png = image("png", 2, 2);
		String id = uploaded("PRODUCT_IMAGE", "image/jpeg", png, png);

		assertProblem(finalizeUpload(this.admin, id), 422, "UPLOAD_MIME_MISMATCH");
		assertThat(status(id)).isEqualTo("FAILED");
	}

	@Test
	void anObjectOverFiveMegabytesIsTooLarge() throws Exception {
		byte[] png = image("png", 2, 2);
		byte[] big = Arrays.copyOf(png, 5 * 1024 * 1024 + 1);
		String id = uploaded("PRODUCT_IMAGE", "image/png", png, big);

		assertProblem(finalizeUpload(this.admin, id), 422, "UPLOAD_TOO_LARGE");
		assertThat(status(id)).isEqualTo("FAILED");
	}

	@Test
	void bytesThatDifferFromTheChecksumAreRefused() throws Exception {
		byte[] declared = image("png", 2, 2);
		byte[] other = image("png", 3, 3);
		String id = uploaded("PRODUCT_IMAGE", "image/png", declared, other);

		assertProblem(finalizeUpload(this.admin, id), 422, "UPLOAD_CHECKSUM_MISMATCH");
		assertThat(status(id)).isEqualTo("FAILED");
	}

	@Test
	void anImageWhoseSizeCannotBeReadIsQuarantined() throws Exception {
		byte[] png = image("png", 2, 2);
		byte[] broken = Arrays.copyOf(png, 12);
		String id = uploaded("PRODUCT_IMAGE", "image/png", broken, broken);

		MockHttpServletResponse done = finalizeUpload(this.admin, id);

		assertThat(done.getStatus()).as(done.getContentAsString()).isEqualTo(200);
		assertThat(JsonPath.<String>read(done.getContentAsString(), "$.status")).isEqualTo("QUARANTINED");
		assertThat(JsonPath.<Object>read(done.getContentAsString(), "$.publicUrl")).isNull();
		assertThat(this.storedObjects.find(UUID.fromString(id)).orElseThrow().isActive()).isFalse();
	}

	@Test
	void onlyTheUploaderMayFinalize() throws Exception {
		byte[] png = image("png", 2, 2);
		String id = uploaded("PRODUCT_IMAGE", "image/png", png, png);
		Session other = this.fixtures.openSession(this.fixtures.storeAdmin(IdentityFixtures.uniqueEmail()));

		assertProblem(finalizeUpload(other, id), 403, "PERMISSION_DENIED");
		assertThat(status(id)).isEqualTo("PENDING_UPLOAD");
		assertProblem(finalizeUpload(this.admin, UUID.randomUUID().toString()), 404, "UPLOAD_NOT_FOUND");
		assertThat(finalizeUpload(this.admin, id).getStatus()).isEqualTo(200);
		assertProblem(finalizeUpload(this.admin, id), 409, "UPLOAD_ALREADY_FINALIZED");
	}

	// --- storage.api (AF3) --------------------------------------------------------------------------------------

	@Test
	void businessContextsFindAndArchiveAnActiveObject() throws Exception {
		byte[] png = image("png", 2, 2);
		String id = uploaded("PRODUCT_IMAGE", "image/png", png, png);
		assertThat(finalizeUpload(this.admin, id).getStatus()).isEqualTo(200);
		UUID objectId = UUID.fromString(id);

		StoredObjectInfo info = this.storedObjects.find(objectId).orElseThrow();
		assertThat(info.status()).isEqualTo(StoredObjectInfo.Status.ACTIVE);
		assertThat(info.visibility()).isEqualTo(StoredObjectInfo.Visibility.PUBLIC);
		assertThat(info.purpose()).isEqualTo(StoredObjectInfo.Purpose.PRODUCT_IMAGE);
		assertThat(info.publicUrl()).endsWith(id + ".png");

		this.storedObjects.archive(objectId);
		this.storedObjects.archive(objectId);

		StoredObjectInfo archived = this.storedObjects.find(objectId).orElseThrow();
		assertThat(archived.status()).isEqualTo(StoredObjectInfo.Status.ARCHIVED);
		assertThat(archived.publicUrl()).isNull();
		assertThat(this.storedObjects.find(UUID.randomUUID())).isEmpty();
	}

	@Test
	void anObjectThatIsNotActiveCannotBeArchived() throws Exception {
		byte[] png = image("png", 2, 2);
		String id = JsonPath.read(intent(this.admin, "PRODUCT_IMAGE", "image/png", png.length, sha256(png))
			.getContentAsString(), "$.storedObjectId");

		assertThatThrownBy(() -> this.storedObjects.archive(UUID.fromString(id)))
			.isInstanceOf(IllegalStateException.class);
	}

	// --- helpers ------------------------------------------------------------------------------------------------

	private MockHttpServletResponse intent(Session session, String purpose, String contentType, long size,
			String checksum) throws Exception {
		return this.auth.perform(post(UPLOADS).contentType(MediaType.APPLICATION_JSON).content("""
				{"purpose": "%s", "fileName": "matcha.img", "contentType": "%s", "sizeBytes": %d, "checksumSha256": "%s"}
				""".formatted(purpose, contentType, size, checksum)), session, true);
	}

	/** An intent declaring {@code declared}, then a {@code PUT} of {@code sent}. */
	private String uploaded(String purpose, String contentType, byte[] declared, byte[] sent) throws Exception {
		MockHttpServletResponse created = intent(this.admin, purpose, contentType, Math.min(declared.length, 5242880),
				sha256(declared));
		assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
		put(created, sent);
		return JsonPath.read(created.getContentAsString(), "$.storedObjectId");
	}

	private static void put(MockHttpServletResponse intent, byte[] bytes) throws Exception {
		String body = intent.getContentAsString();
		HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(JsonPath.read(body, "$.upload.url")))
			.header("Content-Type", JsonPath.read(body, "$.upload.headers.Content-Type"))
			.PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
			.build(), HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
	}

	private MockHttpServletResponse finalizeUpload(Session session, String id) throws Exception {
		return this.auth.perform(post(UPLOADS + "/" + id + "/finalize"), session, true);
	}

	private String status(String id) {
		return this.jdbc.sql("SELECT status FROM sto_stored_object WHERE id = ?")
			.param(UuidBinary.toBytes(UUID.fromString(id)))
			.query(String.class)
			.single();
	}

	private static byte[] image(String format, int width, int height) throws Exception {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		image.setRGB(0, 0, width * 1000 + height);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		assertThat(ImageIO.write(image, format, out)).isTrue();
		return out.toByteArray();
	}

	private static String sha256(byte[] bytes) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
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
