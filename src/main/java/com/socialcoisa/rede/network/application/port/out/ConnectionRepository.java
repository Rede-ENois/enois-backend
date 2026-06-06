package com.socialcoisa.rede.network.application.port.out;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.network.domain.ConnectionSuggestion;
import com.socialcoisa.rede.network.domain.ConnectionStatus;
import com.socialcoisa.rede.network.domain.UserConnection;

public interface ConnectionRepository {

	void save(UserConnection connection);

	Optional<UserConnection> findBetweenUsers(String requesterUserId, String receiverUserId);

	List<UserConnection> findConnections(String userId, ConnectionStatus status);

	List<UserConnection> findRequests(String userId, ConnectionStatus status);

	int countByUserAndStatus(String userId, ConnectionStatus status);

	boolean existsActiveConnection(String userId1, String userId2);

	Set<UserId> findAcceptedFriendIds(UserId userId);

	List<ConnectionSuggestion> findSuggestions(String userId, int limit);
}
