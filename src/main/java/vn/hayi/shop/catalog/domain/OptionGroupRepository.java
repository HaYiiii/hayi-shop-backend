package vn.hayi.shop.catalog.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

public interface OptionGroupRepository extends Repository<OptionGroup, UUID> {

	/** Inserts or updates now, so a concurrent duplicate code fails here on its unique key. */
	OptionGroup saveAndFlush(OptionGroup group);

	Optional<OptionGroup> findById(UUID id);

	boolean existsByCode(String code);

	List<OptionGroup> findByIdIn(Collection<UUID> ids);

	/** The group holding this item, archived or not. */
	@Query("select g from OptionGroup g join g.items i where i.id = :itemId")
	Optional<OptionGroup> findByItemId(UUID itemId);

	List<OptionGroup> findAllByOrderByCodeAsc();

	List<OptionGroup> findByStatusOrderByCodeAsc(OptionStatus status);

}
