package com.socialcoisa.rede.profile.adapters.out.persistence;

import java.util.Optional;

import org.springframework.stereotype.Repository;

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
		var result = em.createNativeQuery(
			"select coalesce(sum(reactions + comments + shares), 0) from posts where author_id = :uid and status <> 'DELETED'")
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
}
