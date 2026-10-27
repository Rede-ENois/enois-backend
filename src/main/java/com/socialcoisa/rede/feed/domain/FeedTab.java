package com.socialcoisa.rede.feed.domain;

public enum FeedTab {
	/** Tudo: cronológico, com filtro opcional por PostType. */
	ALL,
	/** Para mim: amigos + posts que comentou/reagiu. */
	FOR_YOU,
	/** Ainda não vi: posts sem interação do usuário. */
	UNSEEN,
	/** Em alta: mais reagidos nas últimas 48h. */
	TRENDING,

	// Legado — o frontend antigo ainda pode enviar. Mapeados no service.
	FOLLOWING, DISCUSSIONS, RECENT,
	PROJECTS, NEWS, TUTORIALS, OPPORTUNITIES, REVIEWS, FUN
}
