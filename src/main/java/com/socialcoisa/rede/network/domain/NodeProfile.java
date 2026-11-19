package com.socialcoisa.rede.network.domain;

import java.util.Set;

/**
 * Perfis de participação na rede. Um perfil é um contrato de permissão, não um binário: ele diz
 * quais tipos de tarefa o nó pode receber e que classe de dado pode tocar.
 *
 * <p>Capacidade computacional não dá permissão: um nó de 64 núcleos com perfil NODE_MEDIA não
 * recebe tarefa de NODE_WORKER. Um servidor pode executar mais de um perfil se suas permissões
 * permitirem.</p>
 */
public enum NodeProfile {

	/** Distribuição de tráfego, cache e entrega de conteúdo. Nunca decide autorização de usuário. */
	NODE_EDGE,

	/** Tarefas assíncronas e processamento intensivo. Nunca escreve no banco principal. */
	NODE_WORKER,

	/** Processamento de mídia: recebe o objeto da tarefa, nada de dado pessoal fora dele. */
	NODE_MEDIA,

	/** Leitura de projeções autorizadas. Somente leitura, com allowlist explícita. */
	NODE_READ_REPLICA,

	/** Instâncias auxiliares de API. Nunca emite sessão própria. */
	NODE_API,

	/** Nó da própria infraestrutura do Énóis. Prioridade de escalonamento, não permissão extra. */
	NODE_LOCAL,

	/**
	 * Armazenamento de objetos do Énóis Storage, tipo <b>convencional</b> (brief §6,
	 * {@code STORAGE_NODE_STANDARD}): guarda bytes que não são dele, com leitura e escrita.
	 *
	 * <p>É o perfil anterior aos tipos. Todo nó de storage declarado antes deste contrato tem só
	 * ele, e a colocação o trata como o tipo {@code STANDARD} — a especialização é aditiva, nunca
	 * um segundo vocabulário.</p>
	 */
	NODE_STORAGE,

	/**
	 * Alias explícito de {@link #NODE_STORAGE} com o nome do brief §6 ({@code STORAGE_NODE_STANDARD}).
	 *
	 * <p>Existe para que um participante novo possa declarar o tipo pelo nome do contrato. É o
	 * mesmo perfil: quem tem um recebe o que o outro recebe.</p>
	 */
	NODE_STORAGE_STANDARD,

	// Os tipos especializados abaixo são permissão igual a NODE_STORAGE. O que muda é para onde a
	// colocação prefere mandar o objeto (ver StorageNodeKind e StoragePlacementService). Um mesmo
	// servidor pode ter vários, e quem tem apenas NODE_STORAGE continua recebendo tudo.

	/** Mídia: imagens, vídeos e miniaturas (brief §6, {@code STORAGE_NODE_MEDIA}). */
	NODE_STORAGE_MEDIA,

	/** Arquivo de longo prazo: prioriza capacidade e custo (brief §6, {@code STORAGE_NODE_ARCHIVE}). */
	NODE_STORAGE_ARCHIVE,

	/** Perto dos usuários: prioriza leitura e distribuição (brief §6, {@code STORAGE_NODE_EDGE}). */
	NODE_STORAGE_EDGE,

	/** Mantém cópias redundantes (brief §6, {@code STORAGE_NODE_REPLICA}). */
	NODE_STORAGE_REPLICA,

	/**
	 * Servidor doméstico: disponibilidade variável, limite de banda e suporte a NAT (brief §6,
	 * {@code STORAGE_NODE_HOME}).
	 *
	 * <p>Só marcado, sem regra própria ainda: durabilidade diferenciada para nó de casa não existe.
	 * O perfil serve para o participante declarar a natureza da máquina e para o painel mostrá-la.
	 * A doc registra a lacuna.</p>
	 */
	NODE_STORAGE_HOME,

	/** Contingência: só assume as funções previamente preparadas para incidente. */
	NODE_EMERGENCY

	;

	/**
	 * Este perfil dá permissão de armazenamento — e portanto qual <b>tipo</b> de storage ele é.
	 *
	 * @return o tipo do brief §6, ou {@code null} se o perfil não é de armazenamento
	 */
	public StorageNodeKind storageKind() {
		return switch (this) {
			case NODE_STORAGE, NODE_STORAGE_STANDARD -> StorageNodeKind.STANDARD;
			case NODE_STORAGE_MEDIA -> StorageNodeKind.MEDIA;
			case NODE_STORAGE_ARCHIVE -> StorageNodeKind.ARCHIVE;
			case NODE_STORAGE_EDGE -> StorageNodeKind.EDGE;
			case NODE_STORAGE_REPLICA -> StorageNodeKind.REPLICA;
			case NODE_STORAGE_HOME -> StorageNodeKind.HOME;
			default -> null;
		};
	}

	/** Perfis válidos numa string CSV; parse tolerante é coisa do service, não do domínio. */
	public static Set<NodeProfile> all() {
		return Set.of(values());
	}
}
