package vn.hayi.shop.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.EvaluationResult;
import org.junit.jupiter.api.Test;

import vn.hayi.shop.AuthorizationRules;
import archfixture.web.backoffice.UndeclaredController;

/** The ArchUnit rules of {@link AuthorizationRules} catch the endpoints they are written for. */
class AuthorizationRulesTest {

	@Test
	void endpointWithPermissionMarkedSWithoutStepUpFailsTheRule() {
		EvaluationResult result = AuthorizationRules.stepUpPermissionsRequireStepUp.evaluate(classes());

		assertThat(result.hasViolation()).isTrue();
		assertThat(result.getFailureReport().getDetails()).singleElement().asString().contains("approveWithoutStepUp");
	}

	@Test
	void backofficeEndpointWithoutPermissionFailsTheRule() {
		EvaluationResult result = AuthorizationRules.backofficeEndpointsDeclarePermission
			.evaluate(new ClassFileImporter().importClasses(UndeclaredController.class));

		assertThat(result.getFailureReport().getDetails()).singleElement().asString().contains("undeclared()");
	}

	private static JavaClasses classes() {
		return new ClassFileImporter().importClasses(Endpoints.class);
	}

	static class Endpoints {

		@RequiresPermission(Permission.REFUND_APPROVE)
		@RequiresStepUp
		void approveWithStepUp() {
		}

		@RequiresPermission(Permission.REFUND_APPROVE)
		void approveWithoutStepUp() {
		}

		@RequiresPermission(Permission.REFUND_REQUEST)
		void requestWithoutStepUp() {
		}

	}

}
