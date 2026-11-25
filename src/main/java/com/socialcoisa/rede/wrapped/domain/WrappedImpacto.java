package com.socialcoisa.rede.wrapped.domain;

/**
 * O impacto na comunidade: números que falam de contribuição, não de competição. A credibilidade
 * entra só como CONTEXTO (a mesma coluna {@code users.reputation} da fila), nunca como ranking.
 *
 * @param publicacoesComConversa  publicações próprias que receberam pelo menos um comentário
 * @param comentariosComReacao    comentários próprios que receberam pelo menos uma reação
 * @param interacoesRecebidas     interações que outras pessoas fizeram no conteúdo da conta
 * @param comunidades             comunidade(s) em que a conta participou no ano (a contagem real)
 * @param credibilidade           a credibilidade atual (0 a 1000), como dado de contexto
 */
public record WrappedImpacto(
		int publicacoesComConversa,
		int comentariosComReacao,
		int interacoesRecebidas,
		int comunidades,
		int credibilidade) {
}
