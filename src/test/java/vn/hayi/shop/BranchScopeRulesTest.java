package vn.hayi.shop;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.EvaluationResult;
import org.junit.jupiter.api.Test;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.Repository;

import vn.hayi.shop.shared.scope.BranchOwned;

/** {@link BranchScopeRules} catches the repositories it is written for, and only those. */
class BranchScopeRulesTest {

	@Test
	void bareIdLookupOnABranchOwnedAggregateFailsTheRule() {
		EvaluationResult result = evaluate(LeakyRepository.class);

		assertThat(result.getFailureReport().getDetails()).singleElement().asString()
			.contains("LeakyRepository", "findById");
	}

	@Test
	void inheritedCrudLookupsAreCaughtToo() {
		List<String> details = evaluate(CrudLeakyRepository.class).getFailureReport().getDetails();

		assertThat(details).anyMatch(detail -> detail.contains("findById"))
			.anyMatch(detail -> detail.contains("deleteById"));
	}

	@Test
	void lookupByBranchAndOtherAggregatesPass() {
		assertThat(evaluate(ScopedRepository.class).hasViolation()).isFalse();
		assertThat(evaluate(GlobalRepository.class).hasViolation()).isFalse();
	}

	private static EvaluationResult evaluate(Class<?> repository) {
		JavaClasses classes = new ClassFileImporter().importClasses(repository);
		return BranchScopeRules.branchOwnedRepositoriesHaveNoBareIdLookups.evaluate(classes);
	}

	static class Owned implements BranchOwned {

		@Override
		public UUID getBranchId() {
			return null;
		}

	}

	static class Global {

	}

	interface LeakyRepository extends Repository<Owned, UUID> {

		Optional<Owned> findById(UUID id);

	}

	interface CrudLeakyRepository extends CrudRepository<Owned, UUID> {

	}

	interface ScopedRepository extends Repository<Owned, UUID> {

		Optional<Owned> findByBranchIdAndId(UUID branchId, UUID id);

	}

	interface GlobalRepository extends Repository<Global, UUID> {

		Optional<Global> findById(UUID id);

	}

}
