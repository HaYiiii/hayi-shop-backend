package vn.hayi.shop.platform.web;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import vn.hayi.shop.shared.error.CommonErrorCode;
import vn.hayi.shop.shared.error.ErrorCode;

/**
 * Single place that turns exceptions into Problem Details (API README §6). Also serves errors raised by the
 * security filter chain, which delegates here through the {@code HandlerExceptionResolver}.
 */
@RestControllerAdvice
class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	private static final AuthenticationTrustResolver TRUST_RESOLVER = new AuthenticationTrustResolverImpl();

	private final ProblemFactory problems;

	GlobalExceptionHandler(ProblemFactory problems) {
		this.problems = problems;
	}

	@ExceptionHandler(AuthenticationException.class)
	ResponseEntity<ProblemDetail> handleUnauthenticated(AuthenticationException ex, HttpServletRequest request) {
		return respond(CommonErrorCode.AUTH_REQUIRED, request);
	}

	/** Anonymous callers hitting a protected method must sign in first, so they get 401 rather than 403. */
	@ExceptionHandler(AccessDeniedException.class)
	ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (TRUST_RESOLVER.isAuthenticated(authentication)) {
			return respond(CommonErrorCode.PERMISSION_DENIED, request);
		}
		return respond(CommonErrorCode.AUTH_REQUIRED, request);
	}

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		HttpServletRequest servletRequest = ((ServletWebRequest) request).getRequest();
		ProblemDetail problem = this.problems.create(CommonErrorCode.VALIDATION_FAILED, servletRequest);
		problem.setProperty("errors", this.problems.fieldViolations(ex.getBindingResult(), servletRequest));
		return ResponseEntity.badRequest().headers(headers).body(problem);
	}

	private ResponseEntity<ProblemDetail> respond(ErrorCode code, HttpServletRequest request, Object... detailArgs) {
		return ResponseEntity.status(this.problems.statusOf(code)).body(this.problems.create(code, request, detailArgs));
	}

}
