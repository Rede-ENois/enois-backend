package com.socialcoisa.rede.mentoring.adapters.out.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataMentorSessionJpaRepository extends JpaRepository<MentorSessionJpaEntity, String> {

	List<MentorSessionJpaEntity> findByMentorIdOrderByCreatedAtDesc(String mentorId);

	List<MentorSessionJpaEntity> findByMenteeIdOrderByCreatedAtDesc(String menteeId);

	List<MentorSessionJpaEntity> findByStatus(com.socialcoisa.rede.mentoring.domain.SessionStatus status);

	/** Sessões com dinheiro ainda no escrow: a liberação automática depois da janela de contestação. */
	List<MentorSessionJpaEntity> findByPriceSparksIsNotNullAndSettledAtIsNull();

}
