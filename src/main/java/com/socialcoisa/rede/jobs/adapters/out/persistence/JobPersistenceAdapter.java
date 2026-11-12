package com.socialcoisa.rede.jobs.adapters.out.persistence;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.jobs.application.port.out.JobRepository;
import com.socialcoisa.rede.jobs.domain.Job;
import com.socialcoisa.rede.shared.search.BooleanQuery;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

@Repository
class JobPersistenceAdapter implements JobRepository {

	private static final char LIKE_ESCAPE = com.socialcoisa.rede.shared.web.LikePattern.ESCAPE;

	private final SpringDataJobJpaRepository springData;
	private final SpringDataJobApplicationJpaRepository applicationRepo;
	private final SpringDataJobSaveJpaRepository saveRepo;
	private final EntityManager entityManager;
	private final Clock clock;

	JobPersistenceAdapter(SpringDataJobJpaRepository springData,
			SpringDataJobApplicationJpaRepository applicationRepo,
			SpringDataJobSaveJpaRepository saveRepo, EntityManager entityManager, Clock clock) {
		this.springData = springData;
		this.applicationRepo = applicationRepo;
		this.saveRepo = saveRepo;
		this.entityManager = entityManager;
		this.clock = clock;
	}

	@Override
	public void save(Job job) {
		springData.save(JobJpaEntity.fromDomain(job));
	}

	@Override
	public Optional<Job> findById(String id) {
		return springData.findById(id).map(JobJpaEntity::toDomain);
	}

	@Override
	public Optional<Job> findByIdForUpdate(String id) {
		return springData.findByIdForUpdate(id).map(JobJpaEntity::toDomain);
	}

	/**
	 * Lista as vagas aplicando TODOS os filtros informados.
	 *
	 * Antes cada filtro era um `else if`, entao buscar por texto descartava o nivel/tipo/local
	 * escolhidos (e vice-versa). Agora os filtros sao combinados, e a busca textual aceita a
	 * sintaxe booleana de {@link BooleanQuery} (AND/OR/NOT, frases, parenteses, {@code -termo}).
	 */
	@Override
	public List<Job> findAll(String query, String type, String level, String location, List<String> skills,
			Instant before, int limit) {
		CriteriaBuilder cb = entityManager.getCriteriaBuilder();
		CriteriaQuery<JobJpaEntity> criteria = cb.createQuery(JobJpaEntity.class);
		Root<JobJpaEntity> root = criteria.from(JobJpaEntity.class);

		List<Predicate> filters = new ArrayList<>();
		if (before != null) {
			filters.add(cb.lessThan(root.get("postedAt"), before));
		}
		if (StringUtils.hasText(type)) {
			filters.add(cb.equal(root.get("type"), type.trim()));
		}
		if (StringUtils.hasText(level)) {
			filters.add(cb.equal(root.get("level"), level.trim()));
		}
		if (StringUtils.hasText(location)) {
			filters.add(cb.equal(root.get("location"), location.trim()));
		}
		if (skills != null) {
			for (String skill : skills.stream().filter(StringUtils::hasText).map(String::trim).toList()) {
				filters.add(textMatch(criteria, cb, root, skill));
			}
		}

		BooleanQuery booleanQuery = BooleanQuery.parse(query);
		if (!booleanQuery.isEmpty()) {
			filters.add(nodePredicate(criteria, cb, root, booleanQuery.root()));
		}

		criteria.select(root)
			.where(filters.isEmpty() ? cb.conjunction() : cb.and(filters.toArray(Predicate[]::new)))
			.orderBy(cb.desc(root.get("postedAt")));

		return entityManager.createQuery(criteria)
			.setMaxResults(limit)
			.getResultList()
			.stream()
			.map(JobJpaEntity::toDomain)
			.toList();
	}

	private Predicate nodePredicate(CriteriaQuery<JobJpaEntity> criteria, CriteriaBuilder cb, Root<JobJpaEntity> root,
			BooleanQuery.Node node) {
		return switch (node) {
			case BooleanQuery.MatchAll ignored -> cb.conjunction();
			case BooleanQuery.Term term -> textMatch(criteria, cb, root, term.text());
			case BooleanQuery.Phrase phrase -> textMatch(criteria, cb, root, phrase.text());
			case BooleanQuery.Not not -> cb.not(nodePredicate(criteria, cb, root, not.child()));
			case BooleanQuery.And and -> cb.and(and.children().stream()
				.map(child -> nodePredicate(criteria, cb, root, child))
				.toArray(Predicate[]::new));
			case BooleanQuery.Or or -> cb.or(or.children().stream()
				.map(child -> nodePredicate(criteria, cb, root, child))
				.toArray(Predicate[]::new));
		};
	}

	/**
	 * Um termo casa quando aparece em qualquer campo pesquisavel da vaga (titulo, empresa, local,
	 * tipo, nivel ou habilidade) — o mesmo texto que a pessoa leria no card.
	 *
	 * O `coalesce` evita que um campo nulo torne o LIKE desconhecido (e, dentro de um NOT, sumisse
	 * com a vaga por engano).
	 */
	private Predicate textMatch(CriteriaQuery<JobJpaEntity> criteria, CriteriaBuilder cb, Root<JobJpaEntity> root,
			String rawText) {
		String pattern = "%" + com.socialcoisa.rede.shared.web.LikePattern.escape(BooleanQuery.normalize(rawText)) + "%";
		List<Predicate> perField = new ArrayList<>();
		for (String field : List.of("title", "company", "location", "type", "level")) {
			perField.add(cb.like(cb.lower(cb.coalesce(root.get(field), "")), pattern, LIKE_ESCAPE));
		}
		perField.add(skillMatch(criteria, cb, root, pattern));
		return cb.or(perField.toArray(Predicate[]::new));
	}

	/** Habilidades ficam em tabela filha: casa por substring sem duplicar a vaga. */
	private Predicate skillMatch(CriteriaQuery<JobJpaEntity> criteria, CriteriaBuilder cb, Root<JobJpaEntity> root,
			String pattern) {
		Subquery<Integer> subquery = criteria.subquery(Integer.class);
		Root<JobJpaEntity> subRoot = subquery.from(JobJpaEntity.class);
		Join<JobJpaEntity, String> skill = subRoot.join("skills");
		subquery.select(cb.literal(1))
			.where(cb.equal(subRoot.get("id"), root.get("id")), cb.like(cb.lower(skill), pattern, LIKE_ESCAPE));
		return cb.exists(subquery);
	}

	@Override
	public String saveApplication(com.socialcoisa.rede.jobs.domain.JobApplication application) {
		JobApplicationJpaEntity saved = applicationRepo.save(JobApplicationJpaEntity.fromDomain(application));
		return saved.numericId() == null ? application.id() : String.valueOf(saved.numericId());
	}

	@Override
	public List<com.socialcoisa.rede.jobs.domain.JobApplication> findApplicationsByJob(String jobId, int limit) {
		return applicationRepo.findByJobIdOrderByAppliedAtDesc(jobId, org.springframework.data.domain.PageRequest.of(0, limit))
			.stream()
			.map(JobApplicationJpaEntity::toDomain)
			.toList();
	}

	@Override
	public List<com.socialcoisa.rede.jobs.domain.JobApplication> findApplicationsByUser(String userId, int limit) {
		return applicationRepo.findByUserIdOrderByAppliedAtDesc(userId, PageRequest.of(0, limit)).stream()
			.map(JobApplicationJpaEntity::toDomain)
			.toList();
	}

	@Override
	public Optional<com.socialcoisa.rede.jobs.domain.JobApplication> findApplicationById(String applicationId) {
		try {
			return applicationRepo.findById(Long.valueOf(applicationId)).map(JobApplicationJpaEntity::toDomain);
		}
		catch (NumberFormatException exception) {
			// Id que nao e numerico nunca existiu: o contrato da API e 404, nao erro interno.
			return Optional.empty();
		}
	}

	@Override
	public void updateApplicationStatus(String applicationId,
			com.socialcoisa.rede.jobs.domain.JobApplicationStatus status, String feedback,
			java.time.Instant reviewedAt) {
		applicationRepo.findById(Long.valueOf(applicationId)).ifPresent(entity -> {
			entity.changeStatus(status, feedback, reviewedAt);
			applicationRepo.save(entity);
		});
	}

	@Override
	public void removeApplication(String jobId, String userId) {
		applicationRepo.deleteByJobIdAndUserId(jobId, userId);
	}

	@Override
	public boolean hasApplied(String jobId, String userId) {
		return applicationRepo.existsByJobIdAndUserId(jobId, userId);
	}

	@Override
	public java.util.List<Job> findByIds(java.util.Collection<String> ids) {
		if (ids == null || ids.isEmpty()) {
			return java.util.List.of();
		}
		return springData.findByIdIn(ids).stream().map(JobJpaEntity::toDomain).toList();
	}

	@Override
	public java.util.List<String> findSavedJobIds(String userId) {
		return saveRepo.findByUserIdOrderBySavedAtDesc(userId).stream()
			.map(JobSaveJpaEntity::jobId).toList();
	}

	@Override
	public void saveBookmark(String jobId, String userId) {
		saveRepo.save(JobSaveJpaEntity.create(jobId, userId, clock.instant()));
	}

	@Override
	public void removeBookmark(String jobId, String userId) {
		saveRepo.deleteByJobIdAndUserId(jobId, userId);
	}

	@Override
	public boolean hasBookmarked(String jobId, String userId) {
		return saveRepo.existsByJobIdAndUserId(jobId, userId);
	}

	@Override
	public int countUnansweredApplications(String jobId) {
		return applicationRepo.countByJobIdAndStatus(jobId,
			com.socialcoisa.rede.jobs.domain.JobApplicationStatus.APPLIED);
	}

	@Override
	public void deleteById(String id) {
		applicationRepo.deleteByJobId(id);
		saveRepo.deleteByJobId(id);
		springData.deleteById(id);
	}

	@Override
	public List<Job> findByPostedBy(String userId, Instant before, int limit) {
		return springData.findByPostedByAndPostedAtBeforeOrderByPostedAtDesc(userId, before, PageRequest.of(0, limit))
			.stream().map(JobJpaEntity::toDomain).toList();
	}

	@Override
	public List<Job> findAppliedByUser(String userId, Instant before, int limit) {
		List<String> jobIds = applicationRepo.findJobIdsByUserId(userId, PageRequest.of(0, limit));
		if (jobIds.isEmpty()) {
			return List.of();
		}
		java.util.Map<String, Job> byId = springData.findByIdIn(jobIds).stream()
			.map(JobJpaEntity::toDomain)
			.collect(java.util.stream.Collectors.toMap(Job::id, job -> job));
		// Mantem a ordem das candidaturas (mais recentes primeiro), aplicando o cursor.
		return jobIds.stream()
			.map(byId::get)
			.filter(java.util.Objects::nonNull)
			.filter(job -> before == null || !job.postedAt().isAfter(before))
			.toList();
	}

	@Override
	public int countApplications(String jobId) {
		return applicationRepo.countByJobId(jobId);
	}

	@Override
	public java.util.Set<String> findAppliedJobIds(String userId, java.util.Collection<String> jobIds) {
		if (jobIds == null || jobIds.isEmpty()) {
			return java.util.Set.of();
		}
		return java.util.Set.copyOf(applicationRepo.findJobIdsByUserIdAndJobIdIn(userId, jobIds));
	}

	@Override
	public java.util.Set<String> findSavedJobIds(String userId, java.util.Collection<String> jobIds) {
		if (jobIds == null || jobIds.isEmpty()) {
			return java.util.Set.of();
		}
		return java.util.Set.copyOf(saveRepo.findJobIdsByUserIdAndJobIdIn(userId, jobIds));
	}

	@Override
	public java.util.Map<String, Integer> countApplicationsByJobIds(java.util.Collection<String> jobIds) {
		if (jobIds == null || jobIds.isEmpty()) {
			return java.util.Map.of();
		}
		return applicationRepo.countByJobIdGrouped(jobIds).stream()
			.collect(java.util.stream.Collectors.toMap(row -> (String) row[0], row -> ((Number) row[1]).intValue()));
	}

	@Override
	public java.util.Map<String, Integer> countApplicationsByJobIdsAndStatus(java.util.Collection<String> jobIds,
			com.socialcoisa.rede.jobs.domain.JobApplicationStatus status) {
		if (jobIds == null || jobIds.isEmpty()) {
			return java.util.Map.of();
		}
		return applicationRepo.countByJobIdAndStatusGrouped(jobIds, status).stream()
			.collect(java.util.stream.Collectors.toMap(row -> (String) row[0], row -> ((Number) row[1]).intValue()));
	}

}
