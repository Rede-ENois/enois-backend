package com.socialcoisa.rede.network.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.network.domain.ConnectionSuggestion;
import com.socialcoisa.rede.network.domain.ConnectionStatus;
import com.socialcoisa.rede.network.domain.UserConnection;

public interface ConnectionRepository {

	void save(UserConnection connection);

	/**
	 * Marca a linha como removida sem carregar/regravar a entidade. Dois DELETE concorrentes do
	 * mesmo par davam "Record has changed since last read" (update de 0 linhas); o UPDATE direto
	 * é idempotente e não disputa o estado antigo.
	 */
	void markRemoved(Long id, Instant removedAt);

	/**
	 * Aceita a solicitação com UPDATE condicional (só se ainda estiver PENDING). Devolve 1 quando
	 * esta chamada venceu a corrida e 0 quando outra já aceitou — dois cliques simultâneos não
	 * podem gerar "Record has changed since last read" nem notificação duplicada.
	 */
	int markAccepted(Long id, Instant acceptedAt);

	Optional<UserConnection> findBetweenUsers(String requesterUserId, String receiverUserId);

	List<UserConnection> findConnections(String userId, ConnectionStatus status);

	List<UserConnection> findRequests(String userId, ConnectionStatus status);

	int contarSolicitacoesRecebidasVisiveis(String userId);

	int countByUserAndStatus(String userId, ConnectionStatus status);

	/** Only an ACCEPTED connection counts: a pending request must not unlock anything. */
	boolean existsAcceptedConnection(String userId1, String userId2);

	/** The ids among {@code others} with an ACCEPTED connection to {@code userId}, in one query. */
	Set<String> findAcceptedAmong(String userId, java.util.Collection<String> others);

	Set<UserId> findAcceptedFriendIds(UserId userId);

	List<ConnectionSuggestion> findSuggestions(String userId, int limit);
}
