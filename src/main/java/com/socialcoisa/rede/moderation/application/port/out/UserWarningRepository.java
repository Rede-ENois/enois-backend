package com.socialcoisa.rede.moderation.application.port.out;

import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.moderation.domain.UserWarning;

public interface UserWarningRepository {

	UserWarning save(UserWarning warning);

	Optional<UserWarning> findById(String id);

	/** Histórico da conta, mais recente primeiro. */
	List<UserWarning> findByUser(String userId, int limit);

	/** Quantas advertências ativas a conta acumulou (escalada de decisão administrativa). */
	long countActiveByUser(String userId);
}