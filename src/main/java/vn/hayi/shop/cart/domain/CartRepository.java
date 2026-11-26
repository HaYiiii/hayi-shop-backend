package vn.hayi.shop.cart.domain;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

public interface CartRepository extends Repository<Cart, UUID> {

	@Query("select c from Cart c where c.customerId = :customerId and c.branchId = :branchId and c.status = vn.hayi.shop.cart.domain.CartStatus.ACTIVE")
	Optional<Cart> findActive(UUID customerId, UUID branchId);

	/**
	 * Locks the Cart row, so concurrent changes to one Cart run one after the other, and moves its version once (its
	 * items and {@code updatedAt} are excluded from optimistic locking).
	 * <p>
	 * Call it in a {@code READ_COMMITTED} transaction: the items are loaded afterwards by a plain read, which under
	 * REPEATABLE READ would see the snapshot taken before the lock and miss what a concurrent change just committed.
	 * (A {@code join fetch} does not help: Hibernate reads the join first and locks the Cart row in a follow-on
	 * query.)
	 */
	@Lock(LockModeType.PESSIMISTIC_FORCE_INCREMENT)
	@Query("select c from Cart c where c.customerId = :customerId and c.branchId = :branchId and c.status = vn.hayi.shop.cart.domain.CartStatus.ACTIVE")
	Optional<Cart> findActiveForUpdate(UUID customerId, UUID branchId);

}
