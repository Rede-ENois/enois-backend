package com.socialcoisa.rede.profile.adapters.out.persistence;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.profile.application.port.out.TalentCandidateReader;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.shared.search.BooleanQuery;
import com.socialcoisa.rede.shared.web.LikePattern;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

/**
 * A leitura do banco de talentos: busca e extrato das contas liberadas.
 *
 * <p>A busca usa a mesma sintaxe booleana das vagas e a mesma tradução para Criteria — o que muda é
 * o ponto de partida: aqui o conjunto é sempre os ids que o módulo de vagas trouxe dos
 * consentimentos ativos. Filtrar por esse conjunto é o que faz o consentimento valer para qualquer
 * critério, e não o contrário.
 */
@Repository
class TalentCandidatePersistenceAdapter implements TalentCandidateReader {

	private static final char LIKE_ESCAPE = LikePattern.ESCAPE;

	/** Campos pesquisáveis: os mesmos que a linha do banco de talentos mostra. */
	private static final List<String> TEXT_FIELDS = List.of("name", "username", "headline", "location", "company");

	private final EntityManager em;
	private final SpringDataUserProfileJpaRepository profiles;

	TalentCandidatePersistenceAdapter(EntityManager em, SpringDataUserProfileJpaRepository profiles) {
		this.em = em;
		this.profiles = profiles;
	}

	@Override
	public List<String> searchAmong(Collection<String> candidateIds, String query, int limit) {
		if (candidateIds.isEmpty()) {
			return List.of();
		}
		CriteriaBuilder cb = em.getCriteriaBuilder();
		CriteriaQuery<String> criteria = cb.createQuery(String.class);
		Root<UserProfileJpaEntity> root = criteria.from(UserProfileJpaEntity.class);

		List<Predicate> filters = new ArrayList<>();
		filters.add(root.get("id").in(candidateIds));
		// Conta removida nunca entra, por mais que o id esteja na lista de consentimentos: o
		// consentimento foi da pessoa, e a pessoa não está mais ali.
		filters.add(cb.not(cb.like(root.get("username"), UserProfile.REMOVED_USERNAME_PREFIX + "%")));
		BooleanQuery booleanQuery = BooleanQuery.parse(query);
		if (!booleanQuery.isEmpty()) {
			filters.add(nodePredicate(cb, root, booleanQuery.root()));
		}
		criteria.select(root.get("id"))
			.where(cb.and(filters.toArray(Predicate[]::new)))
			.orderBy(cb.desc(root.get("reputation")));

		return em.createQuery(criteria).setMaxResults(Math.max(1, limit)).getResultList();
	}

	@Override
	public List<TalentCandidate> byIds(Collection<String> candidateIds) {
		if (candidateIds.isEmpty()) {
			return List.of();
		}
		Map<String, Integer> experiences = experienceCounts(candidateIds);
		return profiles.findAllById(candidateIds)
			.stream()
			.map(UserProfileJpaEntity::toDomain)
			.map(profile -> new TalentCandidate(profile.id(), profile.username(), profile.displayName(),
				profile.headline(), profile.avatar(), profile.location(), profile.resumeUrl(),
				profile.resumeName(), experiences.getOrDefault(profile.id(), 0)))
			.toList();
	}

	private Predicate nodePredicate(CriteriaBuilder cb, Root<UserProfileJpaEntity> root, BooleanQuery.Node node) {
		return switch (node) {
			case BooleanQuery.MatchAll ignored -> cb.conjunction();
			case BooleanQuery.Term term -> textMatch(cb, root, term.text());
			case BooleanQuery.Phrase phrase -> textMatch(cb, root, phrase.text());
			case BooleanQuery.Not not -> cb.not(nodePredicate(cb, root, not.child()));
			case BooleanQuery.And and -> cb.and(and.children().stream()
				.map(child -> nodePredicate(cb, root, child))
				.toArray(Predicate[]::new));
			case BooleanQuery.Or or -> cb.or(or.children().stream()
				.map(child -> nodePredicate(cb, root, child))
				.toArray(Predicate[]::new));
		};
	}

	/**
	 * Um termo casa quando aparece em qualquer campo que a linha mostra — nome, @, título,
	 * local ou empresa. O {@code coalesce} evita que um campo nulo torne o LIKE desconhecido (e,
	 * dentro de um NOT, derrubasse a conta por engano).
	 */
	private Predicate textMatch(CriteriaBuilder cb, Root<UserProfileJpaEntity> root, String rawText) {
		String pattern = "%" + LikePattern.escape(BooleanQuery.normalize(rawText)) + "%";
		List<Predicate> perField = new ArrayList<>();
		for (String field : TEXT_FIELDS) {
			perField.add(cb.like(cb.lower(cb.coalesce(root.get(field), "")), pattern, LIKE_ESCAPE));
		}
		perField.add(skillMatch(cb, root, pattern));
		return cb.or(perField.toArray(Predicate[]::new));
	}

	/** Habilidades ficam em tabela filha: casa por substring sem duplicar a conta. */
	private Predicate skillMatch(CriteriaBuilder cb, Root<UserProfileJpaEntity> root, String pattern) {
		Subquery<Integer> subquery = cb.createQuery().subquery(Integer.class);
		Root<UserProfileJpaEntity> subRoot = subquery.from(UserProfileJpaEntity.class);
		Join<UserProfileJpaEntity, String> skill = subRoot.join("skills");
		subquery.select(cb.literal(1))
			.where(cb.equal(subRoot.get("id"), root.get("id")), cb.like(cb.lower(skill), pattern, LIKE_ESCAPE));
		return cb.exists(subquery);
	}

	/** Quantas experiências cada conta tem, em uma consulta — a linha mostra o número. */
	private Map<String, Integer> experienceCounts(Collection<String> candidateIds) {
		@SuppressWarnings("unchecked")
		List<Object[]> rows = em.createNativeQuery(
				"select user_id, count(*) from user_experiences where user_id in (:ids) group by user_id")
			.setParameter("ids", candidateIds)
			.getResultList();
		Map<String, Integer> counts = new HashMap<>();
		for (Object[] row : rows) {
			counts.put((String) row[0], ((Number) row[1]).intValue());
		}
		return counts;
	}
}
