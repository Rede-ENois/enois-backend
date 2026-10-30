package com.socialcoisa.rede.moderation.application.port.out;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.moderation.domain.SanctionStatus;
import com.socialcoisa.rede.moderation.domain.UserSanction;

public interface SanctionRepository {

	UserSanction save(UserSanction sanction);

	Optional<UserSanction> findById(String id);

	/** Sancao ativa do usuario (restricao ou ban) — no maximo uma por conta. */
	Optional<UserSanction> findActiveByUser(String userId);

	/** Active sanctions of these accounts, in one query. */
	List<UserSanction> findActiveByUsers(Collection<String> userIds);

	List<UserSanction> findByUser(String userId, int limit);

	/** Sanções de toda a rede num estado, mais recentes primeiro (fila do admin). */
	List<UserSanction> findByStatus(SanctionStatus status, int limit);
}
