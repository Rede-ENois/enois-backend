package com.socialcoisa.rede.wrapped.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Os limites de um ano da retrospectiva, no fuso oficial da aplicação ({@code America/Sao_Paulo},
 * o mesmo das outras rotinas de calendário do projeto).
 *
 * <p>O corte do briefing é fechado no FIM DE 25/12: o histórico do ano vai de 01/01 00:00 até
 * 25/12 23:59:59.999, em horário local. O intervalo é {@code [inicio, fim)} — um instante exatamente
 * na virada de 26/12 já pertence ao ano seguinte (mesmo processamento) e não a este.</p>
 *
 * <p>A janela de visualização (26/12 00:00 até 01/01 00:00 do ano seguinte) também é local: antes
 * dela o resultado existe mas não aparece; depois, a retrospectiva daquele ano fecha.</p>
 */
public final class WrappedAno {

	public static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");

	private static final DateTimeFormatter ISO_DATA = DateTimeFormatter.ISO_LOCAL_DATE;

	private final int ano;
	private final Instant inicio;
	private final Instant fim;
	private final Instant janelaAbre;
	private final Instant janelaFecha;

	private WrappedAno(int ano) {
		this.ano = ano;
		this.inicio = LocalDate.of(ano, 1, 1).atStartOfDay(FUSO).toInstant();
		this.fim = LocalDate.of(ano, 12, 26).atStartOfDay(FUSO).toInstant();
		this.janelaAbre = fim;
		this.janelaFecha = LocalDate.of(ano + 1, 1, 1).atStartOfDay(FUSO).toInstant();
	}

	public static WrappedAno de(int ano) {
		if (ano < 2000 || ano > 2100) {
			throw new IllegalArgumentException("Ano fora do intervalo suportado: " + ano);
		}
		return new WrappedAno(ano);
	}

	/** A retrospectiva do ano pode ser exibida agora (entre 26/12 e 01/01 do ano seguinte)? */
	public static boolean visivelEm(int ano, Instant agora) {
		WrappedAno w = de(ano);
		return !agora.isBefore(w.janelaAbre) && agora.isBefore(w.janelaFecha);
	}

	public int ano() {
		return ano;
	}

	/** Início do histórico: 01/01 00:00 local (inclusivo). */
	public Instant inicio() {
		return inicio;
	}

	/** Fim do histórico: 26/12 00:00 local (exclusivo), ou seja, 25/12 23:59:59 local. */
	public Instant fim() {
		return fim;
	}

	/** Quando o resultado começa a ser mostrado: 26/12 00:00 local. */
	public Instant janelaAbre() {
		return janelaAbre;
	}

	/** Quando a retrospectiva do ano fecha: 01/01 00:00 local do ano seguinte. */
	public Instant janelaFecha() {
		return janelaFecha;
	}

	public boolean dentroDoHistorico(Instant instante) {
		return !instante.isBefore(inicio) && instante.isBefore(fim);
	}

	/** O ano de calendário local de um instante (a chave de agrupamento mensal/diário da análise). */
	public static int anoDe(Instant instante) {
		return LocalDate.ofInstant(instante, FUSO).getYear();
	}

	/** A data local (o fuso da aplicação), para os "dias ativos". */
	public static LocalDate dataLocal(Instant instante) {
		return LocalDate.ofInstant(instante, FUSO);
	}

	/** O "2026-03-14" de um instante no fuso da aplicação. */
	public static String dataIso(Instant instante) {
		return dataLocal(instante).format(ISO_DATA);
	}
}
