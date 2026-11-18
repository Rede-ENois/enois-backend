package com.socialcoisa.rede.termometro.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.socialcoisa.rede.termometro.application.port.out.ManifestacaoReader;
import com.socialcoisa.rede.termometro.application.port.out.TermometroRepository;
import com.socialcoisa.rede.termometro.domain.CorteTermico;
import com.socialcoisa.rede.termometro.domain.Emocao;
import com.socialcoisa.rede.termometro.domain.TermometroPeriodo;

/**
 * O corte incremental do Termômetro: a cada 5 minutos lê das fontes APENAS o que foi publicado
 * desde a última fronteira, classifica com o léxico e grava uma fatia por manifestação (bucket
 * da hora de publicação, fonte, emoção, autor, momento da leitura). Depois recalcula os cortes
 * agregados das janelas a partir das fatias — nunca relendo posts, comentários, desabafos ou
 * confissões.
 *
 * O autor interno vive só na fatia, para a contagem de pessoas distintas da janela; o texto
 * classificado não é gravado. O corte é determinístico: mesma entrada, mesmo resultado.
 */
@Component
public class TermometroWorker {

	private static final Logger LOG = LoggerFactory.getLogger(TermometroWorker.class);

	/** A janela mais larga define até onde a contabilidade por bucket precisa existir. */
	private static final Duration HORIZONTE = Duration.ofDays(31);

	private final List<ManifestacaoReader> fontes;

	private final TermometroRepository repository;

	private final SentimentoLexicon lexicon;

	private final Clock clock;

	TermometroWorker(List<ManifestacaoReader> fontes, TermometroRepository repository,
			SentimentoLexicon lexicon, Clock clock) {
		this.fontes = fontes;
		this.repository = repository;
		this.lexicon = lexicon;
		this.clock = clock;
	}

	@Scheduled(fixedDelayString = "${rede.termometro.corte-delay-ms:300000}")
	public void cortar() {
		try {
			corte();
		}
		catch (RuntimeException excecao) {
			LOG.warn("Falha no corte do Termômetro: {}", excecao.getMessage());
		}
	}

	/** Um corte, exposto para teste: lê, classifica, grava fatias e reescreve os cortes. */
	public void corte() {
		Instant agora = clock.instant();
		Instant fronteira = repository.ultimaFronteira().orElse(agora.minus(HORIZONTE));

		List<TermometroRepository.Fatia> fatias = fatiar(fronteira, agora);
		if (!fatias.isEmpty()) {
			repository.salvarFatias(fatias);
		}

		for (TermometroPeriodo periodo : TermometroPeriodo.emOrdemDePainel()) {
			repository.salvarCorte(corteDaJanela(periodo, agora));
		}
	}

	/** Lê cada fonte desde a fronteira e devolve as fatias classificadas. */
	private List<TermometroRepository.Fatia> fatiar(Instant de, Instant ate) {
		List<TermometroRepository.Fatia> fatias = new ArrayList<>();
		for (ManifestacaoReader fonte : fontes) {
			for (ManifestacaoReader.Manifestacao manifestacao : fonte.desde(de, ate)) {
				SentimentoLexicon.Veredito veredito = lexicon.classificar(manifestacao.texto());
				if (!(veredito instanceof SentimentoLexicon.Emocional emocional)) {
					continue;
				}
				fatias.add(new TermometroRepository.Fatia(bucketDaHora(manifestacao.criadoEm()),
						fonte.fonte(), emocional.emocao().name(), manifestacao.autorId(), ate));
			}
		}
		return fatias;
	}

	/**
	 * O corte da janela: soma as fatias cujo bucket cai no intervalo da janela, contando pessoas
	 * distintas. Reescreve a linha da janela por completo — o corte é idempotente.
	 */
	private CorteTermico corteDaJanela(TermometroPeriodo periodo, Instant agora) {
		Instant inicio = inicioDaJanela(periodo, agora);
		List<TermometroRepository.Fatia> doIntervalo = repository.fatiasEntre(inicio, agora);

		Map<Emocao, Long> contagens = new EnumMap<>(Emocao.class);
		Set<String> autores = new HashSet<>();
		for (TermometroRepository.Fatia fatia : doIntervalo) {
			contagens.merge(Emocao.valueOf(fatia.emocao()), 1L, Long::sum);
			autores.add(fatia.autorId());
		}

		Instant watermark = repository.ultimaFronteira().orElse(inicio);
		return new CorteTermico(periodo, contagens, doIntervalo.size(), autores.size(), watermark);
	}

	/** O começo da janela relativa a agora (a de HOJE começa na meia-noite UTC da plataforma). */
	static Instant inicioDaJanela(TermometroPeriodo periodo, Instant agora) {
		return switch (periodo) {
			case HOJE -> LocalDate.ofInstant(agora, ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
			case ULTIMAS_24H -> agora.minus(Duration.ofHours(24));
			case ULTIMOS_7D -> agora.minus(Duration.ofDays(7));
			case ULTIMOS_30D -> agora.minus(Duration.ofDays(30));
		};
	}

	/** A hora cheia em UTC em que a manifestação foi publicada (o bucket). */
	static Instant bucketDaHora(Instant quando) {
		return Instant.ofEpochSecond((quando.getEpochSecond() / 3600) * 3600);
	}

}
