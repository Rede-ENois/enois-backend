package com.socialcoisa.rede.mentoring.application.port.out;

import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.mentoring.domain.Mentor;
import com.socialcoisa.rede.mentoring.domain.MentorSession;
import com.socialcoisa.rede.mentoring.domain.MentorSlot;

public interface MentorRepository {

	void save(Mentor mentor);

	Optional<Mentor> findById(String id);

	Optional<Mentor> findByUserId(String userId);

	List<Mentor> findAll(String expertise, int limit);

	void saveSlot(MentorSlot slot);

	List<MentorSlot> findSlotsByMentor(String mentorId);

	Optional<MentorSlot> findSlotById(String slotId);

	void saveSession(MentorSession session);

}
