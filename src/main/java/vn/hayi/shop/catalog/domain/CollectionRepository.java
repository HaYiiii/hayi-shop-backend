package vn.hayi.shop.catalog.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

public interface CollectionRepository extends Repository<Collection, UUID> {

	/** Writes now, so a concurrent duplicate slug fails here on {@code uk_cat_collection__slug}. */
	Collection saveAndFlush(Collection collection);

	Optional<Collection> findById(UUID id);

	boolean existsBySlug(String slug);

	boolean existsBySlugAndIdNot(String slug, UUID id);

	List<Collection> findAll();

	List<Collection> findByStatus(CollectionStatus status);

	List<Collection> findByStatusNot(CollectionStatus status);

	@Query("select coalesce(max(c.sortOrder) + 1, 0) from Collection c where c.status <> :excluded")
	int nextSortOrder(CollectionStatus excluded);

}
