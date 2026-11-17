package com.socialcoisa.rede.network.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.network.domain.NetworkJoinRequest;

/**
 * Persistência das inscrições para servir um nó.
 *
 * <p>As escritas que mudam decisão são condicionais por estado (`where status = 'PENDING'`), e a
 * que consome o link é condicional por uso (`where reveal_consumed_at is null`): duas requisições
 * concorrentes não podem decidir duas vezes a mesma inscrição nem exibir a mesma chave duas vezes.
 * Quem perde a corrida recebe {@code false} e o serviço traduz em 409.</p>
 */
public interface NetworkJoinRequestRepository {

	void insert(NetworkJoinRequest request);

	Optional<NetworkJoinRequest> findById(String id);

	/** Inscrição ainda pendente com este e-mail ou CPF — o freio contra pedido duplicado. */
	Optional<NetworkJoinRequest> findPendingByEmailOrCpf(String email, String cpf);

	List<NetworkJoinRequest> list(NetworkJoinRequest.Status status, int limit);

	/** A inscrição do link, travada para a transação que consome o uso único. */
	Optional<NetworkJoinRequest> findByRevealHashForUpdate(String revealHash);

	Optional<NetworkJoinRequest> findByRevealHash(String revealHash);

	/** Decide uma inscrição {@code PENDING}. Devolve {@code false} se ela já foi decidida. */
	boolean decide(String id, NetworkJoinRequest.Status status, String profiles, String decidedBy, String note,
			String revealHash, Instant revealExpiresAt, Instant now);

	/** Troca o link de uma inscrição aprovada e ainda não usada. Devolve {@code false} se já usada. */
	boolean rotateReveal(String id, String newHash, Instant expiresAt, Instant now);

	/** Marca o uso único do link. Devolve {@code false} quando outra requisição chegou antes. */
	boolean consumeReveal(String id, Instant now);

	void markEmailSent(String id, Instant now);
}