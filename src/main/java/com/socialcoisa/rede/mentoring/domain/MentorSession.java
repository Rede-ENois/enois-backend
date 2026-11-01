package com.socialcoisa.rede.mentoring.domain;

import java.time.Instant;

public class MentorSession {

	private final String id;
	private final String mentorId;
	private final String menteeId;
	private final String slotId;
	private final String message;
	private final SessionStatus status;
	/** Valor retido no escrow na inscrição (milésimos de faísca); null em sessão gratuita. */
	private final Long priceSparks;
	/** Como a sala foi escolhida quando a sessão foi marcada. */
	private final RoomMode roomMode;
	/** Link da sala, resolvido no agendamento e congelado: trocar a sala do perfil não muda a sessão. */
	private final String roomUrl;
	private final Instant createdAt;

	private MentorSession(String id, String mentorId, String menteeId, String slotId, String message,
			SessionStatus status, Long priceSparks, RoomMode roomMode, String roomUrl, Instant createdAt) {
		this.id = id;
		this.mentorId = mentorId;
		this.menteeId = menteeId;
		this.slotId = slotId;
		this.message = message;
		this.status = status;
		this.priceSparks = priceSparks;
		this.roomMode = roomMode == null ? RoomMode.JITSI_SESSION : roomMode;
		this.roomUrl = roomUrl;
		this.createdAt = createdAt;
	}

	public static MentorSession schedule(String id, String mentorId, String menteeId, String slotId, String message,
			Instant now) {
		return schedule(id, mentorId, menteeId, slotId, message, null, now);
	}

	public static MentorSession schedule(String id, String mentorId, String menteeId, String slotId, String message,
			Long priceSparks, Instant now) {
		return schedule(id, mentorId, menteeId, slotId, message, priceSparks, RoomMode.JITSI_SESSION, null, now);
	}

	public static MentorSession schedule(String id, String mentorId, String menteeId, String slotId, String message,
			Long priceSparks, RoomMode roomMode, String roomUrl, Instant now) {
		return new MentorSession(id, mentorId, menteeId, slotId, message, SessionStatus.SCHEDULED, priceSparks,
			roomMode, roomUrl, now);
	}

	public static MentorSession restore(String id, String mentorId, String menteeId, String slotId, String message,
			SessionStatus status, Instant createdAt) {
		return restore(id, mentorId, menteeId, slotId, message, status, null, createdAt);
	}

	public static MentorSession restore(String id, String mentorId, String menteeId, String slotId, String message,
			SessionStatus status, Long priceSparks, Instant createdAt) {
		return restore(id, mentorId, menteeId, slotId, message, status, priceSparks, null, null, createdAt);
	}

	public static MentorSession restore(String id, String mentorId, String menteeId, String slotId, String message,
			SessionStatus status, Long priceSparks, RoomMode roomMode, String roomUrl, Instant createdAt) {
		return new MentorSession(id, mentorId, menteeId, slotId, message, status, priceSparks, roomMode, roomUrl,
			createdAt);
	}

	public MentorSession complete() {
		return new MentorSession(id, mentorId, menteeId, slotId, message, SessionStatus.COMPLETED, priceSparks,
			roomMode, roomUrl, createdAt);
	}

	public MentorSession cancel() {
		return new MentorSession(id, mentorId, menteeId, slotId, message, SessionStatus.CANCELLED, priceSparks,
			roomMode, roomUrl, createdAt);
	}

	public boolean paid() {
		return priceSparks != null && priceSparks > 0;
	}

	public String id() { return id; }

	public String mentorId() { return mentorId; }

	public String menteeId() { return menteeId; }

	public String slotId() { return slotId; }

	public String message() { return message; }

	public SessionStatus status() { return status; }

	public Long priceSparks() { return priceSparks; }

	public RoomMode roomMode() { return roomMode; }

	public String roomUrl() { return roomUrl; }

	public Instant createdAt() { return createdAt; }

}
