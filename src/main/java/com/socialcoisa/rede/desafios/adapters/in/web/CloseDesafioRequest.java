package com.socialcoisa.rede.desafios.adapters.in.web;

/**
 * Corpo do POST /desafios/{desafioId}/fechamento: o id da participação que mais representou o
 * desafio. Obrigatório — encerrar sem reconhecimento deixa quem participou sem resposta.
 */
public record CloseDesafioRequest(String destaqueId) {
}
