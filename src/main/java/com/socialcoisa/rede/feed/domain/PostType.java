package com.socialcoisa.rede.feed.domain;

/**
 * Categorias de publicacao.
 *
 * ARTICLE e categoria propria: o corpo dele (ate 4.000 caracteres) fica na tabela `articles`, e o
 * `content` do post guarda titulo/resumo (ver PublishPostService). PAGE e o post com uma mini pagina
 * em HTML sanitizada no servidor. Os demais tipos sao texto curto, como sempre foram.
 */
public enum PostType {
	INSIGHT,
	ARTICLE,
	QUESTION,
	PROJECT,
	OPPORTUNITY,
	/** Mini pagina em HTML (colada pelo autor e sanitizada no backend). */
	PAGE,
	NEWS,
	TUTORIAL,
	REVIEW,
	/** Post divertido: piada, memória, coisa leve — texto curtinho. */
	DIVERTIDO,
	/** Momento: some sozinho em 24 horas (o texto fica em 500). */
	MOMENT,
	/**
	 * Instante: efêmero curto — some sozinho em 1 hora a partir da publicação efetiva (o texto fica
	 * em 500, igual ao Momento). Aparece nos feeds comuns como qualquer post; o prazo é a única
	 * diferença.
	 */
	INSTANTE,
	/** Enquete: o titulo fica no content (ate 400) e as alternativas/votos em tabelas proprias. */
	POLL,
	/**
	 * Ponteiro de um post de blog: o conteudo mora no blog do autor, nao no feed. Entra so no "Para
	 * voce" de quem se inscreveu no blog (nunca no "Tudo") e leva a tag "blog".
	 */
	BLOG,
	/**
	 * Publicação corporativa/profissional: assinada pela identidade profissional da conta
	 * (AuthorIdentity.CORPORATE) e exibida apenas no feed corporativo e no perfil profissional —
	 * nunca no feed Tudo nem nos feeds sociais. A regra vive no backend (ver GetFeedService).
	 */
	BUSINESS,
	/** Reclamação: desabafo/denúncia de problema (pedido do painel de suporte). Texto padrão. */
	COMPLAINT;

	/**
	 * Tipos efêmeros nascem com prazo de vida e se apagam sozinhos (o worker de expiração cobre os
	 * dois). Qualquer novo tipo efêmero entra aqui E em {@link #ephemeralDuration()} — as duas
	 * respostas têm de ser consistentes entre si.
	 */
	public boolean isEphemeral() {
		return this == MOMENT || this == INSTANTE;
	}

	/** Prazo de vida do tipo efêmero, contado da publicação efetiva. Nunca chamado em tipo fixo. */
	public java.time.Duration ephemeralDuration() {
		return switch (this) {
			case MOMENT -> java.time.Duration.ofHours(24);
			case INSTANTE -> java.time.Duration.ofHours(1);
			default -> throw new IllegalStateException("Tipo " + this + " não é efêmero.");
		};
	}
}
