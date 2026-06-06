package com.socialcoisa.rede.network.application;

import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.stream.Collectors;

import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.network.application.port.out.ConnectionRepository;
import com.socialcoisa.rede.network.domain.ConnectionSuggestion;
import com.socialcoisa.rede.network.domain.ConnectionStatus;
import com.socialcoisa.rede.network.domain.UserConnection;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.shared.web.ApiException;

@Service
public class NetworkService {

	private final ConnectionRepository connectionRepository;
	private final UserProfileRepository userProfileRepository;
	private final Clock clock;

	public NetworkService(ConnectionRepository connectionRepository, UserProfileRepository userProfileRepository,
			Clock clock) {
		this.connectionRepository = connectionRepository;
		this.userProfileRepository = userProfileRepository;
		this.clock = clock;
	}

	@Transactional
	public UserConnection requestConnection(String requesterUserId, String receiverUserId) {
		if (requesterUserId.equals(receiverUserId)) {
			throw new ApiException(400, "SELF_CONNECTION", "Nao e possivel conectar consigo mesmo.");
		}
		var existing = connectionRepository.findBetweenUsers(requesterUserId, receiverUserId);
		if (existing.isPresent()) {
			var conn = existing.get();
			return switch (conn.status()) {
				case PENDING -> throw new ApiException(409, "CONNECTION_ALREADY_REQUESTED",
					"Solicitacao de conexao ja enviada.");
				case ACCEPTED -> throw new ApiException(409, "ALREADY_CONNECTED", "Usuarios ja estao conectados.");
				case REMOVED, BLOCKED -> {
					var renewed = UserConnection.request(requesterUserId, receiverUserId, clock.instant());
					connectionRepository.save(renewed);
					yield renewed;
				}
			};
		}
		var connection = UserConnection.request(requesterUserId, receiverUserId, clock.instant());
		connectionRepository.save(connection);
		return connection;
	}

	@Transactional
	public UserConnection acceptConnection(String targetUserId, String currentUserId) {
		var connection = connectionRepository.findBetweenUsers(targetUserId, currentUserId)
			.orElseThrow(() -> new ApiException(404, "CONNECTION_NOT_FOUND", "Solicitacao de conexao nao encontrada."));
		if (connection.status() != ConnectionStatus.PENDING) {
			throw new ApiException(409, "INVALID_STATUS", "Solicitacao de conexao nao esta pendente.");
		}
		if (!connection.requesterUserId().equals(targetUserId)) {
			throw new ApiException(403, "FORBIDDEN", "Voce nao pode aceitar essa solicitacao.");
		}
		var accepted = connection.accept(clock.instant());
		connectionRepository.save(accepted);
		return accepted;
	}

	@Transactional
	public void removeConnection(String targetUserId, String currentUserId) {
		var connection = connectionRepository.findBetweenUsers(currentUserId, targetUserId)
			.or(() -> connectionRepository.findBetweenUsers(targetUserId, currentUserId))
			.orElseThrow(() -> new ApiException(404, "CONNECTION_NOT_FOUND", "Conexao nao encontrada."));
		var removed = connection.remove(clock.instant());
		connectionRepository.save(removed);
	}

	public List<UserConnection> getConnections(String userId) {
		return connectionRepository.findConnections(userId, ConnectionStatus.ACCEPTED);
	}

	public List<UserConnection> getPendingRequests(String userId) {
		return connectionRepository.findRequests(userId, ConnectionStatus.PENDING);
	}

	public int countConnections(String userId) {
		return connectionRepository.countByUserAndStatus(userId, ConnectionStatus.ACCEPTED);
	}

	public List<ConnectionSuggestion> getSuggestions(String userId, int limit) {
		return connectionRepository.findSuggestions(userId, limit);
	}

	public Set<UserId> findFriends(UserId userId) {
		return connectionRepository.findAcceptedFriendIds(userId);
	}

	public Set<UserId> findFriendsOfFriends(UserId userId) {
		Set<UserId> result = new HashSet<>();
		for (UserId friend : findFriends(userId)) {
			result.addAll(findFriends(friend));
		}
		result.remove(userId);
		return result;
	}

	public Set<String> findUserTags(UserId userId) {
		return userProfileRepository.findById(userId.value())
			.map(profile -> profile.skills().stream()
				.map(String::toLowerCase)
				.collect(Collectors.toSet()))
			.orElseGet(Set::of);
	}

}
