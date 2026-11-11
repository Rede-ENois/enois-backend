package com.socialcoisa.rede.shared.broadcast;

import java.time.Instant;

/**
 * Um sinal de fan-out entre instâncias do Control Plane.
 *
 * <p>Sinal é aviso, não conteúdo: {@code target} diz o que mudou (um id de usuário, o nome de um
 * cache) e {@code payload} carrega só o que a entrega local precisa para montar o evento do
 * stream. Quem recebe continua buscando o dado pelo endpoint autorizado.</p>
 *
 * @param seq posição monotônica na tabela; é a posição de leitura de cada instância
 * @param id identificador do sinal
 * @param originInstance instância que emitiu (ela já entregou no próprio estado local)
 * @param type tipo do sinal, ver {@link InstanceSignals}
 * @param target alvo da entrega no estado local
 * @param payload dados mínimos da entrega, em JSON
 * @param createdAt instante da emissão
 */
public record InstanceSignal(long seq, String id, String originInstance, String type, String target,
		String payload, Instant createdAt) {

	/**
	 * Sinal ainda não gravado: a posição é atribuída pela tabela no insert, então quem emite não a
	 * conhece. Só a leitura preenche {@code seq}.
	 */
	public InstanceSignal(String id, String originInstance, String type, String target, String payload,
			Instant createdAt) {
		this(0L, id, originInstance, type, target, payload, createdAt);
	}
}
