package dev.boujelbene.mealplanner;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * Enforces module boundaries: fails the build if a module accesses another module's internals
 * or if modules form a dependency cycle. Also generates module documentation into build/spring-modulith-docs.
 */
class ModularityTests {

	private final ApplicationModules modules = ApplicationModules.of(BackendApplication.class);

	@Test
	void verifiesModuleStructure() {
		modules.verify();
	}

	@Test
	void writesModuleDocumentation() {
		new Documenter(modules).writeDocumentation();
	}

}
