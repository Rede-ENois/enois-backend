package com.socialcoisa.rede.preferences.adapters.out.persistence;

import java.time.Instant;
import java.time.LocalDate;

import com.socialcoisa.rede.preferences.domain.UserPreference;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "user_preferences")
class UserPreferenceJpaEntity {

	@Id
	@Column(name = "user_id", nullable = false)
	private String userId;

	@Column(name = "theme_id", length = 64)
	private String themeId;

	@Column(name = "liked_topics", length = 400)
	private String likedTopics;

	@Column(name = "blocked_topics", length = 400)
	private String blockedTopics;

	@Column(name = "disabled_modules", length = 400)
	private String disabledModules;

	@Column(name = "night_rest_reminder", nullable = false)
	private boolean nightRestReminder = true;

	/** Data local da noite em que o lembrete foi dispensado (null = nunca dispensou). */
	@Column(name = "night_rest_dismissed_on")
	private LocalDate nightRestDismissedOn;

	@Column(name = "lite_mode", nullable = false)
	private boolean liteMode;

	/** Interface classica e economica (BATATA); implica o modo lite na apresentacao. */
	@Column(name = "batata_mode", nullable = false)
	private boolean batataMode;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected UserPreferenceJpaEntity() {
	}

	UserPreferenceJpaEntity(String userId, String themeId, Instant updatedAt) {
		this.userId = userId;
		this.themeId = themeId;
		this.updatedAt = updatedAt;
	}

	static UserPreferenceJpaEntity fromDomain(UserPreference preference) {
		UserPreferenceJpaEntity entity = new UserPreferenceJpaEntity(preference.userId(), preference.themeId(),
			preference.updatedAt());
		entity.likedTopics = preference.likedTopics();
		entity.blockedTopics = preference.blockedTopics();
		entity.disabledModules = preference.disabledModules();
		entity.nightRestReminder = preference.nightRestReminder();
		entity.nightRestDismissedOn = preference.nightRestDismissedOn();
		entity.liteMode = preference.liteMode();
		entity.batataMode = preference.batataMode();
		return entity;
	}

	UserPreference toDomain() {
		return new UserPreference(userId, themeId, likedTopics, blockedTopics, disabledModules,
			nightRestReminder, nightRestDismissedOn, liteMode, batataMode, updatedAt);
	}
}
