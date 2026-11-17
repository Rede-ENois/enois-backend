package com.socialcoisa.rede.eroleplay.domain;

/**
 * O tipo da entidade do mundo. O World Builder registra o cenário, não o sistema de regras:
 * os cinco tipos cobrem o que qualquer campanha precisa — o que muda por sistema é a FICHA
 * (V294), nunca a lore. Um tipo sem adaptador, um texto de "lore do sistema comercial": nada
 * disso entra aqui.
 */
public enum WorldKind {

	/** Um lugar do mundo: cidade, masmorra, taverna, floresta. */
	LOCALE,

	/** Um personagem do cenário (NPC): quem a mesa encontra, mas não controla. */
	CHARACTER,

	/** Um item: relíquia, chave, poção, mapa. */
	ITEM,

	/** Uma pista, segredo ou rumor — o fio da trama que os jogadores puxam. */
	PLOT,

	/** Lore pura: história, cosmologia, regra do mundo. */
	LORE;

	/** O valor que a API aceita e o que fica gravado. */
	public static WorldKind of(String value) {
		for (WorldKind kind : values()) {
			if (kind.name().equalsIgnoreCase(value)) {
				return kind;
			}
		}
		throw new IllegalArgumentException("kind invalido: " + value);
	}
}
