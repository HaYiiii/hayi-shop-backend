package vn.hayi.shop.branch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.jayway.jsonpath.JsonPath;
import vn.hayi.shop.MutableClockConfiguration;
import vn.hayi.shop.TestcontainersConfiguration;
import vn.hayi.shop.branch.api.BranchFulfillmentSummary;
import vn.hayi.shop.branch.application.BranchHoursService;
import vn.hayi.shop.branch.application.BranchService;
import vn.hayi.shop.branch.application.NewBranch;
import vn.hayi.shop.branch.application.WeeklyIntervalRequest;
import vn.hayi.shop.shared.actor.ActorContext;
import vn.hayi.shop.shared.concurrency.ExpectedVersion;

/** One Branch whose ordering summary fails does not take the whole public list down (review of PR #54). */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MutableClockConfiguration.class })
class PublicBranchesResilienceTest {

	static final ActorContext OWNER = ActorContext.staff(UUID.randomUUID(), null);

	@Autowired
	MockMvc mvc;

	@Autowired
	BranchService branches;

	@Autowired
	BranchHoursService hours;

	@MockitoSpyBean
	BranchFulfillmentSummary summary;

	@Test
	void aFailingSummaryLeavesThatBranchWithoutOrderingFacts() throws Exception {
		UUID healthy = activeBranch();
		UUID failing = activeBranch();
		doThrow(new IllegalStateException("probe")).when(this.summary).earliestPickupAt(failing);
		doThrow(new IllegalStateException("probe")).when(this.summary).fulfillmentTypes(failing);

		MockHttpServletResponse list = this.mvc.perform(MockMvcRequestBuilders.get("/api/public/v1/branches"))
			.andReturn()
			.getResponse();

		assertThat(list.getStatus()).as(list.getContentAsString()).isEqualTo(200);
		String body = list.getContentAsString();
		assertThat(JsonPath.<List<String>>read(body, "$.items[*].branchId")).contains(healthy.toString(),
				failing.toString());
		assertThat(JsonPath.<List<Object>>read(body, "$.items[?(@.branchId == '" + failing + "')].earliestPickupAt"))
			.containsExactly((Object) null);
		assertThat(JsonPath.<List<List<String>>>read(body,
				"$.items[?(@.branchId == '" + failing + "')].fulfillmentTypes"))
			.containsExactly(List.of());
	}

	private UUID activeBranch() {
		UUID id = this.branches.create(new NewBranch("T-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
				"HaYi test", "1 Lê Lợi", null, null, "TP.HCM", null, null, null, null), OWNER, null, null)
			.branchId();
		this.hours.replaceWeekly(id, List.of(new WeeklyIntervalRequest(2, LocalTime.of(7, 0), LocalTime.of(21, 0))),
				new ExpectedVersion(0), OWNER, null, null);
		this.branches.activate(id, null, OWNER, null, null);
		return id;
	}

}
