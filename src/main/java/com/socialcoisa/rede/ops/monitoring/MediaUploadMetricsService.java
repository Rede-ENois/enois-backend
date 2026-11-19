package com.socialcoisa.rede.ops.monitoring;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Métricas do upload de mídia no painel de monitorabilidade (#925): por etapa do pipeline
 * (sanitize, moderação, gravação no bucket) o tempo medido pela série `rede_media_upload_stage`
 * — contagem, tempo total, mediana e p95 — e o tamanho gravado pela série `rede_media_upload_bytes`.
 *
 * <p>Leitura pura do MeterRegistry que a instrumentação do módulo de mídia já alimenta: nada é
 * contado duas vezes nem aqui. Agregado por stage/outcome e por kind, sem id de asset, mimetype
 * ou caminho (a mesma cardinalidade fixa da origem). Cache de um minuto porque o painel atualiza
 * a cada 30s e a janela do upload é mais larga que isso.</p>
 */
@Service
public class MediaUploadMetricsService {

	private final MeterRegistry meters;
	private final long cacheMs;
	private volatile MediaView cached;
	private volatile long cachedAt = Long.MIN_VALUE;

	MediaUploadMetricsService(org.springframework.beans.factory.ObjectProvider<MeterRegistry> meters,
			@Value("${rede.monitoring.media-cache-ms:60000}") long cacheMs) {
		this.meters = meters.getIfAvailable();
		this.cacheMs = cacheMs;
	}

	public MediaView media() {
		long now = System.currentTimeMillis();
		MediaView atual = cached;
		if (atual != null && now - cachedAt < cacheMs) {
			return atual;
		}
		MediaView novo = ler();
		cached = novo;
		cachedAt = now;
		return novo;
	}

	private MediaView ler() {
		List<StageView> etapas = new ArrayList<>();
		meters.find("rede_media_upload_stage").timers().forEach(timer -> {
			etapas.add(new StageView(timer.getId().getTag("stage"), timer.getId().getTag("outcome"),
				timer.count(), Math.round(timer.totalTime(TimeUnit.MILLISECONDS)),
				Math.round(percentil(timer, 0.5, TimeUnit.MILLISECONDS)),
				Math.round(percentil(timer, 0.95, TimeUnit.MILLISECONDS))));
		});
		etapas.sort((a, b) -> {
			int porEtapa = a.stage().compareToIgnoreCase(b.stage());
			return porEtapa != 0 ? porEtapa : a.outcome().compareToIgnoreCase(b.outcome());
		});

		var tamanhos = new LinkedHashMap<String, SizeView>();
		meters.find("rede_media_upload_bytes").summaries().forEach(summary -> {
			tamanhos.put(summary.getId().getTag("kind"), new SizeView(summary.count(),
				summary.totalAmount(), Math.round(percentil(summary.takeSnapshot(), 0.5)),
				Math.round(percentil(summary.takeSnapshot(), 0.95))));
		});

		return new MediaView(etapas, tamanhos);
	}

	/** Percentil publicado pelo timer (0 quando a janela ainda não tem amostra suficiente). */
	private static double percentil(Timer timer, double quantil, TimeUnit unidade) {
		for (var valor : timer.takeSnapshot().percentileValues()) {
			if (valor.percentile() == quantil) {
				return valor.value(unidade);
			}
		}
		return 0;
	}

	private static double percentil(io.micrometer.core.instrument.distribution.HistogramSnapshot snapshot,
			double quantil) {
		for (var valor : snapshot.percentileValues()) {
			if (valor.percentile() == quantil) {
				return valor.value();
			}
		}
		return 0;
	}

	public record MediaView(List<StageView> stages, LinkedHashMap<String, SizeView> sizes) {
	}

	public record StageView(String stage, String outcome, long count, long totalMs, double medianMs,
			double p95Ms) {
	}

	public record SizeView(long count, double totalBytes, double medianBytes, double p95Bytes) {
	}
}
