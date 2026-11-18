package com.socialcoisa.rede.paporeto.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.socialcoisa.rede.paporeto.application.port.out.PapoPublicacaoReader;
import com.socialcoisa.rede.paporeto.application.port.out.PapoRetoRepository;
import com.socialcoisa.rede.paporeto.domain.PapoAssunto;
import com.socialcoisa.rede.paporeto.domain.PapoIntervalo;
import com.socialcoisa.rede.paporeto.domain.PapoTemaFala;

/**
 * O corte incremental do Papo Reto: a cada 5 minutos lê das fontes APENAS o que foi publicado
 * desde a última fronteira, extrai as expressões e grava falas (bucket da hora, expressão
 * normalizada, fonte, autor, comunidade de origem, publicação de origem). Depois REESCREVE os
 * agregados dos assuntos a partir das falas — nunca relendo publicações.
 *
 * Duas propriedades seguram a contabilidade:
 * - DEDUP por publicação: a fala guarda o id da publicação que a gerou; uma publicação lida
 *   duas vezes (recomeço do horizonte, relê por falha) não fala duas vezes;
 * - RECONCILIAÇÃO: o agregado e a timeline são reescritos/reconciliados por completo a cada
 *   corte a partir das falas — conteúdo removido sai da conta na passagem seguinte, e o mesmo
 *   bucket da timeline volta sempre ao mesmo número.
 *
 * A pontuação soma os pesos configuráveis (30% posts, 25% comentários, 25% pessoas distintas,
 * 20% crescimento na última hora, por propriedade) com decaimento pela idade da última fala.
 * O chão de pessoas distintas trava a manipulação: expressão que só uma mão de contas
 * publicou não vira alta. O agregado existe por ESCOPO (global e uma por comunidade com fala
 * nova); a fala entra no escopo da sua comunidade e no global (o assunto do feed geral também
 * é papo da rede).
 */
@Component
public class PapoRetoWorker {

	private static final Logger LOG = LoggerFactory.getLogger(PapoRetoWorker.class);

	/** O escopo do ranking global (falas sem comunidade E falas de todas as comunidades). */
	public static final String ESCOPO_GLOBAL = "";

	/** A janela mais larga define até onde a contabilidade por bucket precisa existir. */
	private static final Duration HORIZONTE = Duration.ofDays(31);

	/** A janela agregada: quanto tempo o assunto continua "em alta" depois da última fala. */
	private static final Duration JANELA = Duration.ofDays(7);

	private final List<PapoPublicacaoReader> fontes;

	private final PapoRetoRepository repository;

	private final PapoAssuntoExtractor extractor;

	private final Clock clock;

	private final double pesoPosts;

	private final double pesoComentarios;

	private final double pesoPessoas;

	private final double pesoCrescimento;

	PapoRetoWorker(List<PapoPublicacaoReader> fontes, PapoRetoRepository repository, PapoAssuntoExtractor extractor,
			Clock clock,
			@Value("${rede.paporeto.peso-posts:0.30}") double pesoPosts,
			@Value("${rede.paporeto.peso-comentarios:0.25}") double pesoComentarios,
			@Value("${rede.paporeto.peso-pessoas:0.25}") double pesoPessoas,
			@Value("${rede.paporeto.peso-crescimento:0.20}") double pesoCrescimento) {
		this.fontes = fontes;
		this.repository = repository;
		this.extractor = extractor;
		this.clock = clock;
		this.pesoPosts = pesoPosts;
		this.pesoComentarios = pesoComentarios;
		this.pesoPessoas = pesoPessoas;
		this.pesoCrescimento = pesoCrescimento;
	}

	@Scheduled(fixedDelayString = "${rede.paporeto.corte-delay-ms:300000}")
	public void cortar() {
		try {
			corte();
		}
		catch (RuntimeException excecao) {
			LOG.warn("Falha no corte do Papo Reto: {}", excecao.getMessage());
		}
	}

	/** Um corte, exposto para teste: lê, extrai, grava falas e reescreve os agregados. */
	public void corte() {
		Instant agora = clock.instant();
		Instant fronteira = repository.ultimaFronteira().orElse(agora.minus(HORIZONTE));

		falar(fronteira, agora);
		reescreverAgregados(agora);
	}

	/**
	 * Lê cada fonte desde a fronteira, extrai as expressões e grava as falas novas. A dedup é
	 * pela âncora (publicação + expressão): se a publicação já falou esta expressão num corte
	 * anterior, a fala não nasce de novo — recomeço de horizonte e relê por falha não duplicam
	 * contagem.
	 */
	private void falar(Instant de, Instant ate) {
		List<PapoRetoRepository.Fala> falas = new ArrayList<>();
		for (PapoPublicacaoReader fonte : fontes) {
			for (PapoPublicacaoReader.Publicacao publicacao : fonte.desde(de, ate)) {
				List<PapoTemaFala> jaLidas = repository.temasDePublicacao(publicacao.publicacaoId());
				Set<String> expressoesDaPublicacao = new HashSet<>();
				jaLidas.forEach(fala -> expressoesDaPublicacao.add(fala.expressao()));
				for (PapoAssuntoExtractor.Fala tema : extractor.extrair(publicacao.texto(), fonte.fonte())) {
					if (expressoesDaPublicacao.contains(tema.expressao())) {
						continue;
					}
					expressoesDaPublicacao.add(tema.expressao());
					falas.add(new PapoRetoRepository.Fala(bucketDaHora(publicacao.criadoEm()), tema.expressao(),
							tema.fonte(), publicacao.autorId(), publicacao.communityId(), publicacao.publicacaoId(), ate));
				}
			}
		}
		if (!falas.isEmpty()) {
			repository.salvarFalas(falas);
		}
	}

	/**
	 * A reescrita: cada escopo com fala na janela é agregado do zero a partir das falas —
	 * contagens por fonte, pessoas distintas, falas da última hora e timeline por bucket.
	 * Assunto zerado sai da lista (a linha é apagada); a timeline é reconciliada por bucket,
	 * então o mesmo bucket volta sempre ao mesmo número.
	 */
	private void reescreverAgregados(Instant agora) {
		Instant inicioDaJanela = agora.minus(JANELA);
		List<PapoRetoRepository.Fala> naJanela = repository.falasEntre(inicioDaJanela, agora);

		// Falas na janela, agrupadas por escopo: global pega todas; o da comunidade pega as suas.
		Map<String, List<PapoRetoRepository.Fala>> porEscopo = new LinkedHashMap<>();
		porEscopo.put(ESCOPO_GLOBAL, naJanela);
		for (PapoRetoRepository.Fala fala : naJanela) {
			if (fala.communityId() != null) {
				porEscopo.computeIfAbsent(fala.communityId(), chave -> new ArrayList<>()).add(fala);
			}
		}

		for (Map.Entry<String, List<PapoRetoRepository.Fala>> escopo : porEscopo.entrySet()) {
			agregarEscopo(escopo.getKey(), escopo.getValue(), agora, inicioDaJanela);
		}
	}

	/** O agregado completo de um escopo: assuntos reescritos e timeline reconciliada. */
	private void agregarEscopo(String escopo, List<PapoRetoRepository.Fala> falas, Instant agora,
			Instant inicioDaJanela) {
		Map<String, List<PapoRetoRepository.Fala>> porSubject = new HashMap<>();
		for (PapoRetoRepository.Fala fala : falas) {
			porSubject.computeIfAbsent(fala.expressao(), chave -> new ArrayList<>()).add(fala);
		}

		Map<String, Long> falasUltimaHora = new HashMap<>();
		for (PapoRetoRepository.Fala fala : falas) {
			if (!fala.hora().isBefore(agora.minus(Duration.ofHours(1)))) {
				falasUltimaHora.merge(fala.expressao(), 1L, Long::sum);
			}
		}

		List<PapoAssunto> assuntos = new ArrayList<>();
		for (Map.Entry<String, List<PapoRetoRepository.Fala>> entry : porSubject.entrySet()) {
			String subject = entry.getKey();
			List<PapoRetoRepository.Fala> doSubject = entry.getValue();

			long falasPost = doSubject.stream().filter(f -> !"COMMENT".equals(f.fonte())).count();
			long falasComentario = doSubject.size() - falasPost;
			Set<String> autores = new HashSet<>();
			doSubject.forEach(f -> autores.add(f.autorId()));
			Instant ultimaFala = doSubject.stream().map(PapoRetoRepository.Fala::hora).max(Instant::compareTo)
					.orElse(inicioDaJanela);

			assuntos.add(new PapoAssunto(subject, falasPost, falasComentario, autores.size(),
					falasUltimaHora.getOrDefault(subject, 0L), ultimaFala));
		}
		repository.salvarAssuntos(escopo, assuntos);
		repository.apagarAssuntosSemFala(escopo);

		// A timeline do escopo: contagem por (subject, bucket) reconciliada — a lista inteira
		// substitui o que estava gravado, então bucket velho e bucket novo voltam ao número certo.
		Map<String, Map<Instant, Long>> porBucket = new HashMap<>();
		for (PapoRetoRepository.Fala fala : falas) {
			porBucket.computeIfAbsent(fala.expressao(), chave -> new HashMap<>())
				.merge(fala.hora(), 1L, Long::sum);
		}
		List<PapoIntervalo> intervalos = new ArrayList<>();
		for (Map.Entry<String, Map<Instant, Long>> subjectBuckets : porBucket.entrySet()) {
			for (Map.Entry<Instant, Long> bucket : subjectBuckets.getValue().entrySet()) {
				intervalos.add(new PapoIntervalo(escopo, subjectBuckets.getKey(), bucket.getKey(), bucket.getValue()));
			}
		}
		repository.reconciliarIntervalos(escopo, intervalos);
	}

	/** A pontuação do subject, exposta para o ranking e para teste (mesma fórmula do corte). */
	public double pontuacaoDe(PapoAssunto assunto, Instant agora) {
		return assunto.potencia(pesoPosts, pesoComentarios, pesoPessoas, pesoCrescimento, agora);
	}

	/** A hora cheia em UTC em que a publicação foi feita (o bucket). */
	static Instant bucketDaHora(Instant quando) {
		return Instant.ofEpochSecond((quando.getEpochSecond() / 3600) * 3600);
	}

}
