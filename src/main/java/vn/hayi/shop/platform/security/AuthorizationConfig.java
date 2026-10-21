package vn.hayi.shop.platform.security;

import java.time.Clock;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the endpoint checks for every controller, in the order of Decision M1: {@link RequiresPermission}
 * (permission, branch scope) before {@link RequiresStepUp}.
 */
@Configuration(proxyBeanMethods = false)
class AuthorizationConfig implements WebMvcConfigurer {

	private final PermissionEvaluator permissions;

	private final StepUpChecker stepUps;

	private final SecurityProperties properties;

	private final Clock clock;

	AuthorizationConfig(PermissionEvaluator permissions, StepUpChecker stepUps, SecurityProperties properties,
			Clock clock) {
		this.permissions = permissions;
		this.stepUps = stepUps;
		this.properties = properties;
		this.clock = clock;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(new AuthorizationInterceptor(this.permissions));
		registry.addInterceptor(new StepUpInterceptor(this.stepUps, this.properties, this.clock));
	}

}
