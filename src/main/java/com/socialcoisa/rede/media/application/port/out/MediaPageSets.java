package com.socialcoisa.rede.media.application.port.out;

import java.util.List;
import java.util.Set;

/**
 * Página que já foi gravada no bucket e espera (ou já teve) a checagem de conteúdo.
 *
 * <p>Quem grava a página é o render do PDF; quem decide sobre ela é a moderação, pelo mesmo ledger
 * das demais imagens. Este port é a ponte entre os dois: a gravação e a varredura perguntam "o que
 * ainda não foi olhado?", o catálogo pergunta "as páginas desta mídia estão limpas?" e a barreira
 * pede a remoção do documento inteiro.</p>
 */
public interface MediaPageSets {

	/**
	 * Pede a checagem das páginas da mídia: só as que existem e ainda não foram olhadas. Fica numa
	 * fila; quem a esvazia é {@code analyseQueued}, fora da transação do render.
	 */
	void checkSoon(String mediaId);

	/** A mídia ainda está na fila: pedida por {@code checkSoon} e não analisada. */
	boolean waiting(String mediaId);

	/** Tudo o que está na fila agora: o mesmo que {@link #waiting} para cada id, lido de uma vez. */
	Set<String> waitingNow();

	/**
	 * Manda analisar agora o que foi pedido por {@code checkSoon}. Chamado fora da transação do
	 * render: cada página custa um download e uma chamada de modelo. Devolve quantas mídias foram
	 * barradas nesta passada — a barreira pode cair aqui, antes de a varredura olhar o lote.
	 */
	int analyseQueued();

	/**
	 * Ids das páginas desta mídia que ainda não têm veredito. Vazio quando não falta nada. Para mídia
	 * que não é PDF, a própria mídia.
	 */
	List<String> uncheckedOf(String mediaId);

	/**
	 * Pede a remoção do documento e das páginas dele depois de uma barreira: folha barrada
	 * contamina o documento inteiro. Quem apaga de fato é o cadastro de mídia com o worker que já
	 * existe; aqui só se pede, e é idempotente.
	 */
	void retire(String mediaId);
}
