package com.socialcoisa.rede.termometro.domain;

/**
 * As emoções do Termômetro: as caixas em que o léxico classifica o que a comunidade pubblica.
 *
 * O nome é de clima, não de diagnóstico — o painel nunca diz como UMA pessoa está, diz como o
 * conjunto soa nesta janela. Alegre e Pesada medem o tom geral; Cansada e Precisando capturam
 * desgaste e pedido de ajuda (o que nos Desabafos aparece como "não aguento mais" e "alguém
 * me ajuda"); Curiosa e Celebrando capturam movimento e festa.
 */
public enum Emocao {

	/** Tom leve e positivo: bom dia, que legal, adorei, feliz assim. */
	ALEGRE,

	/** Tom pesado ou tenso: discussão, raiva, injustiça, cansaço de briga. */
	PESADA,

	/** Desgaste: sem energia, exausta, não aguento, esgotada. */
	CANSADA,

	/** Pedido de ajuda ou acolhida: alguém me ajuda, tô mal, precisa de apoio. */
	PRECISANDO,

	/** Movimento de aprender e perguntar: como faz, alguém sabe, dica de. */
	CURIOSA,

	/** Comemoração: consegui, passei, bora, comemorar. */
	CELEBRANDO
}
