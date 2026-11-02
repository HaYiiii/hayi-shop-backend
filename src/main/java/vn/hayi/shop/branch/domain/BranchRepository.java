package vn.hayi.shop.branch.domain;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.Repository;

public interface BranchRepository extends Repository<Branch, UUID> {

	/** Inserts now, so a concurrent duplicate code fails here on {@code uk_brn_branch__branch_code}. */
	Branch saveAndFlush(Branch branch);

	Optional<Branch> findById(UUID id);

	boolean existsByBranchCode(String branchCode);

}
