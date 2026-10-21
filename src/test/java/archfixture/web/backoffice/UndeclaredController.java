package archfixture.web.backoffice;

import org.springframework.web.bind.annotation.RestController;

import vn.hayi.shop.platform.security.Permission;
import vn.hayi.shop.platform.security.RequiresPermission;

/**
 * Input of {@code AuthorizationRulesTest}, outside {@code vn.hayi.shop} so component scanning never picks it up:
 * a Backoffice controller with one endpoint that declares no permission.
 */
@RestController
public class UndeclaredController {

	@RequiresPermission(Permission.ORDER_VIEW)
	public void declared() {
	}

	public void undeclared() {
	}

	void helper() {
	}

}
