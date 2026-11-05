package vn.hayi.shop.platform.web;

import java.lang.reflect.Method;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.ElementKind;
import jakarta.validation.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.AnnotatedMethod;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import vn.hayi.shop.platform.security.AuthErrors;
import vn.hayi.shop.shared.error.CommonErrorCode;
import vn.hayi.shop.shared.error.DomainException;
import vn.hayi.shop.shared.error.ErrorCode;
import vn.hayi.shop.shared.error.ThrottledException;

/**
 * Single place that turns exceptions into Problem Details (API README §6). Also serves errors raised by the
 * security filter chain, which delegates here through the {@code HandlerExceptionResolver}.
 */
@RestControllerAdvice
class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	private static final AuthenticationTrustResolver TRUST_RESOLVER = new AuthenticationTrustResolverImpl();

	private final ProblemFactory problems;

	GlobalExceptionHandler(ProblemFactory problems) {
		this.problems = problems;
	}

	@ExceptionHandler(DomainException.class)
	ResponseEntity<ProblemDetail> handleDomain(DomainException ex, HttpServletRequest request) {
		ResponseEntity<ProblemDetail> response = respond(ex.code(), request, ex.detailArgs());
		ex.problemProperties().forEach(response.getBody()::setProperty);
		if (ex instanceof ThrottledException throttled) {
			long seconds = Math.max(1, (throttled.retryAfter().toMillis() + 999) / 1000);
			return ResponseEntity.status(response.getStatusCode())
				.header(HttpHeaders.RETRY_AFTER, Long.toString(seconds))
				.body(response.getBody());
		}
		return response;
	}

	/** Hand-validated bodies ({@link MergePatch}) report like bean validation: {@code errors[]} per field. */
	@ExceptionHandler(InvalidFieldsException.class)
	ResponseEntity<ProblemDetail> handleInvalidFields(InvalidFieldsException ex, HttpServletRequest request) {
		ProblemDetail problem = this.problems.create(CommonErrorCode.VALIDATION_FAILED, request);
		problem.setProperty("errors", this.problems.fieldViolations(ex.violations(), request));
		return ResponseEntity.badRequest().body(problem);
	}

	/**
	 * A constrained parameter (query, header, path) of a controller failed method validation → {@code 400
	 * VALIDATION_FAILED} with one {@code errors[]} entry per parameter, like body validation (API README §6). A
	 * violation raised beyond the web layer (a validated service, entity validation on flush) is a server bug:
	 * {@code 500 INTERNAL_ERROR}, logged.
	 */
	@ExceptionHandler(ConstraintViolationException.class)
	ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException ex,
			HttpServletRequest request) {
		boolean fromController = !ex.getConstraintViolations().isEmpty() && ex.getConstraintViolations()
			.stream()
			.allMatch(violation -> violation.getRootBeanClass() != null && AnnotatedElementUtils
				.hasAnnotation(ClassUtils.getUserClass(violation.getRootBeanClass()), Controller.class));
		if (!fromController) {
			return handleUnexpected(ex, request);
		}
		List<InvalidFieldsException.Violation> violations = ex.getConstraintViolations().stream()
			.map(violation -> new InvalidFieldsException.Violation(parameterName(violation),
					violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName()
						.replaceAll("([a-z0-9])([A-Z])", "$1_$2")
						.toUpperCase(Locale.ROOT)))
			.sorted(Comparator.comparing(InvalidFieldsException.Violation::field))
			.toList();
		return handleInvalidFields(new InvalidFieldsException(violations), request);
	}

	/**
	 * The parameter's name on the wire (Decision Y8): the {@code @RequestHeader} / {@code @RequestParam} /
	 * {@code @PathVariable} name, e.g. {@code If-Match} rather than the Java {@code ifMatch}; the generated interface
	 * carries these annotations, which {@link AnnotatedMethod} merges in. Falls back to the Java name.
	 */
	private static String parameterName(ConstraintViolation<?> violation) {
		String path = violation.getPropertyPath().toString();
		String javaName = path.substring(path.lastIndexOf('.') + 1);
		Path.MethodNode method = null;
		Path.ParameterNode parameter = null;
		for (Path.Node node : violation.getPropertyPath()) {
			if (node.getKind() == ElementKind.METHOD) {
				method = node.as(Path.MethodNode.class);
			}
			else if (node.getKind() == ElementKind.PARAMETER) {
				parameter = node.as(Path.ParameterNode.class);
			}
		}
		if (method == null || parameter == null) {
			return javaName;
		}
		Method target = ReflectionUtils.findMethod(ClassUtils.getUserClass(violation.getRootBeanClass()),
				method.getName(), method.getParameterTypes().toArray(Class<?>[]::new));
		if (target == null) {
			return javaName;
		}
		MethodParameter methodParameter = new AnnotatedMethod(target).getMethodParameters()[parameter
			.getParameterIndex()];
		return httpName(methodParameter).orElse(javaName);
	}

	private static Optional<String> httpName(MethodParameter parameter) {
		RequestHeader header = parameter.getParameterAnnotation(RequestHeader.class);
		if (header != null && StringUtils.hasText(header.name())) {
			return Optional.of(header.name());
		}
		RequestParam param = parameter.getParameterAnnotation(RequestParam.class);
		if (param != null && StringUtils.hasText(param.name())) {
			return Optional.of(param.name());
		}
		PathVariable path = parameter.getParameterAnnotation(PathVariable.class);
		if (path != null && StringUtils.hasText(path.name())) {
			return Optional.of(path.name());
		}
		return Optional.empty();
	}

	/**
	 * Another change of the same aggregate committed first and the loser's optimistic lock failed (Decision O5). An
	 * edit based on {@code If-Match} gets the same answer as a stale tag, {@code 412 VERSION_CONFLICT}; a request
	 * without (or with a blank) {@code If-Match} (a semantic command, API README §8) never named a version, so it gets
	 * {@code 409 CONCURRENT_MODIFICATION}.
	 */
	@ExceptionHandler(OptimisticLockingFailureException.class)
	ResponseEntity<ProblemDetail> handleLostUpdate(OptimisticLockingFailureException ex, HttpServletRequest request) {
		boolean basedOnVersion = StringUtils.hasText(request.getHeader(HttpHeaders.IF_MATCH));
		return respond(basedOnVersion ? CommonErrorCode.VERSION_CONFLICT : CommonErrorCode.CONCURRENT_MODIFICATION,
				request);
	}

	/** {@code AUTH_REQUIRED}, or the precise cause: {@code AUTH_TOKEN_EXPIRED}, {@code AUTH_SESSION_REVOKED}. */
	@ExceptionHandler(AuthenticationException.class)
	ResponseEntity<ProblemDetail> handleUnauthenticated(AuthenticationException ex, HttpServletRequest request) {
		return respond(AuthErrors.codeOf(ex), request);
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

	/** Last resort: full detail goes to the server log (with traceId), never to the client. */
	@ExceptionHandler(Exception.class)
	ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) {
		logUnexpected(ex, request);
		return respond(CommonErrorCode.INTERNAL_ERROR, request);
	}

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		HttpServletRequest servletRequest = ((ServletWebRequest) request).getRequest();
		ProblemDetail problem = this.problems.create(CommonErrorCode.VALIDATION_FAILED, servletRequest);
		problem.setProperty("errors", this.problems.fieldViolations(ex.getBindingResult(), servletRequest));
		return ResponseEntity.badRequest().headers(headers).body(problem);
	}

	/**
	 * Every other Spring MVC error (malformed JSON, missing parameter, unknown route, wrong method…) gets a fresh
	 * problem in our shape, so framework/parser wording never reaches the client. Request-shape errors (400) are
	 * {@code VALIDATION_FAILED}; server-side failures (5xx except 503) are logged and become {@code INTERNAL_ERROR};
	 * the rest use the status name as code (e.g. {@code METHOD_NOT_ALLOWED}, {@code SERVICE_UNAVAILABLE}).
	 */
	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
			HttpStatusCode statusCode, WebRequest request) {
		HttpServletRequest servletRequest = ((ServletWebRequest) request).getRequest();
		HttpStatus status = HttpStatus.valueOf(statusCode.value());
		if (status.is5xxServerError()) {
			logUnexpected(ex, servletRequest);
		}
		ProblemDetail problem = this.problems.create(status, frameworkCode(status), servletRequest);
		return ResponseEntity.status(status).headers(headers).body(problem);
	}

	private static String frameworkCode(HttpStatus status) {
		if (status == HttpStatus.BAD_REQUEST) {
			return CommonErrorCode.VALIDATION_FAILED.name();
		}
		if (status.is5xxServerError() && status != HttpStatus.SERVICE_UNAVAILABLE) {
			return CommonErrorCode.INTERNAL_ERROR.name();
		}
		return status.name();
	}

	private static void logUnexpected(Exception ex, HttpServletRequest request) {
		LOG.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
	}

	private ResponseEntity<ProblemDetail> respond(ErrorCode code, HttpServletRequest request, Object... detailArgs) {
		return ResponseEntity.status(this.problems.statusOf(code)).body(this.problems.create(code, request, detailArgs));
	}

}
