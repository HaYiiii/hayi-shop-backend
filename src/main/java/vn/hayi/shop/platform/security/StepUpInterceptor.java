package vn.hayi.shop.platform.security;

import java.time.Clock;
import java.time.Instant;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import vn.hayi.shop.shared.error.CommonErrorCode;
import vn.hayi.shop.shared.error.DomainException;

/** Enforces {@link RequiresStepUp}: a fresh {@code stepUpVerifiedAt} or {@code 403 STEP_UP_REQUIRED} (API 01-security §8). */
final class StepUpInterceptor implements HandlerInterceptor {

	private final StepUpChecker checker;

	private final SecurityProperties properties;

	private final Clock clock;

	StepUpInterceptor(StepUpChecker checker, SecurityProperties properties, Clock clock) {
		this.checker = checker;
		this.properties = properties;
		this.clock = clock;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		if (!(handler instanceof HandlerMethod method) || !method.hasMethodAnnotation(RequiresStepUp.class)) {
			return true;
		}
		Instant freshAfter = Instant.now(this.clock).minus(this.properties.stepUpFreshness());
		boolean fresh = AuthenticatedSession.current()
			.flatMap(session -> this.checker.stepUpVerifiedAt(session.sessionId()))
			.filter(verifiedAt -> verifiedAt.isAfter(freshAfter))
			.isPresent();
		if (!fresh) {
			throw new DomainException(CommonErrorCode.STEP_UP_REQUIRED, "Step-up required for this action");
		}
		return true;
	}

}
