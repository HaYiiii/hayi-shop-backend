package vn.hayi.shop;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Layer rules inside each module (SOURCE-STRUCTURE.md §2.2).
 */
@AnalyzeClasses(packages = "vn.hayi.shop", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

	@ArchTest
	static final ArchRule domainIsFrameworkFreeOfWebAndPlatform = noClasses()
		.that().resideInAPackage("..domain..")
		.should().dependOnClassesThat().resideInAnyPackage("org.springframework.web..", "vn.hayi.shop.platform..", "..web..", "..application..", "..infrastructure..")
		.allowEmptyShould(true);

	@ArchTest
	static final ArchRule onlyWebLayerUsesWebAnnotations = noClasses()
		.that().resideOutsideOfPackages("..web..", "vn.hayi.shop.platform..")
		.should().dependOnClassesThat().resideInAPackage("org.springframework.web.bind.annotation..")
		.allowEmptyShould(true);

	@ArchTest
	static final ArchRule applicationDoesNotDependOnWeb = noClasses()
		.that().resideInAPackage("..application..")
		.should().dependOnClassesThat().resideInAPackage("..web..")
		.allowEmptyShould(true);

}
