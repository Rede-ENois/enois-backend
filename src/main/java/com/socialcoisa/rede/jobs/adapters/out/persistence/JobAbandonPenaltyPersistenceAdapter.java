package com.socialcoisa.rede.jobs.adapters.out.persistence;

import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import jakarta.persistence.EntityManager;

import com.socialcoisa.rede.jobs.application.port.out.JobAbandonPenaltyRepository;
import com.socialcoisa.rede.profile.application.port.out.JobAbandonPenaltyReader;

/**
 * Exclusões de vaga sem resposta (item 7 do backlog). Uma linha por vaga — a chave única
 * garante que o mesmo delete registrado por corrida não contage duas vezes.
 */
@Component
class JobAbandonPenaltyPersistenceAdapter implements JobAbandonPenaltyRepository, JobAbandonPenaltyReader {

	private final SpringDataJobAbandonPenaltyJpaRepository repository;
	private final EntityManager em;
	private final Clock clock;

	JobAbandonPenaltyPersistenceAdapter(SpringDataJobAbandonPenaltyJpaRepository repository, EntityManager em,
			Clock clock) {
		this.repository = repository;
		this.em = em;
		this.clock = clock;
	}

	@Override
	public boolean record(String jobId, String userId, int unanswered) {
		if (repository.existsById(jobId)) {
			return false;
		}
		var entity = new JobAbandonPenaltyJpaEntity();
		entity.jobId = jobId;
		entity.userId = userId;
		entity.unanswered = unanswered;
		entity.recordedAt = clock.instant();
		repository.save(entity);
		return true;
	}

	@Override
	public int countByUser(String userId) {
		return repository.countByUserId(userId);
	}

	@Override
	public Map<String, Integer> countsByUsers(Collection<String> userIds) {
		if (userIds.isEmpty()) {
			return Map.of();
		}
		@SuppressWarnings("unchecked")
		List<Object[]> rows = em.createNativeQuery(
				"select user_id, count(*) from job_abandon_penalties where user_id in (:ids) group by user_id")
			.setParameter("ids", userIds)
			.getResultList();
		var counts = new java.util.HashMap<String, Integer>();
		for (Object[] row : rows) {
			counts.put((String) row[0], ((Number) row[1]).intValue());
		}
		return counts;
	}
}
