package com.socialcoisa.rede.network.adapters.out.persistence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.network.application.port.out.ConnectionRepository;
import com.socialcoisa.rede.network.domain.ConnectionSuggestion;
import com.socialcoisa.rede.network.domain.ConnectionStatus;
import com.socialcoisa.rede.network.domain.UserConnection;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

@Repository
class ConnectionPersistenceAdapter implements ConnectionRepository {

	private final SpringDataUserConnectionJpaRepository springData;
	private final EntityManager em;

	ConnectionPersistenceAdapter(SpringDataUserConnectionJpaRepository springData, EntityManager em) {
		this.springData = springData;
		this.em = em;
	}

	@Override
	public void save(UserConnection connection) {
		springData.save(UserConnectionJpaEntity.fromDomain(connection));
	}

	@Override
	public void markRemoved(Long id, Instant removedAt) {
		springData.markRemoved(id, ConnectionStatus.REMOVED, removedAt);
	}

	@Override
	public int markAccepted(Long id, Instant acceptedAt) {
		return springData.markAccepted(id, acceptedAt);
	}

	@Override
	public Optional<UserConnection> findBetweenUsers(String requesterUserId, String receiverUserId) {
		return springData.findBetweenUsers(requesterUserId, receiverUserId)
			.map(UserConnectionJpaEntity::toDomain);
	}

	@Override
	public List<UserConnection> findConnections(String userId, ConnectionStatus status) {
		var asRequester = springData.findByRequesterUserIdAndStatus(userId, status);
		var asReceiver = springData.findByReceiverUserIdAndStatus(userId, status);
		return java.util.stream.Stream.concat(asRequester.stream(), asReceiver.stream())
			.map(UserConnectionJpaEntity::toDomain)
			.toList();
	}

	@Override
	public List<UserConnection> findRequests(String userId, ConnectionStatus status) {
		return springData.findByRequesterUserIdAndStatusOrReceiverUserIdAndStatus(userId, status, userId, status)
			.stream()
			.map(UserConnectionJpaEntity::toDomain)
			.toList();
	}

	@Override
	public int countByUserAndStatus(String userId, ConnectionStatus status) {
		return springData.countByRequesterUserIdAndStatus(userId, status)
			+ springData.countByReceiverUserIdAndStatus(userId, status);
	}

	@Override
	public boolean existsAcceptedConnection(String userId1, String userId2) {
		return springData.existsAcceptedConnection(userId1, userId2);
	}

	@Override
	public Set<String> findAcceptedAmong(String userId, java.util.Collection<String> others) {
		return others.isEmpty() ? Set.of() : new HashSet<>(springData.findAcceptedAmong(userId, others));
	}

	@Override
	public Set<UserId> findAcceptedFriendIds(UserId userId) {
		var ids = springData.findAcceptedFriendIds(userId.value());
		Set<UserId> result = new HashSet<>();
		for (String id : ids) {
			result.add(new UserId(id));
		}
		return result;
	}

	/** People already connected to, or with a pending request with, {@code :userId}. */
	private static final String ALREADY_LINKED = """
			SELECT requester_user_id FROM user_connections
			WHERE receiver_user_id = :userId AND status IN ('PENDING','ACCEPTED')
			UNION
			SELECT receiver_user_id FROM user_connections
			WHERE requester_user_id = :userId AND status IN ('PENDING','ACCEPTED')""";

	/**
	 * Friends of friends first, ranked by how many connections they share with the user, in one
	 * query; then other people to fill the list. The ranking used to run on 50 arbitrary users, so
	 * with a large base the people with friends in common were often not even considered.
	 */
	@Override
	public List<ConnectionSuggestion> findSuggestions(String userId, int limit) {
		Set<String> friendIds = new HashSet<>(springData.findAcceptedFriendIds(userId));
		List<ConnectionSuggestion> suggestions = new ArrayList<>();
		if (!friendIds.isEmpty()) {
			Query mutual = em.createNativeQuery("""
					SELECT f.other_id, COUNT(*) AS mutual FROM (
					  SELECT receiver_user_id AS other_id FROM user_connections
					  WHERE status = 'ACCEPTED' AND requester_user_id IN (:friendIds)
					  UNION ALL
					  SELECT requester_user_id AS other_id FROM user_connections
					  WHERE status = 'ACCEPTED' AND receiver_user_id IN (:friendIds)
					) f
					WHERE f.other_id <> :userId AND f.other_id NOT IN (""" + ALREADY_LINKED + """
					)
					GROUP BY f.other_id
					ORDER BY mutual DESC, f.other_id
					LIMIT :limit""");
			mutual.setParameter("friendIds", friendIds);
			mutual.setParameter("userId", userId);
			mutual.setParameter("limit", limit);
			for (Object row : mutual.getResultList()) {
				Object[] columns = (Object[]) row;
				suggestions.add(new ConnectionSuggestion((String) columns[0], ((Number) columns[1]).intValue()));
			}
		}
		if (suggestions.size() < limit) {
			Set<String> taken = new HashSet<>();
			suggestions.forEach(suggestion -> taken.add(suggestion.userId()));
			Query others = em.createNativeQuery("""
					SELECT id FROM users
					WHERE id <> :userId AND id NOT IN (""" + ALREADY_LINKED + """
					)
					LIMIT :limit""");
			others.setParameter("userId", userId);
			others.setParameter("limit", limit + taken.size());
			for (Object id : others.getResultList()) {
				if (suggestions.size() >= limit) {
					break;
				}
				if (taken.add((String) id)) {
					suggestions.add(new ConnectionSuggestion((String) id, 0));
				}
			}
		}
		return suggestions;
	}

}
