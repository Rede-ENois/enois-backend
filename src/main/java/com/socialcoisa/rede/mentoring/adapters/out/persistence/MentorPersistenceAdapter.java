package com.socialcoisa.rede.mentoring.adapters.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.mentoring.application.port.out.MentorRepository;
import com.socialcoisa.rede.mentoring.domain.Mentor;
import com.socialcoisa.rede.mentoring.domain.MentorSession;
import com.socialcoisa.rede.mentoring.domain.MentorSlot;

@Repository
class MentorPersistenceAdapter implements MentorRepository {

	private final SpringDataMentorJpaRepository springData;
	private final SpringDataMentorSlotJpaRepository slotRepo;
	private final SpringDataMentorSessionJpaRepository sessionRepo;

	MentorPersistenceAdapter(SpringDataMentorJpaRepository springData,
			SpringDataMentorSlotJpaRepository slotRepo,
			SpringDataMentorSessionJpaRepository sessionRepo) {
		this.springData = springData;
		this.slotRepo = slotRepo;
		this.sessionRepo = sessionRepo;
	}

	@Override
	public void save(Mentor mentor) {
		springData.save(MentorJpaEntity.fromDomain(mentor));
	}

	@Override
	public Optional<Mentor> findById(String id) {
		return springData.findById(id).map(MentorJpaEntity::toDomain);
	}

	@Override
	public Optional<Mentor> findByUserId(String userId) {
		return springData.findByUserId(userId).map(MentorJpaEntity::toDomain);
	}

	@Override
	public List<Mentor> findAll(String expertise, Instant before, int limit) {
		PageRequest page = PageRequest.of(0, limit);
		List<MentorJpaEntity> entities;
		if (StringUtils.hasText(expertise)) {
			entities = springData.findByExpertise(expertise, before, page);
		} else {
			entities = springData.findAllBefore(before, page);
		}
		return entities.stream().map(MentorJpaEntity::toDomain).toList();
	}

	@Override
	public void saveSlot(MentorSlot slot) {
		slotRepo.save(MentorSlotJpaEntity.fromDomain(slot));
	}

	@Override
	public List<MentorSlot> findSlotsByMentor(String mentorId) {
		return slotRepo.findByMentorIdOrderByStartsAtAsc(mentorId).stream()
			.map(MentorSlotJpaEntity::toDomain)
			.toList();
	}

	@Override
	public Optional<MentorSlot> findSlotById(String slotId) {
		return slotRepo.findById(slotId).map(MentorSlotJpaEntity::toDomain);
	}

	@Override
	public void saveSession(MentorSession session) {
		sessionRepo.save(MentorSessionJpaEntity.fromDomain(session));
	}

	@Override
	public void delete(String mentorId) {
		springData.deleteById(mentorId);
	}
}
