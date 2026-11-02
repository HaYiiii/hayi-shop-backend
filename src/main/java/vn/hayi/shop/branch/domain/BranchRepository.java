package vn.hayi.shop.branch.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.Repository;

public interface BranchRepository extends Repository<Branch, UUID> {

	/** Inserts now, so a concurrent duplicate code fails here on {@code uk_brn_branch__branch_code}. */
	Branch saveAndFlush(Branch branch);

	Optional<Branch> findById(UUID id);

	boolean existsByBranchCode(String branchCode);

	List<Branch> findAllByOrderByBranchCode();

	List<Branch> findByIdInOrderByBranchCode(Collection<UUID> ids);

}
