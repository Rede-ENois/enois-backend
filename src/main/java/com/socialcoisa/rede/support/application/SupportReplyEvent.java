package com.socialcoisa.rede.support.application;

/**
 * Resposta do painel publicada: espelha o texto na issue do GitHub e avisa o solicitante por e-mail,
 * ambos DEPOIS do commit (melhor esforco).
 *
 * <p>O evento carrega o contato e o assunto do pedido para o e-mail sair sem reler o banco dentro
 * de outra transacao — o envio e SMTP sincrono.</p>
 */
public record SupportReplyEvent(String requestId, String messageId, String adminId, String body,
		String contactEmail, String subject) {
}
