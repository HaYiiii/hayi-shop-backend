package vn.hayi.shop.platform.security;

import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import vn.hayi.shop.shared.error.CommonErrorCode;
import vn.hayi.shop.shared.error.DomainException;

/** Enforces {@link RequiresPermission}: permission, then branch scope (API README §3.1, Decision M1). */
final class AuthorizationInterceptor implements HandlerInterceptor {

	static final String BRANCH_ID = "branchId";

	private final PermissionResolver resolver;

	AuthorizationInterceptor(PermissionResolver resolver) {
		this.resolver = resolver;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		if (!(handler instanceof HandlerMethod method) || !method.hasMethodAnnotation(RequiresPermission.class)) {
			return true;
		}
		Permission permission = method.getMethodAnnotation(RequiresPermission.class).value();
		AuthenticatedSession session = AuthenticatedSession.current()
			.orElseThrow(() -> new DomainException(CommonErrorCode.AUTH_REQUIRED, "No session"));
		EffectiveAccess access = this.resolver.resolve(session.userId());
		if (!access.holdsAnywhere(permission)) {
			throw permissionDenied(permission);
		}
		String branchId = branchIdOf(request);
		if (branchId != null) {
			UUID branch = parse(branchId);
			if (!access.inScope(branch)) {
				throw new DomainException(CommonErrorCode.BRANCH_SCOPE_DENIED, "Branch outside the caller's scope");
			}
			if (!access.permissionsAt(branch).contains(permission)) {
				throw permissionDenied(permission);
			}
		}
		return true;
	}

	@SuppressWarnings("unchecked")
	private static String branchIdOf(HttpServletRequest request) {
		Map<String, String> variables = (Map<String, String>) request
			.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
		return (variables != null) ? variables.get(BRANCH_ID) : null;
	}

	/** A malformed id names no Branch the caller may see. */
	private static UUID parse(String branchId) {
		try {
			return UUID.fromString(branchId);
		}
		catch (IllegalArgumentException ex) {
			throw new DomainException(CommonErrorCode.BRANCH_SCOPE_DENIED, "Malformed branch id");
		}
	}

	private static DomainException permissionDenied(Permission permission) {
		return new DomainException(CommonErrorCode.PERMISSION_DENIED, "Missing permission " + permission);
	}

}
