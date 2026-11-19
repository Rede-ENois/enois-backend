package com.socialcoisa.rede.jobs.adapters.out.persistence;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.socialcoisa.rede.jobs.application.port.out.JobApplicationDraftRepository;
import com.socialcoisa.rede.jobs.domain.JobApplicationDraft;

import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

/**
 * Rascunho da candidatura. As respostas moram em JSON numa linha única por (job_id, user_id):
 * é um buffer de digitação, não uma coleção a consultar — a leitura é sempre "o rascunho destes
 * dois ids", nunca uma lista.
 */
@Component
class JobApplicationDraftPersistenceAdapter implements JobApplicationDraftRepository {

	private final SpringDataJobApplicationDraftJpaRepository jpa;
	private final ObjectMapper objectMapper;

	JobApplicationDraftPersistenceAdapter(SpringDataJobApplicationDraftJpaRepository jpa,
			ObjectMapper objectMapper) {
		this.jpa = jpa;
		this.objectMapper = objectMapper;
	}

	@Override
	public JobApplicationDraft save(JobApplicationDraft draft) {
		try {
			JobApplicationDraftJpaEntity entity = jpa.findByJobIdAndUserId(draft.jobId(), draft.userId())
				.orElseGet(JobApplicationDraftJpaEntity::new);
			entity.setJobId(draft.jobId());
			entity.setUserId(draft.userId());
			entity.setAnswersJson(objectMapper.writeValueAsString(draft.answers()));
			entity.setUpdatedAt(draft.updatedAt());
			jpa.save(entity);
			return draft;
		}
		catch (Exception exception) {
			throw new com.socialcoisa.rede.shared.web.ApiException(500, "DRAFT_SAVE_FAILURE",
				"Não foi possível salvar o rascunho agora.");
		}
	}

	@Override
	public Optional<JobApplicationDraft> find(String jobId, String userId) {
		return jpa.findByJobIdAndUserId(jobId, userId).flatMap(entity -> Optional.ofNullable(toDomain(entity)));
	}

	@Override
	public void delete(String jobId, String userId) {
		jpa.deleteByJobIdAndUserId(jobId, userId);
	}

	private JobApplicationDraft toDomain(JobApplicationDraftJpaEntity entity) {
		try {
			Map<String, String> answers = objectMapper.readValue(entity.getAnswersJson(),
				new tools.jackson.core.type.TypeReference<LinkedHashMap<String, String>>() {
				});
			return JobApplicationDraft.create(entity.getJobId(), entity.getUserId(), answers, entity.getUpdatedAt());
		}
		catch (Exception exception) {
			// Rascunho ilegível não vira erro de leitura: é como não ter rascunho.
			return null;
		}
	}
}
