package com.socialcoisa.rede.feed.domain;

/**
 * Reações do post e do comentário. Os últimos seis são CONTEXTUAIS: cada um tem situação clara
 * de uso (dica útil, experiência que identifica, lançamento com hype, ajuda que agradece,
 * estética que inspira, gesto que merece respeito) — e a lista vive só aqui e no mapa de
 * emojis do front (texts.posts.reactions.emojis); picker e resumo são genéricos e mostram
 * tudo que vier no mapa.
 */
public enum ReactionType {
	LIKE,
	LOVE,
	LAUGH,
	ROFL,
	SKULL,
	WOW,
	SAD,
	SOB,
	CELEBRATE,
	SUPPORT,
	INSIGHTFUL,
	ANGRY,
	DISGUST,
	SKEPTICAL,
	EYES,
	NOTE,
	FIRE,
	CLAP,
	CLOWN,
	NAILS,
	/** Dica/tutorial/INSIGHT prático: serviu de ferramenta de verdade. */
	USEFUL,
	/** Experiência ou desabafo: "me identifiquei". */
	RELATABLE,
	/** Lançamento, projeto, vaga: empolgação com o que vem. */
	HYPE,
	/** Ajuda ou resposta que resolveu: gratidão. */
	THANKS,
	/** Estética, foto, colagem: a vibe do post. */
	VIBE,
	/** Gesto corajoso ou generoso: respeito. */
	SALUTE
}
