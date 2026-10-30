package com.socialcoisa.rede.feed.application.port.in;

/**
 * Quarentena de moderação: tira um post do ar sem apagar e sem punir a conta.
 *
 * É o passo de PRECAUÇÃO — o post some do feed, do perfil e do detalhe enquanto uma pessoa do time
 * decide. {@link #release} devolve o conteúdo ao ar se a revisão concluir que não havia violação.
 */
public interface ModerationQuarantinePostUseCase {

	/**
	 * Marca o post como sob revisão. Não apaga, não pune, não avisa: quem chama é que decide o
	 * aviso ao autor.
	 *
	 * @param actorId quem agiu (time ou automação) — fica no registro de auditoria
	 */
	boolean quarantine(String actorId, String postId, String reason);

	/** Revisão humana liberou o post: volta ao ar exatamente como estava. */
	boolean release(String actorId, String postId);
}