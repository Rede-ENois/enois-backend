package com.socialcoisa.rede.profile.adapters.out.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;

import jakarta.persistence.EntityManager;

@Repository
class UserProfilePersistenceAdapter implements UserProfileRepository {

	private final SpringDataUserProfileJpaRepository repository;
	private final EntityManager em;

	UserProfilePersistenceAdapter(SpringDataUserProfileJpaRepository repository, EntityManager em) {
		this.repository = repository;
		this.em = em;
	}

	@Override
	public UserProfile save(UserProfile profile) {
		return repository.save(UserProfileJpaEntity.fromDomain(profile)).toDomain();
	}

	@Override
	public void updateNameStyle(String userId, String theme, String font, String color, java.time.Instant at) {
		repository.updateNameStyle(userId, theme, font, color, at);
	}

	@Override
	public Optional<UserProfile> findById(String id) {
		return repository.findById(id).map(UserProfileJpaEntity::toDomain);
	}

	@Override
	public List<UserProfile> findAllByIds(Collection<String> ids) {
		return repository.findAllById(ids).stream()
			.map(UserProfileJpaEntity::toDomain)
			.toList();
	}

	@Override
	public List<com.socialcoisa.rede.profile.application.port.out.AccountIdentity> identitiesOf(
			Collection<String> ids) {
		return repository.identitiesOf(ids);
	}

	@Override
	public List<com.socialcoisa.rede.profile.application.port.out.SellerIdentity> sellersOf(
			Collection<String> ids) {
		if (ids.isEmpty()) {
			return List.of();
		}
		return repository.sellersOf(ids);
	}

	@Override
	public List<com.socialcoisa.rede.profile.application.port.out.ReputationSeed> seedsOf(
			Collection<String> ids) {
		if (ids.isEmpty()) {
			return List.of();
		}
		@SuppressWarnings("unchecked")
		List<Object[]> rows = em.createNativeQuery("""
				select id, created_at,
					case when email_verified_at is not null or totp_enabled_at is not null
						or email_otp_enabled_at is not null or federated_second_factor_at is not null
					then 1 else 0 end
				from users where id in (:ids)""")
			.setParameter("ids", ids)
			.getResultList();
		return rows.stream()
			.map(row -> new com.socialcoisa.rede.profile.application.port.out.ReputationSeed(
				(String) row[0], instantOf(row[1]), ((Number) row[2]).intValue() == 1))
			.toList();
	}

	/** O driver devolve `timestamp` como Timestamp (MariaDB) ou LocalDateTime (H2); aceita os dois. */
	private static java.time.Instant instantOf(Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof java.time.Instant instant) {
			return instant;
		}
		if (value instanceof java.sql.Timestamp timestamp) {
			return timestamp.toInstant();
		}
		if (value instanceof java.time.LocalDateTime local) {
			return local.toInstant(java.time.ZoneOffset.UTC);
		}
		throw new IllegalStateException("Data inesperada na projeção de credibilidade: " + value.getClass());
	}

	/** Uma consulta por conjunto: o SQL agrupa pelo dono e devolve (dono, contagem). */
	private Map<String, Integer> groupedCounts(Collection<String> ids, String sql) {
		if (ids.isEmpty()) {
			return Map.of();
		}
		@SuppressWarnings("unchecked")
		List<Object[]> rows = em.createNativeQuery(sql).setParameter("ids", ids).getResultList();
		var counts = new java.util.HashMap<String, Integer>();
		for (Object[] row : rows) {
			counts.put((String) row[0], ((Number) row[1]).intValue());
		}
		return counts;
	}

	@Override
	public Map<String, Integer> connectionCounts(Collection<String> ids) {
		// A conexão aceita é UMA linha com dois lados: o dono pode estar em qualquer um deles, e
		// quando os dois estão na página a linha conta para ambos — por isso o UNION ALL.
		return groupedCounts(ids, """
			select owner_id, count(*) from (
				select requester_user_id as owner_id from user_connections
					where status = 'ACCEPTED' and requester_user_id in (:ids)
				union all
				select receiver_user_id from user_connections
					where status = 'ACCEPTED' and receiver_user_id in (:ids)
			) sides group by owner_id""");
	}

	@Override
	public Map<String, Integer> followerCounts(Collection<String> ids) {
		return groupedCounts(ids, """
			select receiver_user_id, count(*) from user_connections
			where status = 'ACCEPTED' and receiver_user_id in (:ids)
			group by receiver_user_id""");
	}

	@Override
	public Map<String, Integer> uniqueEngagerCounts(Collection<String> ids) {
		// Pessoas DISTINTAS que reagiram ou comentaram posts do autor — o autor não conta.
		return groupedCounts(ids, """
			select author_id, count(distinct actor) from (
				select p.author_id as author_id, r.user_id as actor from reactions r
					join posts p on p.id = r.post_id
					where p.status not in ('DELETED', 'QUARANTINED') and p.author_id in (:ids)
				union
				select p2.author_id, c.author_id from comments c
					join posts p2 on p2.id = c.post_id
					where p2.status not in ('DELETED', 'QUARANTINED') and c.status <> 'DELETED'
						and p2.author_id in (:ids)
			) engagers where engagers.actor <> engagers.author_id
			group by author_id""");
	}

	@Override
	public Map<String, Integer> communitiesJoinedCounts(Collection<String> ids) {
		return groupedCounts(ids, """
			select user_id, count(*) from community_members
			where user_id in (:ids) group by user_id""");
	}

	@Override
	public Map<String, Integer> eventsAttendedTotalCounts(Collection<String> ids) {
		// Presenças vivas + agregado guardado antes da purga, em uma consulta só.
		return groupedCounts(ids, """
			select u.id, count(a.id) + coalesce(max(t.total), 0)
			from users u
			left join event_attendees a on a.user_id = u.id
			left join user_event_attendance_totals t on t.user_id = u.id
			where u.id in (:ids) group by u.id""");
	}

	@Override
	public Map<String, Integer> mentoringSessionCounts(Collection<String> ids) {
		return groupedCounts(ids, """
			select mentee_id, count(*) from mentor_sessions
			where status = 'COMPLETED' and mentee_id in (:ids) group by mentee_id""");
	}

	@Override
	public Map<String, Integer> confirmedReportFactCounts(Collection<String> ids) {
		// Fatos CONFIRMADOS e deduplicados por conteúdo — a mesma regra do caminho individual.
		return groupedCounts(ids, """
			select owners.owner_id, count(distinct r.target_id)
			from reports r
			join (
				select p.id as target_id, p.author_id as owner_id from posts p where p.author_id in (:ids)
				union all
				select c.id, c.author_id from comments c where c.author_id in (:ids)
				union all
				select j.id, j.posted_by from jobs j where j.posted_by in (:ids)
			) owners on owners.target_id = r.target_id
			where r.status in ('APPROVED', 'AUTO_PENALIZED')
			group by owners.owner_id""");
	}

	@Override
	public Optional<UserProfile> findByUsername(String username) {
		return repository.findByUsername(username).map(UserProfileJpaEntity::toDomain);
	}

	@Override
	public Optional<UserProfile> findByEmail(String email) {
		return repository.findByEmail(email).map(UserProfileJpaEntity::toDomain);
	}

	@Override
	public boolean existsByUsername(String username) {
		return repository.existsByUsername(username);
	}

	@Override
	public boolean existsByEmail(String email) {
		return repository.existsByEmail(email);
	}

	@Override
	public int countConnections(String userId) {
		var result = em.createNativeQuery(
			"select count(*) from user_connections where (requester_user_id = :uid or receiver_user_id = :uid) and status = 'ACCEPTED'")
			.setParameter("uid", userId)
			.getSingleResult();
		return ((Number) result).intValue();
	}

	@Override
	public int countFollowers(String userId) {
		var result = em.createNativeQuery(
			"select count(*) from user_connections where receiver_user_id = :uid and status = 'ACCEPTED'")
			.setParameter("uid", userId)
			.getSingleResult();
		return ((Number) result).intValue();
	}

	@Override
	public int countPostInteractionsReceived(String userId) {
		// Post em quarentena não soma credibilidade: ele está fora do ar até a revisão humana.
		var result = em.createNativeQuery(
			"select coalesce(sum(reactions + comments + shares), 0) from posts where author_id = :uid"
				+ " and status not in ('DELETED', 'QUARANTINED')")
			.setParameter("uid", userId)
			.getSingleResult();
		return ((Number) result).intValue();
	}

	@Override
	public int countCommunitiesJoined(String userId) {
		var result = em.createNativeQuery(
			"select count(*) from community_members where user_id = :uid")
			.setParameter("uid", userId)
			.getSingleResult();
		return ((Number) result).intValue();
	}

	@Override
	public int countEventsAttended(String userId) {
		var result = em.createNativeQuery(
			"select count(*) from event_attendees where user_id = :uid")
			.setParameter("uid", userId)
			.getSingleResult();
		return ((Number) result).intValue();
	}

	@Override
	public int countMentoringSessionsAsMentee(String userId) {
		var result = em.createNativeQuery(
			"select count(*) from mentor_sessions where mentee_id = :uid and status = 'COMPLETED'")
			.setParameter("uid", userId)
			.getSingleResult();
		return ((Number) result).intValue();
	}

	@Override
	public int countActiveReportsAgainst(String userId) {
		// Denuncias pendentes ou aprovadas contra conteudo do usuario (post, comentario, vaga).
		var result = em.createNativeQuery(
			"select count(*) from reports where status <> 'DISMISSED' and ("
				+ "target_id in (select id from posts where author_id = :uid) "
				+ "or target_id in (select id from comments where author_id = :uid) "
				+ "or target_id in (select id from jobs where posted_by = :uid))")
			.setParameter("uid", userId)
			.getSingleResult();
		return ((Number) result).intValue();
	}

	@Override
	public int countConfirmedReportFactsAgainst(String userId) {
		// Fatos CONFIRMADOS e deduplicados por conteúdo: uma denúncia pendente não é fato, e N
		// denúncias contra o MESMO post/comentário/vaga são um fato só. Recurso aceito volta o
		// report para DISMISSED e o fato desaparece daqui.
		var result = em.createNativeQuery(
			"select count(distinct target_id) from reports where status in ('APPROVED', 'AUTO_PENALIZED') and ("
				+ "target_id in (select id from posts where author_id = :uid) "
				+ "or target_id in (select id from comments where author_id = :uid) "
				+ "or target_id in (select id from jobs where posted_by = :uid))")
			.setParameter("uid", userId)
			.getSingleResult();
		return ((Number) result).intValue();
	}

	@Override
	public int countUniqueEngagers(String userId) {
		// Pessoas DISTINTAS que reagiram ou comentaram posts do autor — o autor não conta.
		// É isto que impede "comentar e apagar em loop" e autointeração de inflar a nota.
		var result = em.createNativeQuery(
			"select count(*) from ("
				+ "select r.user_id as actor from reactions r join posts p on p.id = r.post_id "
				+ "where p.author_id = :uid and p.status not in ('DELETED', 'QUARANTINED') "
				+ "union "
				+ "select c.author_id as actor from comments c join posts p2 on p2.id = c.post_id "
				+ "where p2.author_id = :uid and p2.status not in ('DELETED', 'QUARANTINED')"
				+ " and c.status <> 'DELETED'"
				+ ") engajadores where engajadores.actor <> :uid")
			.setParameter("uid", userId)
			.getSingleResult();
		return ((Number) result).intValue();
	}

	@Override
	public int countEventsAttendedTotal(String userId) {
		var result = em.createNativeQuery(
			"select (select count(*) from event_attendees where user_id = :uid) + "
				+ "coalesce((select total from user_event_attendance_totals where user_id = :uid), 0)")
			.setParameter("uid", userId)
			.getSingleResult();
		return ((Number) result).intValue();
	}

	@Override
	@Transactional
	public void updateReputation(String userId, int reputation) {
		em.createNativeQuery("update users set reputation = :value where id = :uid")
			.setParameter("value", reputation)
			.setParameter("uid", userId)
			.executeUpdate();
	}

	@Override
	public List<String> findAllIds(int offset, int limit) {
		@SuppressWarnings("unchecked")
		List<String> ids = em.createNativeQuery("select id from users order by id limit :limit offset :offset")
			.setParameter("limit", limit)
			.setParameter("offset", offset)
			.getResultList();
		return ids;
	}

	@Override
	public List<com.socialcoisa.rede.profile.application.port.out.ReplicaProfileKey> replicaPage(
			java.time.Instant afterCreatedAt, String afterId, int limit) {
		// O par (criado em, id) é a chave: com um dos dois faltando a comparação perde a ordem total
		// e a página seguinte repetiria itens — então ou os dois vêm, ou nenhum (início).
		boolean fromStart = afterCreatedAt == null || afterId == null;
		return repository.replicaPage(fromStart ? null : afterCreatedAt, fromStart ? null : afterId,
				UserProfile.REMOVED_USERNAME_PREFIX + "%", PageRequest.of(0, Math.max(1, limit)));
	}

	@Override
	public List<UserProfile> findAdmins() {
		return repository.findByIsAdminTrue().stream()
			.map(UserProfileJpaEntity::toDomain)
			.toList();
	}

	@Override
	public List<UserProfile> findNewestProfiles(int limit) {
		// O ranking público virou a vitrine de chegada (decisão do dono do produto, 2026-10-20):
		// mais recentes primeiro, ordenado NO BANCO (antes a lista inteira vinha para a memória e
		// só então era cortada). A busca de perfis continua por reputação descendente.
		return repository.findByOrderByCreatedAtDesc(PageRequest.of(0, Math.max(1, limit))).stream()
			.map(UserProfileJpaEntity::toDomain)
			.toList();
	}

	@Override
	public List<UserProfile> searchByQuery(String query) {
		return repository.searchByNameOrUsername(
				com.socialcoisa.rede.shared.web.LikePattern.contains(query),
				org.springframework.data.domain.PageRequest.of(0, 20))
			.stream()
			.map(UserProfileJpaEntity::toDomain)
			.toList();
	}
}
