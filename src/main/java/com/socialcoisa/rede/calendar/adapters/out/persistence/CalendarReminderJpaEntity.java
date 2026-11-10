package com.socialcoisa.rede.calendar.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.calendar.domain.CalendarReminder;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(name = "calendar_reminders", indexes = {
		@Index(name = "idx_calendar_reminders_due", columnList = "due_at"),
		@Index(name = "uq_calendar_reminders_entry_user_minutes",
				columnList = "entry_id, user_id, minutes_before", unique = true),
		@Index(name = "idx_calendar_reminders_entry", columnList = "entry_id")})
class CalendarReminderJpaEntity {

	@Id
	private String id;

	@Column(name = "entry_id", nullable = false)
	private String entryId;

	@Column(name = "user_id", nullable = false)
	private String userId;

	@Column(name = "minutes_before", nullable = false)
	private int minutesBefore;

	@Column(name = "due_at", nullable = false)
	private Instant dueAt;

	@Column(nullable = false)
	private int attempts;

	@Column(name = "sent_at")
	private Instant sentAt;

	@Column(name = "last_error", length = 500)
	private String lastError;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected CalendarReminderJpaEntity() {
	}

	static CalendarReminderJpaEntity fromDomain(CalendarReminder reminder) {
		CalendarReminderJpaEntity e = new CalendarReminderJpaEntity();
		e.id = reminder.id();
		e.entryId = reminder.entryId();
		e.userId = reminder.userId();
		e.minutesBefore = reminder.minutesBefore();
		e.dueAt = reminder.dueAt();
		e.attempts = reminder.attempts();
		e.sentAt = reminder.sentAt();
		e.lastError = reminder.lastError();
		e.createdAt = reminder.createdAt();
		return e;
	}

	CalendarReminder toDomain() {
		return new CalendarReminder(id, entryId, userId, minutesBefore, dueAt, attempts, sentAt, lastError,
			createdAt);
	}

}