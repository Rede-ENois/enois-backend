package com.socialcoisa.rede.moderation.application.port.out;

import java.util.List;

/**
 * As imagens anexadas ao alvo denunciado, já decodificadas e reduzidas, para a triagem OLHAR.
 *
 * <p>Existe porque o texto do post não é a prova: um post de uma linha com a foto explícita anexada
 * não tem nada para o modelo julgar no texto, e foi assim que conteúdo sexual passou sem barreira.
 * A leitura é best-effort e limitada — alvo sem mídia, imagem em formato que não decodifica ou
 * download que falha devolvem lista vazia, e a decisão cai na revisão humana em vez de fingir que
 * olhou.</p>
 */
public interface ReportedMediaReader {

	/** Até {@code limit} imagens do alvo. Vazio quando não há imagem legível. */
	List<byte[]> imagesOf(String targetType, String targetId, int limit);
}
