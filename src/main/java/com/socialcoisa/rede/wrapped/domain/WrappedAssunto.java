package com.socialcoisa.rede.wrapped.domain;

/**
 * Um assunto que marcou o ano da conta: a expressão normalizada (já dobrada, sem acento, minúscula,
 * como o extrator do Papo Reto entrega) e quantas vezes ela apareceu.
 *
 * <p>O rótulo exibido é a própria expressão; quem apresenta decide capitalizar. Nada de texto de
 * publicação atravessa — só o assunto agregado.</p>
 */
public record WrappedAssunto(String expressao, int ocorrencias) {
}
