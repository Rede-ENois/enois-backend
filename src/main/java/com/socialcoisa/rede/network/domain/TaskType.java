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
	API_REQUEST(NodeProfile.NODE_API),

	/** Gravação de um objeto do Énóis Storage num nó. */
	OBJECT_PUT(NodeProfile.NODE_STORAGE),

	/** Leitura de um objeto do Énóis Storage de um nó. */
	OBJECT_GET(NodeProfile.NODE_STORAGE);

	private final NodeProfile requiredProfile;

	TaskType(NodeProfile requiredProfile) {
		this.requiredProfile = requiredProfile;
	}

	public NodeProfile requiredProfile() {
		return requiredProfile;
	}

	/**
	 * O perfil cobre o tipo? {@code NODE_LOCAL} cobre todos.
	 *
	 * <p>Para os tipos de armazenamento, qualquer perfil cujo {@link NodeProfile#storageKind()} não
	 * seja nulo cobre: os tipos especializados do brief §6 são <b>storage</b> como o
	 * {@code NODE_STORAGE}, e quem tem só {@code NODE_STORAGE} continua recebendo tudo. A
	 * especialização muda <i>para onde</i> a colocação prefere mandar, não <i>se</i> pode guardar.</p>
	 */
	public boolean allowedFor(Set<NodeProfile> profiles) {
		if (profiles == null || profiles.isEmpty()) {
			return false;
		}
		if (profiles.contains(NodeProfile.NODE_LOCAL)) {
			return true;
		}
		if (isStorage() && profiles.stream().anyMatch(profile -> profile.storageKind() != null)) {
			return true;
		}
		return profiles.contains(requiredProfile);
	}

	/** Tipo de armazenamento do Énóis Storage (brief §6)? */
	public boolean isStorage() {
		return this == OBJECT_PUT || this == OBJECT_GET;
	}

	/**
	 * Os tipos que um conjunto de perfis permite, em ordem estável (a da declaração do enum). É a
	 * lista que vai na config assinada do nó: mesma regra do predicado {@link #allowedFor(Set)},
	 * para que a config não possa dizer uma coisa e o despacho fazer outra.
	 */
	public static java.util.List<TaskType> allowedTypesFor(Set<NodeProfile> profiles) {
		return java.util.Arrays.stream(values()).filter(type -> type.allowedFor(profiles)).toList();
	}

	/**
	 * Os mesmos tipos, como nomes, na lista que a config assinada leva e o runtime confere.
	 *
	 * <p>Vive aqui, e não no serviço de config, porque há dois leitores da MESMA permissão: a config
	 * que o nó obedece e a declaração de capacidade (brief §8.2, "Health checks"). Uma lista e a
	 * outra divergindo faria o nó declarar como executável um tipo que a config dele nem autoriza.</p>
	 */
	public static java.util.List<String> allowedNamesFor(Set<NodeProfile> profiles) {
		return allowedTypesFor(profiles).stream().map(Enum::name).toList();
	}
}
