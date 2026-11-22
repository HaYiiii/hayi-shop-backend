package vn.hayi.shop.identity.web.customer;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;
import vn.hayi.shop.identity.application.CustomerAccountService;
import vn.hayi.shop.platform.security.AuthenticatedSession;
import vn.hayi.shop.platform.web.MergePatch;
import vn.hayi.shop.platform.web.VersionTags;
import vn.hayi.shop.platform.web.spec.customer.api.CustomerProfileApi;
import vn.hayi.shop.platform.web.spec.customer.model.CustomerProfileDto;
import vn.hayi.shop.shared.patch.Change;

/**
 * The signed-in Customer's own profile (API 07 §1, Customer §3.2). Lives next to {@code /auth/me} in Identity, which
 * holds the login phone and already reads {@code customer.api}; the session alone decides whose profile it is.
 */
@RestController
class CustomerProfileController implements CustomerProfileApi {

	private final CustomerAccountService accounts;

	CustomerProfileController(CustomerAccountService accounts) {
		this.accounts = accounts;
	}

	@Override
	public ResponseEntity<CustomerProfileDto> getMyProfile() {
		return withETag(this.accounts.profile(AuthenticatedSession.require().userId()));
	}

	@Override
	public ResponseEntity<CustomerProfileDto> updateMyProfile(JsonNode body, String ifMatch, String xCsrfToken) {
		var expected = VersionTags.require(ifMatch);
		MergePatch patch = MergePatch.of(body);
		Change<String> displayName = patch.requiredText("displayName", 128);
		patch.validate();
		var userId = AuthenticatedSession.require().userId();
		var current = this.accounts.profile(userId);
		return withETag(this.accounts.rename(userId, displayName.applyTo(current.displayName()).strip(),
				expected.value()));
	}

	private static ResponseEntity<CustomerProfileDto> withETag(CustomerAccountService.CustomerProfileView profile) {
		return ResponseEntity.ok()
			.eTag(VersionTags.of(profile.version()))
			.body(new CustomerProfileDto(profile.customerId(), profile.displayName(), profile.phoneMasked(), null));
	}

}
