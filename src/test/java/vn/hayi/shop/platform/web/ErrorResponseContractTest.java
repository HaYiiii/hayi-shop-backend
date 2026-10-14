package vn.hayi.shop.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.hayi.shop.TestcontainersConfiguration;
import vn.hayi.shop.shared.error.DomainException;
import vn.hayi.shop.shared.error.ErrorCode;
import vn.hayi.shop.shared.error.ErrorKind;

/**
 * Every error leaves the API as RFC 9457 Problem Details with a stable {@code code} and the request's
 * {@code traceId} (API README §6, §9).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@Import({ TestcontainersConfiguration.class, ErrorResponseContractTest.ProbeController.class })
class ErrorResponseContractTest {

	private static final String PROBE = "/api/backoffice/v1/__probe";

	static final String BOOM_MESSAGE = "select * from iam_user -- secret";

	private static final String TRACE_ID = "[0-9a-f]{32}";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Autowired
	MockMvc mvc;

	@Test
	void unauthenticatedBackofficeRequestGets401AuthRequiredWithTraceId() throws Exception {
		MvcResult result = mvc.perform(get("/api/backoffice/v1/orders"))
			.andExpect(status().isUnauthorized())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.status").value(401))
			.andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
			.andExpect(jsonPath("$.type").value("https://docs.hayi.vn/errors/AUTH_REQUIRED"))
			.andExpect(jsonPath("$.title").isNotEmpty())
			.andExpect(header().string("X-Trace-Id", matchesPattern(TRACE_ID)))
			.andReturn();

		assertTraceIdInBodyMatchesHeader(result);
	}

	@Test
	@WithMockUser
	void authenticatedUserWithoutPermissionGets403PermissionDenied() throws Exception {
		MvcResult result = mvc.perform(get(PROBE + "/needs-permission"))
			.andExpect(status().isForbidden())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.code").value("PERMISSION_DENIED"))
			.andReturn();

		assertTraceIdInBodyMatchesHeader(result);
	}

	@Test
	@WithMockUser
	void invalidRequestBodyGets400ValidationFailedWithFieldErrors() throws Exception {
		MvcResult result = mvc.perform(post(PROBE + "/validated").contentType(MediaType.APPLICATION_JSON)
				.content("""
						{ "name": " ", "quantity": 0 }
						"""))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors.length()").value(2))
			.andExpect(jsonPath("$.errors[?(@.field == 'name')].code").value("NOT_BLANK"))
			.andExpect(jsonPath("$.errors[?(@.field == 'quantity')].code").value("POSITIVE"))
			.andExpect(jsonPath("$.errors[?(@.field == 'quantity')].message").isNotEmpty())
			.andReturn();

		assertTraceIdInBodyMatchesHeader(result);
	}

	@Test
	@WithMockUser
	void malformedJsonGets400ValidationFailedWithoutParserDetails() throws Exception {
		MvcResult result = mvc.perform(post(PROBE + "/validated").contentType(MediaType.APPLICATION_JSON)
				.content("{ \"name\": "))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(content().string(not(containsStringIgnoringCase("jackson"))))
			.andExpect(content().string(not(containsString("ProbeRequest"))))
			.andReturn();

		assertTraceIdInBodyMatchesHeader(result);
	}

	@ParameterizedTest
	@CsvSource({
			"PROBE_NOT_FOUND, 404",
			"PROBE_INVALID_TRANSITION, 409",
			"PROBE_RULE_VIOLATED, 422" })
	@WithMockUser
	void domainExceptionGetsStatusOfItsErrorKindAndKeepsModuleCode(String code, int expectedStatus) throws Exception {
		MvcResult result = mvc.perform(get(PROBE + "/domain-error/" + code))
			.andExpect(status().is(expectedStatus))
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.status").value(expectedStatus))
			.andExpect(jsonPath("$.code").value(code))
			.andExpect(jsonPath("$.type").value("https://docs.hayi.vn/errors/" + code))
			.andReturn();

		assertTraceIdInBodyMatchesHeader(result);
	}

	@Test
	@WithMockUser
	void unexpectedFailureGets500InternalErrorWithoutStackTraceOrMessage() throws Exception {
		MvcResult result = mvc.perform(get(PROBE + "/boom"))
			.andExpect(status().isInternalServerError())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
			.andExpect(content().string(not(containsString(BOOM_MESSAGE))))
			.andExpect(content().string(not(containsString("IllegalStateException"))))
			.andExpect(content().string(not(containsString("at vn.hayi"))))
			.andReturn();

		assertTraceIdInBodyMatchesHeader(result);
	}

	@Test
	@WithMockUser
	void serverLogLineCarriesSameTraceIdAsResponseHeader(CapturedOutput output) throws Exception {
		String traceId = mvc.perform(get(PROBE + "/boom"))
			.andExpect(status().isInternalServerError())
			.andReturn().getResponse().getHeader("X-Trace-Id");

		JsonNode errorLine = output.getOut().lines()
			.filter(line -> line.startsWith("{") && line.contains(BOOM_MESSAGE))
			.map(JSON::readTree)
			.findFirst()
			.orElseThrow(() -> new AssertionError("no JSON log line for the failure"));
		assertThat(errorLine.path("level").asString()).isEqualTo("ERROR");
		assertThat(errorLine.path("traceId").asString()).isEqualTo(traceId);
	}

	private static void assertTraceIdInBodyMatchesHeader(MvcResult result) throws Exception {
		jsonPath("$.traceId").value(result.getResponse().getHeader("X-Trace-Id")).match(result);
	}

	/** Test-only endpoints that raise each kind of error. */
	@RestController
	@RequestMapping(PROBE)
	static class ProbeController {

		@GetMapping("/needs-permission")
		@PreAuthorize("hasAuthority('PERMISSION_NOBODY_HAS')")
		void needsPermission() {
		}

		@GetMapping("/domain-error/{code}")
		void domainError(@PathVariable ProbeErrorCode code) {
			throw new DomainException(code, "probe " + code);
		}

		@GetMapping("/boom")
		void boom() {
			throw new IllegalStateException(BOOM_MESSAGE);
		}

		@PostMapping("/validated")
		void validated(@Valid @RequestBody ProbeRequest request) {
		}

	}

	enum ProbeErrorCode implements ErrorCode {

		PROBE_NOT_FOUND(ErrorKind.NOT_FOUND),
		PROBE_INVALID_TRANSITION(ErrorKind.CONFLICT),
		PROBE_RULE_VIOLATED(ErrorKind.BUSINESS_RULE);

		private final ErrorKind kind;

		ProbeErrorCode(ErrorKind kind) {
			this.kind = kind;
		}

		@Override
		public ErrorKind kind() {
			return this.kind;
		}

	}

	record ProbeRequest(@NotBlank String name, @Positive int quantity) {

	}

}
