package com.socialcoisa.rede.feed.adapters.in.web;

/**
 * Voto da enquete: a POSICAO da alternativa (0..5). Sem o campo, sem voto — nao ha "nulo".
 */
public record PollVoteRequest(Integer option) {
}
