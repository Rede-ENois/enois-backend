package com.socialcoisa.rede.jobs.adapters.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.jobs.application.port.out.ExternalJobRepository;
import com.socialcoisa.rede.jobs.domain.ExternalJob;

@Repository
class ExternalJobPersistenceAdapter implements ExternalJobRepository {

	private final SpringDataExternalJobJpaRepository repository;
	private final jakarta.persistence.EntityManager entityManager;

	ExternalJobPersistenceAdapter(SpringDataExternalJobJpaRepository repository,
			jakarta.persistence.EntityManager entityManager) {
		this.repository = repository;
		this.entityManager = entityManager;
	}

	@Override
	public Optional<ExternalJob> findById(String id) {
		return repository.findById(id).map(ExternalJobJpaEntity::toDomain);
	}

	@Override
	public Optional<ExternalJob> findBySource(String platform, String externalId) {
		return repository.findByPlatformAndExternalId(platform, externalId).map(ExternalJobJpaEntity::toDomain);
	}

	@Override
	public void save(ExternalJob job) {
		repository.save(ExternalJobJpaEntity.fromDomain(job));
	}

	@Override
	public Optional<ExternalJob> findByCanonicalUrl(String canonicalUrl) {
		return repository.findFirstByCanonicalUrl(canonicalUrl).map(ExternalJobJpaEntity::toDomain);
	}

	@Override
	@org.springframework.cache.annotation.Cacheable("externalJobs")
	public List<ExternalJob> search(String query, int limit) {
		// `%` e `_` do texto viram literais: sem isso, buscar "_" casaria com tudo.
		String escaped = com.socialcoisa.rede.shared.web.LikePattern.escape(query.toLowerCase(java.util.Locale.ROOT));
		return repository.search("%" + escaped + "%", PageRequest.of(0, Math.max(1, limit))).stream()
			.map(ExternalJobJpaEntity::toDomain)
			.toList();
	}

	@Override
	@org.springframework.cache.annotation.Cacheable("externalJobs")
	public List<ExternalJob> findByPlatform(String platform, String query, int limit) {
		PageRequest page = PageRequest.of(0, Math.max(1, limit));
		if (query == null || query.isBlank()) {
			return repository.findByPlatformOrderByLastCheckedAtDesc(platform, page).stream()
				.map(ExternalJobJpaEntity::toDomain).toList();
		}
		String escaped = com.socialcoisa.rede.shared.web.LikePattern.escape(query.toLowerCase(java.util.Locale.ROOT));
		return repository.searchByPlatform(platform, "%" + escaped + "%", page).stream()
			.map(ExternalJobJpaEntity::toDomain).toList();
	}

	@Override
	@org.springframework.cache.annotation.Cacheable(value = "externalJobs",
		key = "'excluding:' + #platform + ':' + #limit")
	public List<ExternalJob> findRecentExcludingPlatform(String platform, int limit) {
		return repository.findByPlatformNotOrderByLastCheckedAtDesc(platform,
			PageRequest.of(0, Math.max(1, limit))).stream().map(ExternalJobJpaEntity::toDomain).toList();
	}

	@Override
	@org.springframework.cache.annotation.Cacheable(value = "externalJobs",
		key = "'excluding:' + #platform + ':' + (#query ?: '') + ':' + #limit")
	public List<ExternalJob> searchExcludingPlatform(String platform, String query, int limit) {
		String escaped = com.socialcoisa.rede.shared.web.LikePattern.escape(query.toLowerCase(java.util.Locale.ROOT));
		return repository.searchExcludingPlatform(platform, "%" + escaped + "%",
			PageRequest.of(0, Math.max(1, limit))).stream().map(ExternalJobJpaEntity::toDomain).toList();
	}

	@Override
	@org.springframework.cache.annotation.Cacheable("externalJobs")
	public List<String> distinctPlatforms() {
		return repository.distinctPlatforms();
	}

	@Override
	@org.springframework.cache.annotation.Cacheable("externalJobs")
	public List<ExternalJob> findByIds(List<String> ids) {
		if (ids == null || ids.isEmpty()) {
			return List.of();
		}
		return repository.findByIdIn(ids).stream().map(ExternalJobJpaEntity::toDomain).toList();
	}

	@Override
	@org.springframework.cache.annotation.Cacheable("externalJobs")
	public List<ExternalJob> findRecent(int limit) {
		return repository.findAllByOrderByLastCheckedAtDesc(PageRequest.of(0, Math.max(1, limit))).stream()
			.map(ExternalJobJpaEntity::toDomain)
			.toList();
	}

	/**
	 * Busca com filtros combinados das externas. Criteria em vez de uma query por combinação —
	 * os filtros são todos opcionais, e só o que veio preenchido entra no WHERE.
	 * Sem cache: a combinação de parâmetros é grande demais para valer a chave.
	 */
	@Override
	public List<ExternalJob> search(com.socialcoisa.rede.jobs.application.port.out.ExternalJobSearch search) {
		jakarta.persistence.criteria.CriteriaBuilder cb = entityManager.getCriteriaBuilder();
		jakarta.persistence.criteria.CriteriaQuery<ExternalJobJpaEntity> criteria =
			cb.createQuery(ExternalJobJpaEntity.class);
		jakarta.persistence.criteria.Root<ExternalJobJpaEntity> root = criteria.from(ExternalJobJpaEntity.class);

		java.util.List<jakarta.persistence.criteria.Predicate> where = new java.util.ArrayList<>();
		java.time.Instant now = java.time.Instant.now();

		// Só vagas ativas: expirada ou removida nunca aparece como resultado.
		where.add(cb.isNull(root.get("removedAt")));
		where.add(cb.or(cb.isNull(root.get("expiresAt")), cb.greaterThan(root.get("expiresAt"), now)));

		if (search.query() != null && !search.query().isBlank()) {
			String escaped = com.socialcoisa.rede.shared.web.LikePattern.escape(
				search.query().toLowerCase(java.util.Locale.ROOT));
			String pattern = "%" + escaped + "%";
			where.add(cb.or(like(cb, root.get("title"), pattern), like(cb, root.get("company"), pattern),
				like(cb, root.get("location"), pattern)));
		}
		if (search.platform() != null) {
			where.add(cb.equal(root.get("platform"), search.platform()));
		}
		if (search.excludedPlatform() != null) {
			where.add(cb.notEqual(root.get("platform"), search.excludedPlatform()));
		}
		if (search.level() != null) {
			where.add(cb.equal(root.get("level"), search.level()));
		}
		if (search.workModel() != null) {
			where.add(cb.equal(root.get("workModel"), search.workModel()));
		}
		if (search.employmentType() != null) {
			where.add(cb.equal(root.get("employmentType"), search.employmentType()));
		}
		if (search.city() != null) {
			where.add(cb.equal(cb.lower(root.get("city")), search.city().toLowerCase(java.util.Locale.ROOT)));
		}
		if (search.state() != null) {
			where.add(cb.equal(cb.lower(root.get("state")), search.state().toLowerCase(java.util.Locale.ROOT)));
		}
		if (search.country() != null) {
			where.add(cb.equal(cb.lower(root.get("country")), search.country().toLowerCase(java.util.Locale.ROOT)));
		}
		if (search.salaryMin() != null) {
			// Casa se qualquer extremo da faixa da vaga atinge o piso pedido; sem salário
			// informado, a vaga não entra — não inferir faixa.
			where.add(cb.and(root.get("salaryMaxAmount").isNotNull(),
				cb.greaterThanOrEqualTo(root.get("salaryMaxAmount"), search.salaryMin())));
		}
		if (Boolean.TRUE.equals(search.remoteOnly())) {
			where.add(cb.equal(root.get("workModel"), "REMOTE"));
		}
		if (search.publishedAfter() != null) {
			where.add(cb.greaterThan(root.get("publishedAt"), search.publishedAfter()));
		}
		if (search.before() != null && search.cursorable()) {
			where.add(cb.lessThan(root.get("publishedAt"), search.before()));
		}

		criteria.where(where.toArray(new jakarta.persistence.criteria.Predicate[0]));

		String sort = search.sort() == null
			? com.socialcoisa.rede.jobs.application.port.out.ExternalJobSearch.SORT_RECENT
			: search.sort();
		if (com.socialcoisa.rede.jobs.application.port.out.ExternalJobSearch.SORT_SALARY.equals(sort)) {
			criteria.orderBy(cb.desc(root.get("salaryMaxAmount")), cb.desc(root.get("publishedAt")));
		} else if (com.socialcoisa.rede.jobs.application.port.out.ExternalJobSearch.SORT_RELEVANCE.equals(sort)
				&& search.query() != null && !search.query().isBlank()) {
			// Título que COMEÇA com o termo vem antes: relevância mínima e honesta.
			criteria.orderBy(
				cb.desc(cb.<Integer>selectCase()
					.when(cb.like(cb.lower(root.get("title")),
						search.query().toLowerCase(java.util.Locale.ROOT) + "%"), 1)
					.otherwise(0)),
				cb.desc(root.get("publishedAt")));
		} else {
			criteria.orderBy(cb.desc(root.get("publishedAt")), cb.desc(root.get("importedAt")));
		}

		// getResultList (e não stream): sem transação ativa o stream fecha o ResultSet antes da hora.
		return entityManager.createQuery(criteria).setMaxResults(Math.max(1, search.limit())).getResultList()
			.stream().map(ExternalJobJpaEntity::toDomain).toList();
	}

	private static jakarta.persistence.criteria.Predicate like(jakarta.persistence.criteria.CriteriaBuilder cb,
			jakarta.persistence.criteria.Expression<String> path, String pattern) {
		return cb.like(cb.lower(path), pattern, '!');
	}

	@Override
	public List<ExternalJob> findStaleByPlatform(String platform, java.time.Instant seenBefore, int limit) {
		return repository.findStaleNotRemoved(platform, seenBefore, PageRequest.of(0, Math.max(1, limit)))
			.stream().map(ExternalJobJpaEntity::toDomain).toList();
	}

	/** Sem cache: e a lista "minhas vagas externas", pessoal e curta — frescor importa mais. */
	@Override
	public List<ExternalJob> findBySubmitter(String userId) {
		return repository.findBySubmittedByOrderByImportedAtDesc(userId).stream()
			.map(ExternalJobJpaEntity::toDomain)
			.toList();
	}

	@Override
	public void delete(String id) {
		repository.deleteById(id);
	}
}
