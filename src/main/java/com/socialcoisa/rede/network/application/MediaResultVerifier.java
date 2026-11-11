package com.socialcoisa.rede.network.application;

import com.socialcoisa.rede.network.domain.NetworkTask;

/**
 * Conferência do que um nó {@code NODE_MEDIA} devolveu para uma tarefa {@link
 * com.socialcoisa.rede.network.domain.TaskType#MEDIA_TRANSCODE}.
 *
 * <p>Existe como porta porque a regra de aceite só vale para este tipo de tarefa: as demais
 * continuam aceitando o resultado declarado pelo nó, como antes. O scheduler pergunta à
 * implementação e obedece à resposta — nenhum outro fluxo muda.</p>
 */
public interface MediaResultVerifier {

	/**
	 * O nó declarou sucesso. Devolve {@code accepted=false} quando o que ele declarou não confere
	 * com o artefato que ele entregou; nesse caso a tarefa não conclui.
	 */
	Verification verify(NetworkTask task, String output);

	/** O nó declarou falha: guarda o motivo no job, para o painel não perder o rastro. */
	void recordFailure(NetworkTask task, String error, boolean retryable);

	/**
	 * @param applied resultado aceito
	 * @param status  status devolvido ao nó (vai para {@code ResultOutcome})
	 * @param reason  motivo legível, para o nó e para o log
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