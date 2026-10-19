package vn.hayi.shop;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import org.springframework.web.bind.annotation.RestController;

import vn.hayi.shop.platform.security.RequiresPermission;
import vn.hayi.shop.platform.security.RequiresStepUp;
import vn.hayi.shop.platform.web.spec.backoffice.api.AuthApi;

/** Authorization rules on endpoints (API 01-security §7–8, Decision M5), shared by {@link ArchitectureTest}. */
public final class AuthorizationRules {

	/** An endpoint needing a permission marked S must also demand a fresh step-up. */
	public static final ArchRule stepUpPermissionsRequireStepUp = methods()
		.that().areAnnotatedWith(RequiresPermission.class)
		.should(new ArchCondition<>("be annotated with @RequiresStepUp when the permission is marked S") {
			@Override
			public void check(JavaMethod method, ConditionEvents events) {
				boolean sensitive = method.getAnnotationOfType(RequiresPermission.class).value().requiresStepUp();
				if (sensitive && !method.isAnnotatedWith(RequiresStepUp.class)) {
					events.add(SimpleConditionEvent.violated(method,
							method.getFullName() + " requires a permission marked S but lacks @RequiresStepUp"));
				}
			}
		})
		.allowEmptyShould(true);

	/**
	 * Every Backoffice endpoint outside {@code /auth} declares the permission it needs (Identity §2.7: API permission
	 * check). Endpoints are the public methods of the Backoffice controllers.
	 */
	public static final ArchRule backofficeEndpointsDeclarePermission = methods()
		.that().areDeclaredInClassesThat().resideInAPackage("..web.backoffice..")
		.and().areDeclaredInClassesThat().areAnnotatedWith(RestController.class)
		.and().areDeclaredInClassesThat().areNotAssignableTo(AuthApi.class)
		.and().haveModifier(JavaModifier.PUBLIC)
		.should().beAnnotatedWith(RequiresPermission.class)
		.allowEmptyShould(true);

	private AuthorizationRules() {
	}

}
