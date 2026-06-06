package com.socialcoisa.rede.network.adapters.out.persistence;

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
		return springData.findByReceiverUserIdAndStatus(userId, status).stream()
			.map(UserConnectionJpaEntity::toDomain)
			.toList();
	}

	@Override
	public int countByUserAndStatus(String userId, ConnectionStatus status) {
		return springData.countByRequesterUserIdAndStatus(userId, status)
			+ springData.countByReceiverUserIdAndStatus(userId, status);
	}

	@Override
	public boolean existsActiveConnection(String userId1, String userId2) {
		return springData.existsActiveConnection(userId1, userId2);
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

	@Override
	@SuppressWarnings("unchecked")
	public List<ConnectionSuggestion> findSuggestions(String userId, int limit) {
		Set<String> friendIds = new HashSet<>(springData.findAcceptedFriendIds(userId));
		Query candidates = em.createNativeQuery("""
				SELECT id FROM users
				WHERE id != :userId
				  AND id NOT IN (
				    SELECT requester_user_id FROM user_connections
				    WHERE receiver_user_id = :userId AND status IN ('PENDING','ACCEPTED')
				  )
				  AND id NOT IN (
				    SELECT receiver_user_id FROM user_connections
				    WHERE requester_user_id = :userId AND status IN ('PENDING','ACCEPTED')
				  )
				LIMIT 50""");
		candidates.setParameter("userId", userId);
		List<String> candidateIds = candidates.getResultList();
		List<ConnectionSuggestion> suggestions = new ArrayList<>();
		for (String candidateId : candidateIds) {
			if (candidateId.equals(userId) || friendIds.contains(candidateId)) {
				continue;
			}
			int mutual = countMutualConnections(userId, candidateId, friendIds);
			suggestions.add(new ConnectionSuggestion(candidateId, mutual));
		}
		return suggestions.stream()
			.sorted((a, b) -> Integer.compare(b.mutualConnections(), a.mutualConnections()))
			.limit(limit)
			.toList();
	}

	private int countMutualConnections(String userId, String candidateId, Set<String> friendIds) {
		if (friendIds.isEmpty()) {
			return 0;
		}
		Query q = em.createNativeQuery("""
				SELECT COUNT(*) FROM user_connections c
				WHERE c.status = 'ACCEPTED'
				  AND ((c.requester_user_id IN (:friendIds) AND c.receiver_user_id = :candidateId)
				    OR (c.requester_user_id = :candidateId AND c.receiver_user_id IN (:friendIds)))""");
		q.setParameter("friendIds", friendIds);
		q.setParameter("candidateId", candidateId);
		return ((Number) q.getSingleResult()).intValue();
	}

}
