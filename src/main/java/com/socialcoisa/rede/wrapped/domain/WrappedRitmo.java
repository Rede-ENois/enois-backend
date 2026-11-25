package com.socialcoisa.rede.wrapped.domain;

import java.util.List;

/**
 * O ritmo do ano: em que dia da semana, hora e mês a conta esteve mais ativa, e a evolução mês a mês.
 *
 * <p>Os eixos vêm do {@code created_at} das interações (o mesmo conjunto de fatos da análise), no
 * fuso da aplicação. {@code null} nos picos significa "não houve atividade naquele eixo" — a view
 * esconde a seção, nunca mostra 0 fingindo dado.</p>
 *
 * @param porMes     12 posições (janeiro a dezembro), em ordem; a view desenha o gráfico
 * @param porDiaSemana 7 posições (domingo a sábado)
 * @param porHora    24 posições (0h a 23h)
 */
public record WrappedRitmo(
		Integer diaSemanaMaisAtivo,
		Integer horaMaisAtiva,
		Integer mesMaisAtivo,
		List<Integer> porMes,
		List<Integer> porDiaSemana,
		List<Integer> porHora) {
}
