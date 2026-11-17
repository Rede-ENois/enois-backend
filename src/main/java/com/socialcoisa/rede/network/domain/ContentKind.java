package com.socialcoisa.rede.network.domain;

/**
 * Os tipos de conteúdo que um nó de borda pode entregar. A lista é a allowlist: tipo fora daqui
 * não tem versão, não é servido pela borda e não é cacheado no nó.
 *
 * <p>O critério é público por natureza e compartilhado entre leitores (docs/rede-distribuida/05 §2):
 * a projeção pública de perfil e a vitrine de comunidade. Post, comentário, mensagem e qualquer
 * coisa que dependa de quem está lendo ficam de fora — a borda não decide autorização de usuário,
 * então só pode ter o que é igual para todo mundo.</p>
 *
 * <p>Tipo desconhecido na rota vira 404 antes de qualquer consulta: o nó novo que inventar um tipo
 * não recebe "o conteúdo que der" nem consulta o banco.</p>
 */
public enum ContentKind {

	/** Projeção pública de perfil, por @ (o que {@code GET /users/{username}} devolve). */
	PROFILE,

	/** Vitrine de comunidade por id (o que {@code GET /communities/{id}} devolve). */
	COMMUNITY;

	/** O tipo como o nó o recebe; nulo ou desconhecido devolve <b>vazio</b>, nunca um padrão. */
	public static java.util.Optional<ContentKind> parse(String raw) {
		if (raw == null || raw.isBlank()) {
			return java.util.Optional.empty();
		}
		try {
			return java.util.Optional.of(valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT)));
		}
		catch (IllegalArgumentException unknown) {
			return java.util.Optional.empty();
		}
	}
}
