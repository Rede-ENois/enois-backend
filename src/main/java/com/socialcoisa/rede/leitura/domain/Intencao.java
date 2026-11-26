package com.socialcoisa.rede.leitura.domain;

import java.util.Locale;

/**
 * A intenção que o texto carrega — o que a pessoa QUER com aquilo, não a palavra que ela usou.
 *
 * <p>Lida por um modelo local (endpoint de inferência do projeto) quando disponível; sem ele, a
 * leitura degrada para a extração determinística e a intenção fica {@link #DESCONHECIDA}. O valor
 * é sempre um destes — nada de texto livre vindo do modelo entra como intenção.</p>
 */
public enum Intencao {

	/** Opinião, posicionamento, ponto de vista. */
	OPINIAO,
	/** Dúvida, pedido de ajuda, pergunta. */
	PERGUNTA,
	/** Anúncio, divulgação, convite, oferta. */
	ANUNCIO,
	/** Desabafo, relato emocional, despejo. */
	DESABAFO,
	/** Compartilhamento de notícia, informação, conquista. */
	NOTICIA,
	/** Ensino, explicação, tutorial. */
	EXPLICACAO,
	/** Conversa fiada, humor, troca social. */
	CONVERSA,
	/** Não foi possível ler (modelo indisponível ou texto insuficiente). */
	DESCONHECIDA;

	public static Intencao parse(String raw) {
		if (raw == null || raw.isBlank()) {
			return DESCONHECIDA;
		}
		try {
			return Intencao.valueOf(raw.trim().toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException fora) {
			return DESCONHECIDA;
		}
	}

	/** A intenção é de conteúdo (fala do que o texto trata), não de conversa social? */
	public boolean nomeiaConversa() {
		return this != CONVERSA && this != DESCONHECIDA;
	}
}
