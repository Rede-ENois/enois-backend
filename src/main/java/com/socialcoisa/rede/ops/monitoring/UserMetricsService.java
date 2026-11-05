package com.socialcoisa.rede.ops.monitoring;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Contas da comunidade no painel de monitorabilidade: quantas existem, quantas nascem por dia, o
 * ritmo de crescimento e a projeção.
 *
 * <p>Duas consultas baratas (o total e as datas de criação da janela) com cache de um minuto: o
 * painel atualiza a cada 30s e a contagem de contas não muda nessa escala — repetir a varredura a
 * cada polling de cada admin só gastaria conexão do pool.</p>
 *
 * <p>As datas são agrupadas no fuso de São Paulo, e não em UTC: um cadastro às 22h de Brasília
 * pertence ao dia de hoje, não ao dia seguinte.</p>
 */
@Service
public class UserMetricsService {

	private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
	private static final int WINDOW_DAYS = 30;
	private static final long CACHE_MS = 60_000;
	private static final int[] PROJECTION_DAYS = { 7, 30, 90 };

	private final JdbcTemplate jdbc;
	private final Clock clock;
	private volatile UsersView cached;
	private volatile long cachedAt = Long.MIN_VALUE;

	UserMetricsService(JdbcTemplate jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	public UsersView users() {
		long now = clock.millis();
		UsersView atual = cached;
		if (atual != null && now - cachedAt < CACHE_MS) {
			return atual;
		}
		UsersView calculado = compute();
		cached = calculado;
		cachedAt = now;
		return calculado;
	}

	private UsersView compute() {
		Instant agora = clock.instant();
		LocalDate hoje = LocalDate.ofInstant(agora, ZONE);
		LocalDate primeiroDia = hoje.minusDays(WINDOW_DAYS - 1);
		Instant inicioJanela = primeiroDia.atStartOfDay(ZONE).toInstant();

		long total = jdbc.queryForObject("select count(*) from users", Long.class);
		List<Timestamp> criados = jdbc.queryForList("select created_at from users where created_at >= ?",
			Timestamp.class, Timestamp.from(inicioJanela));

		Map<LocalDate, Long> porDia = new HashMap<>();
		for (Timestamp ts : criados) {
			if (ts == null) {
				continue;
			}
			porDia.merge(LocalDate.ofInstant(ts.toInstant(), ZONE), 1L, Long::sum);
		}

		long[] novosPorDia = new long[WINDOW_DAYS];
		for (int dia = 0; dia < WINDOW_DAYS; dia++) {
			novosPorDia[dia] = porDia.getOrDefault(primeiroDia.plusDays(dia), 0L);
		}
		// aPartirDe[i] = contas criadas do dia i até hoje; o total de fim do dia d é
		// total menos quem nasceu depois dele.
		long[] aPartirDe = new long[WINDOW_DAYS + 1];
		for (int dia = WINDOW_DAYS - 1; dia >= 0; dia--) {
			aPartirDe[dia] = aPartirDe[dia + 1] + novosPorDia[dia];
		}

		List<DailyPoint> serie = new ArrayList<>(WINDOW_DAYS);
		for (int dia = 0; dia < WINDOW_DAYS; dia++) {
			long novos = novosPorDia[dia];
			long totalFimDoDia = total - aPartirDe[dia + 1];
			long base = totalFimDoDia - novos;
			serie.add(new DailyPoint(primeiroDia.plusDays(dia).toString(), novos, totalFimDoDia,
				percentual(novos, base)));
		}

		long novosHoje = novosPorDia[WINDOW_DAYS - 1];
		long novosSeteDias = 0;
		for (int dia = WINDOW_DAYS - 7; dia < WINDOW_DAYS; dia++) {
			novosSeteDias += novosPorDia[dia];
		}
		long novosTrintaDias = aPartirDe[0];
		double mediaDiariaSete = novosSeteDias / 7.0;
		double mediaDiariaTrinta = novosTrintaDias / (double) WINDOW_DAYS;

		List<Projection> projecoes = new ArrayList<>(PROJECTION_DAYS.length);
		for (int dias : PROJECTION_DAYS) {
			// Projeção linear sobre o ritmo da última semana: o mês inteiro dilui uma semana de
			// lançamento e esconde a tendência atual; 90 dias com o ritmo de 30 também mente.
			long projetado = Math.max(total, total + Math.round(mediaDiariaSete * dias));
			projecoes.add(new Projection(dias, projetado, percentual(projetado - total, total)));
		}

		return new UsersView(agora.toString(), total, novosHoje, novosSeteDias, novosTrintaDias,
			percentual(novosHoje, total - novosHoje), composto(total, total - novosSeteDias, 7),
			composto(total, total - novosTrintaDias, WINDOW_DAYS), arredonda(mediaDiariaSete),
			arredonda(mediaDiariaTrinta), serie, projecoes);
	}

	private static double percentual(long parte, long base) {
		return base <= 0 ? 0 : arredonda(parte * 100.0 / base);
	}

	/** Ritmo diário composto: qual foi a taxa média por dia que levou a base ao total de hoje. */
	private static double composto(long total, long base, int dias) {
		if (base <= 0 || dias <= 0 || total <= base) {
			return 0;
		}
		return arredonda((Math.pow((double) total / base, 1.0 / dias) - 1) * 100.0);
	}

	private static double arredonda(double valor) {
		return Math.round(valor * 100.0) / 100.0;
	}

	/** Um dia da série: quantos entraram, o total acumulado ao fim dele e a variação sobre a base. */
	public record DailyPoint(String date, long newUsers, long total, double growthPercent) {
	}

	/** Projeção do total de contas daqui a N dias, no ritmo da última semana. */
	public record Projection(int days, long total, double growthPercent) {
	}

	public record UsersView(String generatedAt, long total, long newToday, long newLast7Days,
			long newLast30Days, double growthTodayPercent, double growth7DaysPercent,
			double growth30DaysPercent, double averageDailyNew7Days, double averageDailyNew30Days,
			List<DailyPoint> series, List<Projection> projections) {
	}
}