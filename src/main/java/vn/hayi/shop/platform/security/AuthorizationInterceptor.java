package vn.hayi.shop.platform.security;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import vn.hayi.shop.shared.error.CommonErrorCode;
import vn.hayi.shop.shared.error.DomainException;

/** Enforces {@link RequiresPermission} through the {@link PermissionEvaluator} (API README §3.1, Decision M1). */
final class AuthorizationInterceptor implements HandlerInterceptor {

	static final String BRANCH_ID = "branchId";

	private final PermissionEvaluator evaluator;

	AuthorizationInterceptor(PermissionEvaluator evaluator) {
		this.evaluator = evaluator;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		if (!(handler instanceof HandlerMethod method) || !method.hasMethodAnnotation(RequiresPermission.class)) {
			return true;
		}
		Permission permission = method.getMethodAnnotation(RequiresPermission.class).value();
		AuthenticatedSession session = AuthenticatedSession.require();
		String branchId = branchIdOf(request);
		Optional<UUID> branch = (branchId != null) ? parse(branchId) : Optional.empty();
		AccessDecision decision = this.evaluator.evaluate(session.userId(), permission, branch);
		if (decision == AccessDecision.GRANTED && branchId != null && branch.isEmpty()) {
			// A malformed id names no Branch the caller may see; the permission was still checked first (Decision M1).
			decision = AccessDecision.BRANCH_SCOPE_DENIED;
		}
		return switch (decision) {
			case GRANTED -> true;
			case PERMISSION_DENIED ->
				throw new DomainException(CommonErrorCode.PERMISSION_DENIED, "Missing permission " + permission);
			case BRANCH_SCOPE_DENIED ->
				throw new DomainException(CommonErrorCode.BRANCH_SCOPE_DENIED, "Branch outside the caller's scope");
		};
	}

	@SuppressWarnings("unchecked")
	private static String branchIdOf(HttpServletRequest request) {
		Map<String, String> variables = (Map<String, String>) request
			.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
		return (variables != null) ? variables.get(BRANCH_ID) : null;
	}

	private static Optional<UUID> parse(String branchId) {
		try {
			return Optional.of(UUID.fromString(branchId));
		}
		catch (IllegalArgumentException ex) {
			return Optional.empty();
		}
	}

}
