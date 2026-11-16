package com.socialcoisa.rede.ops.monitoring;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Métricas do Éroleplay no painel de monitorabilidade: quantas campanhas existem, quantas estão
 * ativas, por visibilidade e por sistema de regras, além do volume dos dados que o módulo guarda
 * (mesas, lugares, fichas, mensagens, rolagens, entradas do mundo, publicações na biblioteca) e o
 * ritmo de criação de campanhas na janela de 30 dias.
 *
 * <p>Só agregado — NUNCA conteúdo de campanha ou identidade de pessoa: o mesmo recorte do
 * histórico administrativo da auditoria, agora em números. As consultas são contagens sobre
 * índices que já existem (o módulo não cria tabela nem índice novo); o resultado é cacheado por
 * um minuto porque o painel atualiza a cada 30s e nenhuma dessas contas muda nessa escala.</p>
 */
@Service
public class RoleplayMetricsService {

	private final JdbcTemplate jdbc;
	private final Clock clock;
	private final long cacheMs;
	private volatile RoleplayView cached;
	private volatile long cachedAt = Long.MIN_VALUE;

	RoleplayMetricsService(JdbcTemplate jdbc, Clock clock,
			@Value("${rede.monitoring.roleplay-cache-ms:60000}") long cacheMs) {
		this.jdbc = jdbc;
		this.clock = clock;
		this.cacheMs = cacheMs;
	}

	public RoleplayView roleplay() {
		long now = clock.millis();
		RoleplayView atual = cached;
		if (atual != null && now - cachedAt < cacheMs) {
			return atual;
		}
		RoleplayView novo = ler();
		cached = novo;
		cachedAt = now;
		return novo;
	}

	private RoleplayView ler() {
		long total = escalar("select count(*) from roleplay_campaigns");
		long ativas = escalar("select count(*) from roleplay_campaigns where status = 'ACTIVE'");
		long arquivadas = escalar("select count(*) from roleplay_campaigns where status = 'ARCHIVED'");
		long publicas = escalar("select count(*) from roleplay_campaigns where visibility = 'PUBLIC'");
		long porConvite = escalar("select count(*) from roleplay_campaigns where visibility = 'INVITE_ONLY'");
		long privadas = escalar("select count(*) from roleplay_campaigns where visibility = 'PRIVATE'");

		Map<String, Long> porSistema = new LinkedHashMap<>();
		jdbc.query("select game_system, count(*) as total from roleplay_campaigns"
			+ " group by game_system order by total desc, game_system", resultado -> {
				porSistema.put(resultado.getString("game_system"), resultado.getLong("total"));
			});

		Instant agora = clock.instant();
		Instant trintaDias = agora.minus(java.time.Duration.ofDays(30));
		Instant seteDias = agora.minus(java.time.Duration.ofDays(7));
		long campanhas30Dias = escalar(
			"select count(*) from roleplay_campaigns where created_at >= ?", Timestamp.from(trintaDias));
		long campanhas7Dias = escalar(
			"select count(*) from roleplay_campaigns where created_at >= ?", Timestamp.from(seteDias));

		return new RoleplayView(agora.toString(), total, ativas, arquivadas, publicas, porConvite, privadas,
			porSistema, campanhas7Dias, campanhas30Dias,
			escalar("select count(*) from roleplay_campaign_members"),
			escalar("select count(*) from roleplay_join_requests where status = 'PENDING'"),
			escalar("select count(*) from roleplay_tables"),
			escalar("select count(*) from roleplay_table_seats"),
			escalar("select count(*) from roleplay_character_sheets"),
			escalar("select count(*) from roleplay_sheet_templates"),
			escalar("select count(*) from roleplay_campaign_messages"),
			escalar("select count(*) from roleplay_dice_rolls"),
			escalar("select count(*) from roleplay_roll_requests"),
			escalar("select count(*) from roleplay_world_entries"),
			escalar("select count(*) from roleplay_published_sheets"));
	}

	private long escalar(String sql, Object... parametros) {
		Long valor = jdbc.queryForObject(sql, Long.class, parametros);
		return valor == null ? 0L : valor;
	}

	public record RoleplayView(String generatedAt, long campaigns, long activeCampaigns, long archivedCampaigns,
			long publicCampaigns, long inviteOnlyCampaigns, long privateCampaigns, Map<String, Long> bySystem,
			long campaignsLast7Days, long campaignsLast30Days, long members, long pendingJoinRequests,
			long tables, long seats, long characterSheets, long sheetTemplates, long chatMessages,
			long diceRolls, long rollRequests, long worldEntries, long publishedSheets) {
	}
}
