package com.socialcoisa.rede.network.domain;

/**
 * A chave de posição de uma projeção na ordenação da réplica de leitura.
 *
 * <p>Por que <b>chave</b> e não {@code offset}: a réplica percorre o catálogo em páginas e, entre
 * uma página e a seguinte, alguém cria e apaga contas. Com {@code offset} a página seguinte desloca
 * — conta nova entra antes do ponto lido e o item é pulado; conta apagada sai e o item aparece duas
 * vezes. Com chave, "o próximo é o primeiro depois de (a, i)" é uma pergunta que não depende do que
 * existe antes, então página perdida não vira buraco nem repetição.</p>
 *
 * <p>O par é (chave, id) e não só o id porque a ordenação precisa ser estável para quem lê e não
 * depende de UUID: para o perfil a chave é o {@code created_at} (quem chegou primeiro), desempatado
 * pelo id; para a comunidade é o próprio id. Dois itens podem compartilhar a chave; o id é o que
 * nunca empata.</p>
 *
 * @param key valor da chave de ordenação; nulo só na posição inicial ("desde o começo")
 * @param id id estável do agregado, que desempata a chave
 */
public record ReplicaCursor(String key, String id) {

	/** A posição inicial: a réplica nunca sincronizou e pede o catálogo desde o começo. */
	public static ReplicaCursor start() {
		return new ReplicaCursor(null, null);
	}

	public boolean isStart() {
		return id == null || id.isBlank();
	}
}
