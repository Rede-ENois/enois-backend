package com.socialcoisa.rede.network.domain;

import java.util.Set;

/**
 * Tipo de tarefa que a rede pode distribuir.
 *
 * <p>Cada tipo exige um perfil (docs/rede-distribuida/04-identidade-e-autorizacao.md). O vínculo é
 * do tipo, não do nó: um nó com 64 núcleos e perfil {@code NODE_MEDIA} não recebe {@code ASYNC_JOB}.
 * {@code NODE_LOCAL} é a infraestrutura do Énóis e executa qualquer tipo — é prioridade de
 * escalonamento, não permissão nova.</p>
 *
 * <p>{@code NODE_EMERGENCY} não aparece aqui de propósito: contingência distribuída é a fase 6, e
 * até lá um nó de emergência não recebe tarefa nenhuma. Preparar a função é diferente de
 * autorizá-la.</p>
 */
public enum TaskType {

	/** Cache de borda: materializa conteúdo público. */
	EDGE_CACHE(NodeProfile.NODE_EDGE),

	/** Entrega de conteúdo público pela borda. */
	EDGE_DELIVER(NodeProfile.NODE_EDGE),

	/** Tarefa assíncrona genérica. */
	ASYNC_JOB(NodeProfile.NODE_WORKER),

	/** Transcode de mídia. */
	MEDIA_TRANSCODE(NodeProfile.NODE_MEDIA),

	/** Geração de miniatura de mídia. */
	MEDIA_THUMBNAIL(NodeProfile.NODE_MEDIA),

	/** Consulta de leitura sobre projeção autorizada. */
	READ_QUERY(NodeProfile.NODE_READ_REPLICA),

	/** Requisição a uma instância auxiliar de API. */
	API_REQUEST(NodeProfile.NODE_API);

	private final NodeProfile requiredProfile;

	TaskType(NodeProfile requiredProfile) {
		this.requiredProfile = requiredProfile;
	}

	public NodeProfile requiredProfile() {
		return requiredProfile;
	}

	/** O perfil cobre o tipo? {@code NODE_LOCAL} cobre todos. */
	public boolean allowedFor(Set<NodeProfile> profiles) {
		if (profiles == null || profiles.isEmpty()) {
			return false;
		}
		return profiles.contains(requiredProfile) || profiles.contains(NodeProfile.NODE_LOCAL);
	}
}
