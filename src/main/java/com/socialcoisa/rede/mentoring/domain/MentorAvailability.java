package com.socialcoisa.rede.mentoring.domain;

/** Regra de disponibilidade semanal do mentor: dia da semana (1=segunda..7=domingo) e horário "HH:mm". */
public record MentorAvailability(String id, String mentorId, int dayOfWeek, String timeOfDay) {

	public MentorAvailability {
		if (dayOfWeek < 1 || dayOfWeek > 7) {
			throw new IllegalArgumentException("dayOfWeek fora do intervalo 1..7");
		}
	}

	public static MentorAvailability create(String id, String mentorId, int dayOfWeek, String timeOfDay) {
		return new MentorAvailability(id, mentorId, dayOfWeek, timeOfDay);
	}

}
