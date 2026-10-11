package vn.hayi.shop;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * Fails the build when a module reaches into another module's internals (Decision H2).
 */
class ModularityTest {

	static final ApplicationModules MODULES = ApplicationModules.of(HayiShopApplication.class);

	@Test
	void verifiesModuleBoundaries() {
		MODULES.verify();
	}

	@Test
	void writesModuleDocumentation() {
		new Documenter(MODULES).writeModulesAsPlantUml().writeIndividualModulesAsPlantUml();
	}

}
