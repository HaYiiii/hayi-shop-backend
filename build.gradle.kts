import org.openapitools.generator.gradle.plugin.tasks.GenerateTask

plugins {
	java
	alias(libs.plugins.spring.boot)
	alias(libs.plugins.spring.dependency.management)
	alias(libs.plugins.openapi.generator)
}

group = "vn.hayi"
version = "0.0.1-SNAPSHOT"
description = "HaYi Shop backend - Spring Boot modular monolith"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(25)
	}
}

repositories {
	mavenCentral()
}

dependencyManagement {
	imports {
		mavenBom(libs.spring.modulith.bom.get().toString())
	}
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.flywaydb:flyway-mysql")
	implementation("org.springframework.modulith:spring-modulith-starter-core")
	implementation(libs.uuid.creator)
	implementation(libs.shedlock.spring)
	implementation(libs.shedlock.jdbc)
	implementation(libs.bouncycastle.prov)
	implementation("com.github.ben-manes.caffeine:caffeine") // version from the Boot BOM

	runtimeOnly("com.mysql:mysql-connector-j")
	runtimeOnly("org.springframework.modulith:spring-modulith-actuator")
	developmentOnly("org.springframework.boot:spring-boot-docker-compose")
	annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.springframework.modulith:spring-modulith-starter-test")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	testImplementation("org.testcontainers:testcontainers-mysql")
	testImplementation(libs.archunit.junit5)
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Design-first OpenAPI (SOURCE-STRUCTURE §2.8): one interface + model set per surface, generated into build/ and
// never committed. Controllers implement the generated interfaces, so drifting from the spec fails compilation.
val openApiSurfaces = listOf("backoffice", "customer", "public")
val openApiOutput = layout.buildDirectory.dir("generated/openapi")

val openApiGenerateTasks = openApiSurfaces.map { surface ->
	tasks.register<GenerateTask>("openApiGenerate${surface.replaceFirstChar(Char::uppercase)}") {
		group = "openapi"
		generatorName = "spring"
		inputSpec = layout.projectDirectory.file("api-spec/$surface-v1.yaml")
		outputDir = openApiOutput.map { it.dir(surface) }
		apiPackage = "vn.hayi.shop.platform.web.spec.$surface.api"
		modelPackage = "vn.hayi.shop.platform.web.spec.$surface.model"
		modelNameSuffix = "Dto"
		cleanupOutput = true
		configOptions = mapOf(
			"useSpringBoot4" to "true",
			"useJackson3" to "true",
			"interfaceOnly" to "true",
			"skipDefaultInterface" to "true",
			"useTags" to "true",
			"useResponseEntity" to "true",
			"openApiNullable" to "false",
			"documentationProvider" to "none",
			"annotationLibrary" to "none",
			"requestMappingMode" to "api_interface",
			"hideGenerationTimestamp" to "true",
			"sourceFolder" to "src/main/java",
		)
		globalProperties = mapOf("apis" to "", "models" to "", "supportingFiles" to "false")
	}
}

sourceSets {
	main {
		java {
			openApiSurfaces.forEach { surface -> srcDir(openApiOutput.map { it.dir("$surface/src/main/java") }) }
		}
	}
}

tasks.compileJava {
	dependsOn(openApiGenerateTasks)
}

tasks.withType<JavaCompile> {
	options.compilerArgs.add("-parameters")
}

// Local dev only: a throw-away JWT signing key, so sessions end on restart. Real environments set
// HAYI_SECURITY_SIGNING_KEY (API 01-security §4) and HAYI_SECURITY_MFA_ENCRYPTION_KEY (Decision L3). The MFA key below
// is a fixed, public dev value so enrolled TOTP secrets survive a restart; never use it anywhere else.
tasks.bootRun {
	systemProperty("hayi.security.allow-ephemeral-key", "true")
	systemProperty("hayi.security.mfa-encryption-key", "ZGV2LW9ubHktbWZhLWtleS1ub3QtYS1zZWNyZXQhISE=")
}

tasks.withType<Test> {
	useJUnitPlatform()
}
