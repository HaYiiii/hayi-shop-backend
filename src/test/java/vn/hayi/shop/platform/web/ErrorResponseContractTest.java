package vn.hayi.shop.platform.web;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import vn.hayi.shop.TestcontainersConfiguration;

/**
 * Every error leaves the API as RFC 9457 Problem Details with a stable {@code code} and the request's
 * {@code traceId} (API README §6, §9).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ErrorResponseContractTest {

	private static final String TRACE_ID = "[0-9a-f]{32}";

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

	private static void assertTraceIdInBodyMatchesHeader(MvcResult result) throws Exception {
		jsonPath("$.traceId").value(result.getResponse().getHeader("X-Trace-Id")).match(result);
	}

}
