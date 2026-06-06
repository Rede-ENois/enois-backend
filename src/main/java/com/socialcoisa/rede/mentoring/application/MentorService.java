package com.socialcoisa.rede.mentoring.application;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.mentoring.application.port.out.MentorRepository;
import com.socialcoisa.rede.mentoring.domain.Mentor;
import com.socialcoisa.rede.mentoring.domain.MentorSession;
import com.socialcoisa.rede.mentoring.domain.MentorSlot;
import com.socialcoisa.rede.shared.web.ApiException;

@Service
public class MentorService {

	private final MentorRepository repository;
	private final Clock clock;

	public MentorService(MentorRepository repository, Clock clock) {
		this.repository = repository;
		this.clock = clock;
	}

	@Transactional
	public Mentor create(String userId, List<String> expertise, String price) {
		if (repository.findByUserId(userId).isPresent()) {
			throw new ApiException(409, "ALREADY_MENTOR", "Voce ja esta cadastrado como mentor.");
		}
		String id = "mentor_" + UUID.randomUUID().toString().replace("-", "");
		Mentor mentor = Mentor.create(id, userId, expertise, price, clock.instant());
		repository.save(mentor);
		return mentor;
	}

	@Transactional
	public MentorSlot createSlot(String mentorId, String userId, java.time.Instant startsAt, java.time.Instant endsAt) {
		Mentor mentor = repository.findById(mentorId)
			.orElseThrow(() -> new ApiException(404, "MENTOR_NOT_FOUND", "Mentor nao encontrado."));
		if (!mentor.userId().equals(userId)) {
			throw new ApiException(403, "FORBIDDEN", "Voce nao e o dono deste perfil de mentor.");
		}
		String id = "slot_" + UUID.randomUUID().toString().replace("-", "");
		MentorSlot slot = MentorSlot.create(id, mentorId, startsAt, endsAt);
		repository.saveSlot(slot);
		return slot;
	}

	@Transactional
	public MentorSession scheduleSession(String mentorId, String menteeId, String slotId, String message) {
		Mentor mentor = repository.findById(mentorId)
			.orElseThrow(() -> new ApiException(404, "MENTOR_NOT_FOUND", "Mentor nao encontrado."));
		MentorSlot slot = repository.findSlotById(slotId)
			.orElseThrow(() -> new ApiException(404, "SLOT_NOT_FOUND", "Horario nao encontrado."));
		if (!slot.available()) {
			throw new ApiException(409, "SLOT_UNAVAILABLE", "Horario nao esta mais disponivel.");
		}
		repository.saveSlot(slot.book());
		String id = "mentor_session_" + UUID.randomUUID().toString().replace("-", "");
		MentorSession session = MentorSession.schedule(id, mentorId, menteeId, slotId, message, clock.instant());
		repository.saveSession(session);
		return session;
	}

	public List<Mentor> list(String expertise, java.time.Instant before, int limit) {
		return repository.findAll(expertise, before, limit);
	}

	public Mentor getById(String id) {
		return repository.findById(id)
			.orElseThrow(() -> new ApiException(404, "MENTOR_NOT_FOUND", "Mentor nao encontrado."));
	}

	public List<MentorSlot> getSlots(String mentorId) {
		return repository.findSlotsByMentor(mentorId);
	}

}
