package vn.hayi.shop.catalog.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.Repository;

public interface OptionGroupRepository extends Repository<OptionGroup, UUID> {

	/** Inserts or updates now, so a concurrent duplicate code fails here on its unique key. */
	OptionGroup saveAndFlush(OptionGroup group);

	Optional<OptionGroup> findById(UUID id);

	boolean existsByCode(String code);

	List<OptionGroup> findAllByOrderByCodeAsc();

	List<OptionGroup> findByStatusOrderByCodeAsc(OptionStatus status);

}
