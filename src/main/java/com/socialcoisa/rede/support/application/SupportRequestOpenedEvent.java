package com.socialcoisa.rede.support.application;

/**
 * Pedido de suporte recem-gravado. Os efeitos externos (espelho no GitHub e aviso por e-mail) nascem
 * DEPOIS do commit: o envio de e-mail e SMTP sincrono e nao pode segurar a conexao do banco.
 *
 * <p>O evento carrega o que o aviso precisa (assunto, mensagem e e-mail de contato), para o listener
 * nao ter de reler o pedido so para montar o e-mail.</p>
 */
public record SupportRequestOpenedEvent(String requestId, String subject, String message, String contactEmail) {
}
