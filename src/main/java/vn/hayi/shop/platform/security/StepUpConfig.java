package vn.hayi.shop.platform.security;

import java.time.Clock;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers the {@link RequiresStepUp} check for every controller. */
@Configuration(proxyBeanMethods = false)
class StepUpConfig implements WebMvcConfigurer {

	private final StepUpChecker checker;

	private final SecurityProperties properties;

	private final Clock clock;

	StepUpConfig(StepUpChecker checker, SecurityProperties properties, Clock clock) {
		this.checker = checker;
		this.properties = properties;
		this.clock = clock;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(new StepUpInterceptor(this.checker, this.properties, this.clock));
	}

}
