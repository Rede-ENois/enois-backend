package com.socialcoisa.rede.eroleplay.domain;

/**
 * O canal de conversa dentro da campanha.
 *
 * <p>Dois canais, e a diferença entre eles é SEMÂNTICA, não técnica: em IC a pessoa fala na voz
 * do personagem e o que diz é cena; em OOC fala como si mesma e o que diz é coordenação. A
 * campanha não precisa de uma tabela de canais configuráveis para isso — o par (campanha, canal)
 * é o endereço, e a mensagem nasce e vive dentro da campanha.</p>
 */
public enum ChatChannel {

	/** Na voz do personagem: o que se diz aqui é a cena. */
	IN_CHARACTER,

	/** Fora da cena: coordenação da mesa, entre as pessoas reais. */
	OUT_OF_CHARACTER;

	/** O valor que a API aceita na criação e na leitura. */
	public static ChatChannel of(String value) {
		if ("IC".equalsIgnoreCase(value)) {
			return IN_CHARACTER;
		}
		if ("OOC".equalsIgnoreCase(value)) {
			return OUT_OF_CHARACTER;
		}
		throw new IllegalArgumentException("canal invalido: " + value);
	}

	/** O identificador estável usado na API e na persistência. */
	public String wire() {
		return this == IN_CHARACTER ? "IC" : "OOC";
	}
}
