package com.socialcoisa.rede.profile.adapters.out.persistence;

import java.util.Collection;
import java.util.List;
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
