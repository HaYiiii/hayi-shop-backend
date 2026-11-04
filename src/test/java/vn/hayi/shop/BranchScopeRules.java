package vn.hayi.shop;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.springframework.data.repository.Repository;

import vn.hayi.shop.shared.scope.BranchOwned;

/**
 * Entities of one Branch are never looked up by bare id (API README §3.1, Decision S7): an id of Branch B on a path of
 * Branch A must not be found at all, so it gets the same {@code 404} as an unknown id. Shared by
 * {@link ArchitectureTest}.
 */
public final class BranchScopeRules {

	static final Set<String> BARE_ID_LOOKUPS = Set.of("findById", "existsById", "getById", "getReferenceById",
			"deleteById", "findAllById");

	/** Includes inherited methods, so extending {@code CrudRepository}/{@code JpaRepository} is caught too. */
	public static final ArchRule branchOwnedRepositoriesHaveNoBareIdLookups = classes()
		.that().areInterfaces()
		.and().areAssignableTo(Repository.class)
		.should(new ArchCondition<>("have no bare id lookup when the aggregate is BranchOwned") {
			@Override
			public void check(JavaClass repository, ConditionEvents events) {
				Class<?> type = repository.reflect();
				Optional<Class<?>> aggregate = aggregateOf(type);
				if (aggregate.isEmpty() || !BranchOwned.class.isAssignableFrom(aggregate.get())) {
					return;
				}
				Arrays.stream(type.getMethods())
					.map(Method::getName)
					.filter(BARE_ID_LOOKUPS::contains)
					.distinct()
					.sorted()
					.forEach(name -> events.add(SimpleConditionEvent.violated(repository, repository.getName()
							+ " looks up the BranchOwned " + aggregate.get().getSimpleName() + " by bare id: " + name
							+ " (query by (branchId, …) instead)")));
			}
		})
		.allowEmptyShould(true);

	private BranchScopeRules() {
	}

	/** The {@code T} of {@code Repository<T, ID>}, searched through the interface hierarchy. */
	static Optional<Class<?>> aggregateOf(Class<?> type) {
		for (Type parent : type.getGenericInterfaces()) {
			if (parent instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> raw
					&& Repository.class.isAssignableFrom(raw)
					&& parameterized.getActualTypeArguments()[0] instanceof Class<?> aggregate) {
				return Optional.of(aggregate);
			}
			Class<?> raw = (parent instanceof ParameterizedType parameterized)
					? (Class<?>) parameterized.getRawType() : (Class<?>) parent;
			Optional<Class<?>> inherited = aggregateOf(raw);
			if (inherited.isPresent()) {
				return inherited;
			}
		}
		return Optional.empty();
	}

}
