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
import com.socialcoisa.rede.notifications.application.NotificationService;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.shared.web.ApiException;

@Service
public class NetworkService {

	private final ConnectionRepository connectionRepository;
	private final UserProfileRepository userProfileRepository;
	private final NotificationService notificationService;
	private final com.socialcoisa.rede.network.application.port.out.UserBlockRepository blockRepository;
	private final Clock clock;

	public NetworkService(ConnectionRepository connectionRepository, UserProfileRepository userProfileRepository,
			NotificationService notificationService,
			com.socialcoisa.rede.network.application.port.out.UserBlockRepository blockRepository, Clock clock) {
		this.connectionRepository = connectionRepository;
		this.userProfileRepository = userProfileRepository;
		this.notificationService = notificationService;
		this.blockRepository = blockRepository;
		this.clock = clock;
	}

	@Transactional
	public UserConnection requestConnection(String requesterUserId, String receiverUserId) {
		if (requesterUserId.equals(receiverUserId)) {
			throw new ApiException(400, "SELF_CONNECTION", "Nao e possivel conectar consigo mesmo.");
		}
		// Bloqueio vale para sempre: nao ha nova solicitacao nem notificacao de quem foi bloqueado.
		if (blockRepository.hiddenBetween(requesterUserId, receiverUserId)) {
			throw new ApiException(403, "BLOCKED", "Esta conexao nao esta disponivel.");
		}
		// Without this an unknown id left a pending request (and a notification) for nobody.
		if (userProfileRepository.findById(receiverUserId).isEmpty()) {
			throw new ApiException(404, "USER_NOT_FOUND", "Usuario nao encontrado.");
		}
		var existing = connectionRepository.findBetweenUsers(requesterUserId, receiverUserId);
		if (existing.isPresent()) {
			var conn = existing.get();
			return switch (conn.status()) {
				case PENDING, ACCEPTED -> conn;
				case REMOVED, BLOCKED -> {
					// Mesma linha (id existente): uma linha nova colidiria com o par único.
					var renewed = conn.reopen(requesterUserId, receiverUserId, clock.instant());
					connectionRepository.save(renewed);
					notifyConnectionRequest(requesterUserId, receiverUserId);
					yield renewed;
				}
			};
		}
		var connection = UserConnection.request(requesterUserId, receiverUserId, clock.instant());
		connectionRepository.save(connection);
		notifyConnectionRequest(requesterUserId, receiverUserId);
		return connection;
	}

	private void notifyConnectionRequest(String requesterUserId, String receiverUserId) {
		userProfileRepository.findById(requesterUserId).ifPresent(requester ->
			notificationService.create(receiverUserId, "CONNECTION_REQUEST", requesterUserId,
				"Solicitação de conexão",
				requester.name() + " quer se conectar com você.",
				"/api/network/connections/" + requesterUserId + "/accept", "Aceitar")
		);
	}

	@Transactional
	public UserConnection acceptConnection(String targetUserId, String currentUserId) {
		var connection = connectionRepository.findBetweenUsers(targetUserId, currentUserId)
			.orElseThrow(() -> new ApiException(404, "CONNECTION_NOT_FOUND", "Solicitacao de conexao nao encontrada."));
		if (connection.status() == ConnectionStatus.ACCEPTED) {
			return connection;
		}
		if (connection.status() != ConnectionStatus.PENDING) {
			throw new ApiException(409, "INVALID_STATUS", "Solicitacao de conexao nao esta pendente.");
		}
		if (!connection.requesterUserId().equals(targetUserId)) {
			throw new ApiException(403, "FORBIDDEN", "Voce nao pode aceitar essa solicitacao.");
		}
		if (blockRepository.hiddenBetween(targetUserId, currentUserId)) {
			throw new ApiException(403, "BLOCKED", "Esta conexao nao esta disponivel.");
		}
		var accepted = connection.accept(clock.instant());
		if (connectionRepository.markAccepted(connection.id(), accepted.acceptedAt()) == 0) {
			// Outra chamada aceitou (ou removeu) a mesma solicitação primeiro. Não repete a
			// notificação e devolve o estado que venceu a corrida.
			var current = connectionRepository.findBetweenUsers(targetUserId, currentUserId).orElse(accepted);
			if (current.status() != ConnectionStatus.ACCEPTED) {
				throw new ApiException(409, "INVALID_STATUS", "Solicitacao de conexao nao esta mais pendente.");
			}
			return current;
		}
		userProfileRepository.findById(currentUserId).ifPresent(accepter ->
			notificationService.create(targetUserId, "CONNECTION_ACCEPTED", currentUserId,
				"Conexão aceita",
				accepter.name() + " aceitou sua solicitação de conexão.",
				"/api/network/connections/" + currentUserId, "Ver")
		);
		return accepted;
	}

	@Transactional
	public void removeConnection(String targetUserId, String currentUserId) {
		var connection = connectionRepository.findBetweenUsers(currentUserId, targetUserId)
			.or(() -> connectionRepository.findBetweenUsers(targetUserId, currentUserId))
			.orElseThrow(() -> new ApiException(404, "CONNECTION_NOT_FOUND", "Conexao nao encontrada."));
		if (connection.status() == ConnectionStatus.REMOVED) {
			return;
		}
		connectionRepository.markRemoved(connection.id(), clock.instant());
	}

	/**
	 * Estado da conexão visto por quem consulta. O par é único e a busca olha os dois sentidos: sem
	 * {@code requestedByMe}, quem recebeu o pedido e quem o enviou recebem o mesmo PENDING e a tela não
	 * tem como oferecer "aceitar" a um e "cancelar" ao outro.
	 */
	public ConnectionState getConnectionState(String currentUserId, String targetUserId) {
		return connectionRepository.findBetweenUsers(currentUserId, targetUserId)
			.map(c -> new ConnectionState(c.status().name(),
				c.status() == ConnectionStatus.PENDING && c.requesterUserId().equals(currentUserId)))
			.orElse(new ConnectionState("NONE", false));
	}

	public record ConnectionState(String status, boolean requestedByMe) {
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
