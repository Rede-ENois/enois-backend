package com.socialcoisa.rede.network.domain;

/**
 * O que o Control Plane pode afirmar sobre a saúde da <b>rede</b> — e não de um nó (brief §8.8,
 * "divergência de dados", "falha de captura", "Master sobrecarregado enquanto existem réplicas
 * ociosas").
 *
 * <p>O modo medido já vai para {@code DEGRADED} quando há divergência ou captura exigida e quebrada
 * ({@link NetworkMode.HealthSignal}), mas o painel dizia o modo e não a <b>causa</b>: o operador via
 * "DEGRADED" e ia procurar no lugar errado. Esta é a mesma medida que o modo usa, dita em números.
 * </p>
 *
 * <p>"Não sei" não é problema: {@code captureState} nulo é "não foi possível perguntar ao banco",
 * e a captura só entra como defeito quando a instalação <b>declarou</b> exigir — as duas regras de
 * {@link NetworkMode.HealthSignal#of}. Não há cópia delas aqui.</p>
 *
 * @param divergingContent quantos agregados de conteúdo o nó servindo e a origem discordam
 * @param captureRequired  a instalação declarou exigir a captura de alterações?
 * @param captureState     {@code READY}, {@code NOT_READY} ou {@code null} quando não foi possível medir
 */
public record NetworkHealth(long divergingContent, boolean captureRequired, String captureState) {

	/** Em dia, ou sem medida que permita afirmar o contrário. */
	public boolean healthy() {
		return signal() == NetworkMode.HealthSignal.NONE;
	}

	/** A mesma evidência que o modo usa: uma função, duas leituras (modo e painel). */
	public NetworkMode.HealthSignal signal() {
		Boolean ready = captureState == null ? null : Boolean.valueOf("READY".equals(captureState));
		return NetworkMode.HealthSignal.of(ready, captureRequired, divergingContent > 0);
	}

	/** O modo pode deve ser {@code DEGRADED} por esta evidência? A saída do incidente é do humano. */
	public NetworkMode degrade(NetworkMode mode) {
		return mode.degradedBy(signal());
	}

	/** O motivo em português, para o alerta — o código sozinho não diz o que fazer. */
	public String reason() {
		return switch (signal()) {
			case DATA_DIVERGENCE -> divergingContent + " agregado(s) de conteudo divergem entre o que o no serve "
				+ "e a origem";
			case CAPTURE_NOT_READY -> "a instalacao exige captura de alteracoes e o banco nao esta pronto para ela";
			case NONE -> "sem evidencia de degradacao";
		};
	}
}
