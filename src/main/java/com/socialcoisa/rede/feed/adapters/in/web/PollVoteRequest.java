package com.socialcoisa.rede.feed.adapters.in.web;

import java.util.List;

/**
 * Voto da enquete: a POSICAO da alternativa (0..5) na escolha unica, ou a lista de posicoes na
 * multipla escolha. O servico decide qual campo usar pela configuracao da enquete; sem campo
 * valido, sem voto — nao ha "nulo".
 */
public record PollVoteRequest(Integer option, List<Integer> options) {
}
