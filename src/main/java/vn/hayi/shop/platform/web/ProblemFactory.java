package vn.hayi.shop.platform.web;

import java.net.URI;
import java.util.List;
import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.validation.BindingResult;
import org.springframework.web.servlet.LocaleResolver;

import vn.hayi.shop.platform.observability.TraceIds;
import vn.hayi.shop.shared.error.CommonErrorCode;
import vn.hayi.shop.shared.error.ErrorCode;

/**
 * Builds RFC 9457 Problem Details in the HaYi shape (API README §6): stable {@code code}, {@code traceId},
 * {@code title}/{@code detail} localized from {@code messages*.properties} by {@code Accept-Language}.
 */
@Component
class ProblemFactory {

	static final String TYPE_PREFIX = "https://docs.hayi.vn/errors/";

	private final MessageSource messages;

	private final LocaleResolver localeResolver;

	ProblemFactory(MessageSource messages, LocaleResolver localeResolver) {
		this.messages = messages;
		this.localeResolver = localeResolver;
	}

	/** Exhaustive on purpose: a new {@link ErrorKind} does not compile until it has a status (API README §6). */
	HttpStatus statusOf(ErrorCode code) {
		return switch (code.kind()) {
			case VALIDATION -> HttpStatus.BAD_REQUEST;
			case UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
			case FORBIDDEN -> HttpStatus.FORBIDDEN;
			case NOT_FOUND -> HttpStatus.NOT_FOUND;
			case CONFLICT -> HttpStatus.CONFLICT;
			case BUSINESS_RULE -> HttpStatus.UNPROCESSABLE_CONTENT;
			case INTERNAL -> HttpStatus.INTERNAL_SERVER_ERROR;
		};
	}

	ProblemDetail create(ErrorCode code, HttpServletRequest request, Object... detailArgs) {
		return create(statusOf(code), code.name(), request, detailArgs);
	}

	/** Also used for framework errors whose code is derived from the status rather than an {@link ErrorCode}. */
	ProblemDetail create(HttpStatus status, String code, HttpServletRequest request, Object... detailArgs) {
		Locale locale = this.localeResolver.resolveLocale(request);
		ProblemDetail problem = ProblemDetail.forStatus(status);
		problem.setType(URI.create(TYPE_PREFIX + code));
		problem.setTitle(message("error." + code + ".title", null, locale));
		problem.setDetail(message("error." + code + ".detail", detailArgs, locale));
		problem.setProperty("code", code);
		decorate(problem);
		return problem;
	}

	/** Falls back to the generic {@code error.default.*} text, so the client always gets its language. */
	private String message(String key, Object[] args, Locale locale) {
		String fallbackKey = "error.default" + key.substring(key.lastIndexOf('.'));
		String fallback = this.messages.getMessage(fallbackKey, null, null, locale);
		return this.messages.getMessage(key, args, fallback, locale);
	}

	/** {@code errors[]} entries for field validation (API README §6); messages localized like title/detail. */
	List<FieldViolation> fieldViolations(BindingResult result, HttpServletRequest request) {
		Locale locale = this.localeResolver.resolveLocale(request);
		return result.getFieldErrors().stream()
			.map(error -> new FieldViolation(error.getField(), toUpperSnake(error.getCode()),
					this.messages.getMessage(error, locale)))
			.toList();
	}

	/** {@code NotBlank} → {@code NOT_BLANK}. */
	private static String toUpperSnake(String constraint) {
		if (constraint == null) {
			return CommonErrorCode.VALIDATION_FAILED.name();
		}
		return constraint.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
	}

	record FieldViolation(String field, String code, String message) {
	}

	/** Adds the request's trace id; every problem carries it (API README §9). */
	void decorate(ProblemDetail problem) {
		problem.setProperty("traceId", TraceIds.current());
	}

}
