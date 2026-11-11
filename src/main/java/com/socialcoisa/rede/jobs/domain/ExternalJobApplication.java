package com.socialcoisa.rede.jobs.domain;

import java.time.Instant;

/**
 * Candidatura a uma vaga EXTERNA enviada pelo Énóis: o e-mail que saiu da plataforma para a caixa
 * que o anúncio publica.
 *
 * <p>Não é a candidatura interna ({@link JobApplication}): não há triagem dentro do Énóis, não há
 * estado movido pelo empregador e não há formulário. O que fica registrado é o comprovante — para
 * onde foi, com que assunto, se o currículo foi anexado de verdade — e nada do documento em si.</p>
 *
 * <p>`resumeAttached` é o campo que impede a mentira: só é `true` quando o arquivo foi baixado do
 * CDN e anexado à mensagem. Quando o download falha, o envio acontece sem anexo e o registro diz
 * isso — o app mostra o aviso em vez de fingir que o currículo foi junto.</p>
 */
public record ExternalJobApplication(String id, String externalJobId, String userId, String recipientEmail,
		String subject, String body, boolean resumeAttached, String resumeName, String status, String error,
		Instant appliedAt) {

	/** Reservada: a linha existe e o e-mail está a caminho. Serve para barrar a segunda tentativa. */
	public static final String PENDING = "PENDING";
	public static final String SENT = "SENT";
	public static final String FAILED = "FAILED";

	public static ExternalJobApplication pending(String id, String externalJobId, String userId,
			String recipientEmail, String subject, String body, String resumeName, Instant appliedAt) {
		return new ExternalJobApplication(id, externalJobId, userId, recipientEmail, subject, body, false,
			resumeName, PENDING, null, appliedAt);
	}

	public static ExternalJobApplication sent(String id, String externalJobId, String userId, String recipientEmail,
			String subject, String body, boolean resumeAttached, String resumeName, Instant appliedAt) {
		return new ExternalJobApplication(id, externalJobId, userId, recipientEmail, subject, body, resumeAttached,
			resumeName, SENT, null, appliedAt);
	}

	public static ExternalJobApplication failed(String id, String externalJobId, String userId, String recipientEmail,
			String subject, String body, boolean resumeAttached, String resumeName, String error, Instant appliedAt) {
		return new ExternalJobApplication(id, externalJobId, userId, recipientEmail, subject, body, resumeAttached,
			resumeName, FAILED, error, appliedAt);
	}

	public boolean delivered() {
		return SENT.equals(status);
	}
}
