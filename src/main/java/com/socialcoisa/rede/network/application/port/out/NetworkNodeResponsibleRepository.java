package com.socialcoisa.rede.network.application.port.out;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * Quem responde por um nó, quando o nó não veio de uma conta do site.
 *
 * <p>A inscrição aprovada identifica a pessoa por nome e e-mail; o runtime consome a chave e cria o
 * nó sem que exista conta. Esta tabela guarda esse retrato no momento do enroll. Nó provisionado
 * pelo caminho administrativo antigo não tem linha aqui — o painel cai na conta dona
 * ({@code owner_user_id}).</p>
 */
public interface NetworkNodeResponsibleRepository {

	record Responsible(String nodeId, String fullName, String email, String source) {
	}

	void save(String nodeId, String fullName, String email, String source, Instant now);

	Optional<Responsible> find(String nodeId);

	/** Todos os responsáveis dos nós da página, numa consulta — evita N+1 na listagem. */
	Map<String, Responsible> byNodeIds(Collection<String> nodeIds);
}