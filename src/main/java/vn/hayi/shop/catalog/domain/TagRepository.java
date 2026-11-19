package vn.hayi.shop.catalog.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.Repository;

public interface TagRepository extends Repository<Tag, UUID> {

	/** Writes now, so a concurrent duplicate slug fails here on {@code uk_cat_tag__slug}. */
	Tag saveAndFlush(Tag tag);

	Optional<Tag> findById(UUID id);

	boolean existsBySlug(String slug);

	boolean existsBySlugAndIdNot(String slug, UUID id);

	List<Tag> findAllByOrderBySlugAsc();

	List<Tag> findByIdIn(Collection<UUID> ids);

}
