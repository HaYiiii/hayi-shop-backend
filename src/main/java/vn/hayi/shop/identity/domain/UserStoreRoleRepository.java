package vn.hayi.shop.identity.domain;

import java.util.UUID;

import org.springframework.data.repository.Repository;

public interface UserStoreRoleRepository extends Repository<UserStoreRole, UUID> {

	UserStoreRole save(UserStoreRole role);

}
