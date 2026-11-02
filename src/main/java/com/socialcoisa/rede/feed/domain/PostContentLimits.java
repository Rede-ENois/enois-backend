package com.socialcoisa.rede.feed.domain;

import java.util.Map;

/**
 * Quanto texto cada TIPO de post aceita.
 *
 * O limite e por tipo porque o tipo e o formato da conversa: um divertido e curto, um tutorial e
 * um texto de leitura. A duvida fica no padrao (1.200) e nao mais em 400: pergunta tecnica precisa
 * de contexto e de um trecho de codigo ou de log, e 400 empurrava a pessoa a perguntar mal.
 *
 * A regra e a mesma na criacao e na edicao (as duas leem daqui) e a tela mostra exatamente este
 * numero — se o campo diz 400, o servidor recusa em 401.
 */
public final class PostContentLimits {

	private static final int PADRAO = 1_200;

	private static final Map<PostType, Integer> POR_TIPO = Map.of(
		PostType.DIVERTIDO, 400,
		PostType.MOMENT, 500,
		// Enquete: o titulo da enquete (as alternativas têm limite proprio no PublishPostService).
		PostType.POLL, 400,
		PostType.REVIEW, 600,
		PostType.PROJECT, 2_000,
		PostType.NEWS, 2_000,
		PostType.OPPORTUNITY, 1_500,
		PostType.TUTORIAL, 5_000,
		// Conteúdo profissional: apresentação de serviço/projeto — par com NEWS/PROJECT.
		PostType.BUSINESS, 2_000);

	private PostContentLimits() {
	}

	public static int of(PostType type) {
		return type == null ? PADRAO : POR_TIPO.getOrDefault(type, PADRAO);
	}
}
