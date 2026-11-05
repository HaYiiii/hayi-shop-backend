package vn.hayi.shop.catalog.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

public interface CategoryRepository extends Repository<Category, UUID> {

	/** Inserts now, so a concurrent duplicate slug fails here on {@code uk_cat_category__slug}. */
	Category saveAndFlush(Category category);

	Optional<Category> findById(UUID id);

	/** Writes pending changes now, so a lost optimistic lock fails inside the request. */
	void flush();

	/**
	 * Locks the row until commit. Every change that depends on a Category's children (creating or moving one under
	 * it, archiving it, reordering them) locks it first, so they run one after another and cannot build a third
	 * level or a child under an archived parent.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select c from Category c where c.id = :id")
	Optional<Category> findByIdForUpdate(UUID id);

	boolean existsBySlug(String slug);

	boolean existsBySlugAndIdNot(String slug, UUID id);

	boolean existsByParentIdAndStatusNot(UUID parentId, CategoryStatus status);

	boolean existsByParentId(UUID parentId);

	List<Category> findAllByOrderBySortOrderAscIdAsc();

	List<Category> findByStatusOrderBySortOrderAscIdAsc(CategoryStatus status);

	/** Siblings under {@code parentId}; the roots when {@code null}. */
	@Query("select c from Category c where c.status <> :excluded and ((:parentId is null and c.parentId is null)"
			+ " or c.parentId = :parentId) order by c.sortOrder, c.id")
	List<Category> findSiblings(UUID parentId, CategoryStatus excluded);

}
