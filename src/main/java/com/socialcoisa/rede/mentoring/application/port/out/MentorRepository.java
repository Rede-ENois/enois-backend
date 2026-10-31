package com.socialcoisa.rede.mentoring.application.port.out;

import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.mentoring.domain.Mentor;
import com.socialcoisa.rede.mentoring.domain.MentorAvailability;
import com.socialcoisa.rede.mentoring.domain.MentorSession;
import com.socialcoisa.rede.mentoring.domain.MentorSlot;

public interface MentorRepository {

	void save(Mentor mentor);

	Optional<Mentor> findById(String id);

	Optional<Mentor> findByUserId(String userId);

	List<Mentor> findAll(String expertise, String query, java.time.Instant before, int limit);

	void saveSlot(MentorSlot slot);

	List<MentorSlot> findSlotsByMentor(String mentorId);

	Optional<MentorSlot> findSlotById(String slotId);

	void deleteSlot(String slotId);

	void saveSession(MentorSession session);

	List<MentorSession> findSessionsByMentor(String mentorId);

	/** Sessões que EU marquei como mentorado (agenda de quem contrata). */
	List<MentorSession> findSessionsByMentee(String menteeId);

	Optional<MentorSession> findSessionById(String sessionId);

	/** Sessões pagas e ainda em aberto — a liberação automática do escrow. */
	List<MentorSession> findScheduledPaidSessions();

	void delete(String mentorId);

	List<MentorAvailability> findAvailabilitiesByMentor(String mentorId);

	void saveAvailability(MentorAvailability availability);

	void deleteAvailabilitiesByMentor(String mentorId);
}
