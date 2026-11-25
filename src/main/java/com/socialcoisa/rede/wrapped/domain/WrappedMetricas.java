package com.socialcoisa.rede.wrapped.domain;

/**
 * As contagens do ano, calculadas no banco sobre o histórico real da conta.
 *
 * <p>Nada aqui é inventado: cada número vem de uma agregação SQL filtrada por autor/data e por
 * status ativo (conteúdo removido não conta). {@code publicacoes} e {@code comentarios} são
 * publicação própria; {@code reacoesFeitas}, {@code comentariosFeitos} e {@code compartilhamentos}
 * em conteúdo de quem NÃO é a própria conta são interação; {@code interacoesRecebidas} é o que
 * outras pessoas fizeram nas publicações dela.</p>
 */
public record WrappedMetricas(
		int publicacoes,
		int comentariosProprios,
		int reacoesRecebidas,
		int comentariosRecebidos,
		int compartilhamentosRecebidos,
		int repostsRecebidos,
		int reacoesFeitas,
		int comentariosFeitos,
		int compartilhamentosFeitos,
		int repostsFeitos,
		int salvos,
		int comunidadesParticipadas,
		int diasAtivos) {

	public int interacoesRecebidas() {
		return reacoesRecebidas + comentariosRecebidos + compartilhamentosRecebidos + repostsRecebidos;
	}

	public int interacoesFeitas() {
		return reacoesFeitas + comentariosFeitos + compartilhamentosFeitos + repostsFeitos + salvos;
	}

	/** Sem nenhuma atividade registrada no ano: não há a que dar retrospectiva. */
	public boolean semAtividade() {
		return publicacoes == 0 && comentariosProprios == 0 && interacoesFeitas() == 0
				&& interacoesRecebidas() == 0;
	}
}
