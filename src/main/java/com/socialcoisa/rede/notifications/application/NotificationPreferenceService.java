package com.socialcoisa.rede.notifications.application;

import java.time.Clock;

import org.springframework.stereotype.Service;

import com.socialcoisa.rede.notifications.application.port.out.NotificationPreferenceRepository;
import com.socialcoisa.rede.notifications.domain.NotificationPreferences;

/** Push categories and the weekly digest toggle (GET/PUT /me/notification-preferences). */
@Service
public class NotificationPreferenceService {

	private final NotificationPreferenceRepository repository;
	private final Clock clock;

	public NotificationPreferenceService(NotificationPreferenceRepository repository, Clock clock) {
		this.repository = repository;
		this.clock = clock;
	}

	public NotificationPreferences of(String userId) {
		return repository.find(userId).orElse(NotificationPreferences.DEFAULTS);
	}

	public NotificationPreferences update(String userId, Boolean weeklyDigest, Boolean pushSocial,
			Boolean pushMessages, Boolean pushGroups, Boolean pushJobs, Boolean pushSystem) {
		NotificationPreferences updated = of(userId).with(weeklyDigest, pushSocial, pushMessages, pushGroups,
			pushJobs, pushSystem, clock.instant());
		repository.save(userId, updated);
		return updated;
	}

	public boolean allowsPush(String userId, String kind) {
		return of(userId).allowsPush(kind);
	}
}
