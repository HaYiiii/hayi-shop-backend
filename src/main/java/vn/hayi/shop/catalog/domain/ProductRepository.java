package vn.hayi.shop.catalog.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

public interface ProductRepository extends Repository<Product, UUID> {

	/** Inserts now, so a concurrent duplicate code, slug or SKU fails here on its unique key. */
	Product saveAndFlush(Product product);

	Optional<Product> findById(UUID id);

	boolean existsByProductCode(String productCode);

	boolean existsBySlug(String slug);

	/** Which of {@code skus} a Variant (archived included) already uses. */
	@Query("select v.sku from ProductVariant v where v.sku in :skus order by v.sku")
	List<String> findTakenSkus(Collection<String> skus);

	boolean existsByPrimaryCategoryIdAndStatusNot(UUID primaryCategoryId, ProductStatus status);

	/**
	 * Products in {@code statuses}, optionally with this primary Category, optionally matching {@code pattern} (a
	 * lower-case {@code LIKE} pattern where {@code !} escapes) on name, code or a Variant SKU (Decision Z6).
	 */
	@Query(value = "select p from Product p where p.status in :statuses"
			+ " and (:categoryId is null or p.primaryCategoryId = :categoryId)"
			+ " and (:pattern is null or lower(p.name) like :pattern escape '!'"
			+ " or lower(p.productCode) like :pattern escape '!'"
			+ " or exists (select 1 from Product p2 join p2.variants v where p2 = p"
			+ " and lower(v.sku) like :pattern escape '!'))",
			countQuery = "select count(p) from Product p where p.status in :statuses"
					+ " and (:categoryId is null or p.primaryCategoryId = :categoryId)"
					+ " and (:pattern is null or lower(p.name) like :pattern escape '!'"
					+ " or lower(p.productCode) like :pattern escape '!'"
					+ " or exists (select 1 from Product p2 join p2.variants v where p2 = p"
					+ " and lower(v.sku) like :pattern escape '!'))")
	Page<Product> search(Collection<ProductStatus> statuses, UUID categoryId, String pattern, Pageable pageable);

}
