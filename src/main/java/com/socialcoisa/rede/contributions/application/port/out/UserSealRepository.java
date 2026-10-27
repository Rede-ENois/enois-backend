package com.socialcoisa.rede.contributions.application.port.out;

import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.contributions.domain.SealType;
import com.socialcoisa.rede.contributions.domain.UserSeal;

public interface UserSealRepository {

	UserSeal save(UserSeal seal);

	Optional<UserSeal> findByUserAndType(String userId, SealType type);

	List<UserSeal> findActiveByUser(String userId);
}
