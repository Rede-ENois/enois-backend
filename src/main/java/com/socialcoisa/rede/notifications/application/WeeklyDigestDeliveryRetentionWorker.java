package com.socialcoisa.rede.notifications.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.IsoFields;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.notifications.application.port.out.WeeklyDigestRepository;

/**
 * Descarta as semanas de {@code digest_deliveries} que já passaram da janela de retenção.
 *
 * <p>A tabela guarda uma linha por pessoa por semana ISO — é o que impede dois resumos na mesma
 * semana. Nenhuma rotina nunca removia nada, então o livro-caixa crescia para sempre: 52 linhas
 * por pessoa por ano, e a única linha que participa de alguma decisão é a da semana corrente (a
 * consulta de elegibilidade sempre pergunta pela semana atual). Com 50 mil pessoas são 2,6 milhões
 * de linhas por ano, das quais nenhuma é lida.</p>
 *
 * <p>Regras, por desenho:</p>
 * <ul>
 * <li>A janela é de semanas, não de dias: a unidade da tabela é a semana, e o lote da remoção é
 * uma semana inteira (uma rodada de envio). Oito semanas por padrão — folga para investigar "por
 * que não recebi" e para uma rodada que falhou, curto o bastante para a tabela não virar arquivo.</li>
 * <li>Nada dentro da janela é tocado, e a semana corrente nunca é candidata: o corte é
 * {@code hoje - semanas}, e a comparação é estritamente menor.</li>
 * <li>Cada rodada apaga um lote limitado de semanas e o próximo ciclo continua, para não segurar a
 * tabela numa transação longa (mesmo formato das outras retenções). O índice
 * {@code (week_key)} (V273) transforma a varredura em faixa: medido em MariaDB 11.4 com 2,08
 * milhões de linhas, de 2.080.054 leituras de linha com filesort para nenhuma leitura de tabela.</li>
 * </ul>
 */
@Component
public class WeeklyDigestDeliveryRetentionWorker {

	private static final Logger LOG = LoggerFactory.getLogger(WeeklyDigestDeliveryRetentionWorker.class);

	private final WeeklyDigestRepository digests;
	private final Clock clock;
	private final ZoneId zone;
	private final boolean enabled;
	private final int weeks;
	private final int batchSize;

	public WeeklyDigestDeliveryRetentionWorker(WeeklyDigestRepository digests, Clock clock,
			@Value("${rede.digest.retention.zone:${rede.digest.zone:America/Sao_Paulo}}") String zone,
			@Value("${rede.digest.retention.enabled:true}") boolean enabled,
			@Value("${rede.digest.retention.weeks:8}") int weeks,
			@Value("${rede.digest.retention.batch-size:4}") int batchSize) {
		this.digests = digests;
		this.clock = clock;
		this.zone = ZoneId.of(zone);
		this.enabled = enabled;
		this.weeks = Math.max(1, weeks);
		this.batchSize = Math.max(1, batchSize);
	}

	@Scheduled(initialDelayString = "${rede.digest.retention.initial-delay-ms:600000}",
			fixedDelayString = "${rede.digest.retention.interval-ms:21600000}")
	public void run() {
		if (!enabled) {
			return;
		}
		try {
			int deleted = purge();
			if (deleted > 0) {
				LOG.info("Linhas de digest_deliveries fora da janela de retencao removidas: {}", deleted);
			}
		}
		catch (RuntimeException failure) {
			LOG.warn("Falha ao remover linhas antigas de digest_deliveries: {}", failure.getMessage());
		}
	}

	/** Remove um lote de semanas fora da janela. Devolve quantas linhas saíram (log e teste). */
	@Transactional
	public int purge() {
		List<String> weeks = digests.weekKeysBefore(cutoffWeekKey(), batchSize);
		int deleted = 0;
		for (String week : weeks) {
			deleted += digests.deleteWeek(week);
		}
		return deleted;
	}

	/** Quantas semanas estão elegíveis agora, sem apagar nada (diagnóstico e teste). */
	public long expiredCount() {
		return digests.weekKeysBefore(cutoffWeekKey(), Integer.MAX_VALUE).size();
	}

	/** Primeira semana que a janela ainda cobre: tudo estritamente antes dela sai. */
	String cutoffWeekKey() {
		LocalDate cutoff = LocalDate.now(clock.withZone(zone)).minusWeeks(weeks);
		return "%d-W%02d".formatted(cutoff.get(IsoFields.WEEK_BASED_YEAR),
			cutoff.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
	}
}
