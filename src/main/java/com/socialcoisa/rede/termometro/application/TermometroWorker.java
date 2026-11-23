package com.socialcoisa.rede.termometro.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
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
 * desde a última fronteira, classifica com o léxico e grava uma fatia por manifestação (bucket da
 * hora de publicação, fonte, emoção, autor, id da publicação, momento da leitura). Depois
 * recalcula os cortes agregados das janelas a partir das fatias — nunca relendo posts,
 * comentários, desabafos ou confissões.
 *
 * A contagem é IDEMPOTENTE pela publicação: a mesma manifestação lida de novo (recomeço do
 * horizonte de 31 dias, relê de fonte) atualiza a fatia que já existe, não cria outra. Na janela
 * corrente, a publicação que já foi contada é pulada; na janela antiga, ela volta a ser lida —
 * é o que atualiza a fatia de quem foi editado ou apagado depois da primeira leitura. A releitura
 * reconcilia: a publicação que a fonte devolve sem texto (apagada, desativada ou editada) perde a
 * fatia — só a relida vazia conta como contradição, a pulada continua contando. A publicação que
 * some do banco (removida de verdade) expira junto com a janela: a fonte não devolve nem o id.
 *
 * O autor interno vive só na fatia, para a contagem de pessoas distintas da janela; o texto
 * classificado não é gravado. O corte é determinístico: mesma entrada, mesmo resultado.
 */
@Component
public class TermometroWorker {

	private static final Logger LOG = LoggerFactory.getLogger(TermometroWorker.class);

	/**
	 * A fronteira do corte incremental: a janela de 24 horas recuada de uma hora. O atraso é o que
	 * faz a publicação de agora voltar na leitura seguinte — quem publicou com o relógio um pouco
	 * atrasado, ou leu antes de a transação concluir, não fica de fora para sempre. E a
	 * reconciliação da janela só vale dentro dela: fora do intervalo, sumir da fonte pode ser
	 * expiração, não exclusão. Aqui a fatia é apagada só quando a releitura a contradiz, não por
	 * sumir da lista, então a folga não precisa crescer para a reconciliação evitar leitura vazia.
	 */
	private static final Duration FOLGA_DA_FRONTEIRA = Duration.ofHours(1);

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
		Instant fronteira = agora.minus(Duration.ofHours(24)).minus(FOLGA_DA_FRONTEIRA);

		for (ManifestacaoReader fonte : fontes) {
			List<ManifestacaoReader.Manifestacao> lidas = fonte.desde(fronteira, agora);
			List<TermometroRepository.Fatia> paraGravar = new ArrayList<>();
			// As que a leitura de AGORA releu: a reconciliação só contradiz o que ela viu.
			List<ManifestacaoReader.Manifestacao> relidas = new ArrayList<>();
			for (ManifestacaoReader.Manifestacao manifestacao : lidas) {
				// Lida na janela corrente? Já tem fatia feita com a leitura de agora (ou mais
				// recente): refazer o trabalho não muda nada — é o caminho do corte de 5 minutos.
				// Ela continua no ainda-no-ar (a releitura a viu); a reconciliação não a toca.
				// Mas a leitura que a fonte devolve VAZIA é a contradição (apagada/editada): ela
				// volta para a reconciliação, que remove a fatia e não regrava nada.
				var leitura = repository.leituraDaFatia(fonte.fonte(), manifestacao.publicacaoId());
				if (leitura.filter(lidaEm -> !lidaEm.isBefore(fronteira)).isPresent()
						&& manifestacao.texto() != null && !manifestacao.texto().isBlank()) {
					continue;
				}
				relidas.add(manifestacao);
				SentimentoLexicon.Veredito veredito = lexicon.classificar(manifestacao.texto());
				if (veredito instanceof SentimentoLexicon.Emocional emocional) {
					paraGravar.add(new TermometroRepository.Fatia(bucketDaHora(manifestacao.criadoEm()),
							fonte.fonte(), emocional.emocao().name(), manifestacao.autorId(),
							manifestacao.publicacaoId(), agora));
				}
			}
			// A reconciliação entra ANTES da gravação, e só com o que a leitura de AGORA releu: a
			// publicação que a fonte devolve vazia (apagada, desativada, editada) perde a fatia e é
			// regravada só se ainda classifica. A que o worker pulou (já contada) fica fora da
			// contradição.
			repository.removerFatiasDaJanela(fronteira, agora, relidas);
			if (!paraGravar.isEmpty()) {
				repository.salvarFatias(paraGravar);
			}
		}

		for (TermometroPeriodo periodo : TermometroPeriodo.emOrdemDePainel()) {
			repository.salvarCorte(corteDaJanela(periodo, agora));
		}
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

		Instant watermark = doIntervalo.stream()
			.map(TermometroRepository.Fatia::lidaEm)
			.max(Instant::compareTo)
			.orElse(inicio);
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
