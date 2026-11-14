package com.socialcoisa.rede.notifications.application.port.out;

import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.notifications.domain.NotificationPreferences;

/** One row per user, written on the first change; no row means the defaults. */
public interface NotificationPreferenceRepository {

	Optional<NotificationPreferences> find(String userId);

	/** Trava a conta persistida antes de alterar preferências, inclusive na primeira gravação. */
	void lockForUpdate(String userId);

	void save(String userId, NotificationPreferences preferences);

	/** Assinantes de projetos após o cursor exclusivo, em ordem de id, com teto de 200 por página. */
	List<String> findCommunityProjectSubscriberIds(String afterUserId, int limit);
}
