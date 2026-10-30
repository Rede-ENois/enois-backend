package com.socialcoisa.rede.emotes.application.port.out;

import java.util.List;

import com.socialcoisa.rede.emotes.domain.Emote;

/**
 * Uma fonte de figurinhas do catalogo (colecao global de um provedor, retrato popular, arquivo
 * embarcado). Cada uma pode falhar sem derrubar as outras: quem junta e o `EmoteCatalogService`.
 */
public interface EmoteSource {

	String name();

	/** Lista as figurinhas desta fonte. Pode lancar: a falha e tratada por quem chamou. */
	List<Emote> load() throws Exception;

	/** Fonte cujo conteudo pode ser servido na hora (arquivo local), sem esperar a rede. */
	default boolean local() {
		return false;
	}
}