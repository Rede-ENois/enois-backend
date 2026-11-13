package com.socialcoisa.rede.network.application.port.out;

import com.socialcoisa.rede.network.domain.NetworkTask;

/**
 * Conferência do veredicto que um nó {@code NODE_EDGE} devolveu para uma tarefa {@code
 * EDGE_DELIVER}.
 *
 * <p>Existe como porta pelo mesmo motivo da conferência de mídia: a regra de aceite só vale
 * para este tipo de tarefa. Os demais continuam aceitando o resultado declarado pelo nó, e o
 * scheduler pergunta à implementação quando ela existe — nenhum outro fluxo muda.</p>
 *
 * <p>O que se confere aqui é uma <b>declaração de capacidade</b>, não um arquivo: o nó diz
 * "consigo entregar este conteúdo agora, nesta versão". A declaração só passa se o que ele diz
 * ter for o que ele mesmo confirmou ter ({@code POST /network/content/.../synced}): veredicto
 * positivo sem confirmação é invenção, e versão ou hash que não é o confirmado é declaração
 * sem lastro.</p>
 */
public interface EdgeDeliverVerifier {

	/**
	 * O nó declarou sucesso. Devolve {@code ok=false} quando o veredicto não corresponde ao que o
	 * nó mesmo confirmou na origem.
	 */
	Verification verify(NetworkTask task, String output);

	/**
	 * @param ok        veredicto aceito
	 * @param status    status devolvido ao nó (vai para {@code ResultOutcome})
	 * @param reason    motivo legível, para o nó e para o log
	 * @param retryable quando recusado, se a mesma tarefa pode ser tentada de novo
	 */
	record Verification(boolean ok, String status, String reason, boolean retryable) {

		public static Verification accepted() {
			return new Verification(true, null, null, false);
		}

		public static Verification refused(String status, String reason, boolean retryable) {
			return new Verification(false, status, reason, retryable);
		}
	}
}