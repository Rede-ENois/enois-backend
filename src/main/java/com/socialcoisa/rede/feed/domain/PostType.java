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
	BUSINESS
}
