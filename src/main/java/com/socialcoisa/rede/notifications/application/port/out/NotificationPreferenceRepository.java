package com.socialcoisa.rede.notifications.application.port.out;

import java.util.Optional;

import com.socialcoisa.rede.notifications.domain.NotificationPreferences;

/** One row per user, written on the first change; no row means the defaults. */
public interface NotificationPreferenceRepository {

	Optional<NotificationPreferences> find(String userId);

	void save(String userId, NotificationPreferences preferences);
}
