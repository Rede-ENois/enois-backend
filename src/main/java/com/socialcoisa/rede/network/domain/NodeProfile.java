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

	/** Contingência: só assume as funções previamente preparadas para incidente. */
	NODE_EMERGENCY;

	/** Perfis válidos numa string CSV; parse tolerante é coisa do service, não do domínio. */
	public static Set<NodeProfile> all() {
		return Set.of(values());
	}
}
