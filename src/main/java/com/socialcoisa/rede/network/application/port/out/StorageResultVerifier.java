package com.socialcoisa.rede.network.application.port.out;

import com.socialcoisa.rede.network.domain.NetworkTask;

/**
 * Conferência do resultado que um nó de armazenamento devolve para {@code OBJECT_PUT}.
 *
 * <p>Existe como porta pelo mesmo motivo da conferência de mídia e da entrega de borda: a regra de
 * aceite só vale para este tipo de tarefa. Os demais continuam aceitando o declarado pelo nó.</p>
 *
 * <h2>Por que isto existe</h2>
 * <p>Toda outra tarefa da rede é aceita pela <b>declaração</b> do nó porque o nó é a autoridade
 * sobre o resultado: ele transcodificou, ele entregou. Storage é o único tipo em que o Control
 * Plane é dono da medida — ele mesmo calcula tamanho e SHA-256 ao receber o corpo. Aceitar a
 * declaração de um {@code OBJECT_PUT} seria aceitar um número que o CP pode contradizer com o que
 * ele já mediu.</p>
 *
 * <p>E o dano é concreto: um {@code OBJECT_PUT} ACEITO sem que nada tenha sido gravado vira um
 * objeto "replicado" que não existe no nó. O {@code /storage/scrub} mostrava a cópia como
 * confirmada, e o healing nunca a procuraria — o catálogo prometeria uma durabilidade inventada.</p>
 */
public interface StorageResultVerifier {

	/**
	 * O nó declarou que gravou. Devolve {@code ok=false} quando o objeto não tem cópia confirmada
	 * deste nó ou quando as medidas declaradas não são as médias.
	 *
	 * @param task   a tarefa {@code OBJECT_PUT} concluída
	 * @param output o {@code output} que o nó mandou (JSON codificado como texto, como em todo o resto)
	 */
	Verification verify(NetworkTask task, String output);

	/**
	 * @param ok        veredicto aceito
	 * @param status    status devolvido ao nó (vai para {@code ResultOutcome}); {@code null} quando ok
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