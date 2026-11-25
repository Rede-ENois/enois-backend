package com.socialcoisa.rede.wrapped.domain;

/**
 * As contagens do ano, calculadas no banco sobre o histórico real da conta.
 *
 * <p>Nada aqui é inventado: cada número vem de uma agregação SQL filtrada por autor/data e por
 * status ativo (conteúdo removido não conta). {@code publicacoes} e {@code comentariosProprios} são
 * publicação própria; {@code reacoesFeitas}, {@code comentariosFeitos}, {@code compartilhamentos},
 * {@code reposts}, {@code votos} e {@code salvos} são o que a conta fez; {@code interacoesRecebidas}
 * é o que outras pessoas fizeram nas publicações dela.</p>
 *
 * @param mesMaisAtivo 1 a 12 (o mês de pico), ou {@code null} sem atividade
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
		int votosFeitos,
		int salvos,
		int comunidadesParticipadas,
		int diasAtivos,
		Integer mesMaisAtivo) {

	public int interacoesRecebidas() {
		return reacoesRecebidas + comentariosRecebidos + compartilhamentosRecebidos + repostsRecebidos;
	}

	public int interacoesFeitas() {
		return reacoesFeitas + comentariosFeitos + compartilhamentosFeitos + repostsFeitos + votosFeitos
				+ salvos;
	}

	/** Sem nenhuma atividade registrada no ano: não há a que dar retrospectiva. */
	public boolean semAtividade() {
		return publicacoes == 0 && comentariosProprios == 0 && interacoesFeitas() == 0
				&& interacoesRecebidas() == 0;
	}
}
