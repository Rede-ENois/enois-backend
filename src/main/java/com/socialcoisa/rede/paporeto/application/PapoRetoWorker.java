package com.socialcoisa.rede.paporeto.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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
 * 20% crescimento na última hora, por propriedade) com decaimento FALA A FALA pela idade dela —
 * o assunto vale pelo que está vivo agora, e não pelo que acumulou. O corte também grava a
 * potência do mesmo assunto avaliada 24 horas antes, nas falas que já existiam então: é a
 * trajetória de verdade, e é dela que saem o status e o crescimento da tela.
 *
 * Três réguas seguram a qualidade do ranking, todas no corte:
 * - TETO POR AUTOR: cada pessoa soma no máximo {@link PapoAssunto#TETO_FALAS_POR_AUTOR} falas do
 *   assunto, então repetição não infla;
 * - CHÃO DE PESSOAS: o assunto precisa de {@link PapoAssunto#MINIMO_PESSOAS} pessoas distintas;
 * - DISCRIMINAÇÃO: o agregado grava quantas PUBLICAÇÕES distintas falam do assunto e quantas
 *   publicações o escopo tem na janela; a fração é o que separa a conversa da fala comum, e é
 *   o domínio que aplica a peneira na leitura.
 *
 * O agregado existe por ESCOPO (global e uma por comunidade com fala nova); a fala entra no
 * escopo da sua comunidade e no global (o assunto do feed geral também é papo da rede).
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

	/** A última hora do corte: a matéria do termo de crescimento. */
	private static final Duration HORA = Duration.ofHours(1);

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
	 *
	 * O que vira fala é o assunto do CLUSTER, não a palavra solta: quando "inteligencia
	 * artificial" aparece junto no texto, ela cobre "inteligencia" e "artificial" naquela
	 * publicação. É o mais específico que nomeia a conversa — e é o que impede o ranking de
	 * listar a mesma conversa três vezes. As tags da publicação entram como assunto declarado.
	 */
	private void falar(Instant de, Instant ate) {
		List<PapoRetoRepository.Fala> falas = new ArrayList<>();
		for (PapoPublicacaoReader fonte : fontes) {
			for (PapoPublicacaoReader.Publicacao publicacao : fonte.desde(de, ate)) {
				List<PapoTemaFala> jaLidas = repository.temasDePublicacao(publicacao.publicacaoId());
				Set<String> expressoesDaPublicacao = new HashSet<>();
				jaLidas.forEach(fala -> expressoesDaPublicacao.add(fala.expressao()));
				List<PapoAssuntoExtractor.Fala> extraidas = extractor.extrair(publicacao.texto(), fonte.fonte(),
						publicacao.assuntosDeclarados());
				for (String expressao : extractor.assuntos(extraidas)) {
					if (expressoesDaPublicacao.contains(expressao)) {
						continue;
					}
					expressoesDaPublicacao.add(expressao);
					falas.add(new PapoRetoRepository.Fala(bucketDaHora(publicacao.criadoEm()), expressao,
							fonte.fonte(), publicacao.autorId(), publicacao.communityId(), publicacao.publicacaoId(), ate));
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

		// O denominador da discriminação: quantas publicações o escopo tem na janela. É o que
		// transforma "quantas falas" em "em quantas publicações esse assunto aparece".
		long publicacoesDaJanela = falas.stream().map(PapoRetoRepository.Fala::publicacaoId).distinct().count();
		Instant antesDoCorte = agora.minus(PapoAssunto.MEIA_VIDA);

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
			long publicacoes = doSubject.stream().map(PapoRetoRepository.Fala::publicacaoId).distinct().count();
			long falasUltimaHora = contarEntre(doSubject, agora.minus(HORA), agora);

			// A trajetória é medida, não deduzida: a potência de agora usa as falas de agora, a
			// potência de 24 horas atrás usa as falas que existiam então, com o decaimento
			// avaliado naquele instante. Todas as contagens da potência passam pelo teto por
			// autor — o crescimento também, senão quem repete ainda infla um quinto da conta.
			double potencia = PapoAssunto.potenciaDe(fontesPesadas(doSubject, agora), autores.size(),
					contarComTeto(doSubject, agora.minus(HORA), agora), pesoPessoas, pesoCrescimento);
			long pessoasAntes = pessoasAte(doSubject, antesDoCorte);
			double potenciaAntes = PapoAssunto.potenciaDe(fontesPesadas(doSubject, antesDoCorte), pessoasAntes,
					contarComTeto(doSubject, antesDoCorte.minus(HORA), antesDoCorte), pesoPessoas, pesoCrescimento);

			assuntos.add(new PapoAssunto(subject, falasPost, falasComentario, autores.size(), falasUltimaHora,
					ultimaFala, publicacoes, publicacoesDaJanela, potencia, potenciaAntes, contarComTeto(doSubject,
							agora.minus(Duration.ofDays(1)), agora),
					contarComTeto(doSubject, agora.minus(Duration.ofDays(2)), agora.minus(Duration.ofDays(1)))));
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

	/**
	 * As fontes pesadas do assunto no instante de referência, com o teto por autor: cada pessoa
	 * contribui com as suas falas mais recentes até {@link PapoAssunto#TETO_FALAS_POR_AUTOR}, e
	 * cada fala vale o peso da sua fonte decaído pela idade dela. Fala que ainda não existia no
	 * instante de referência não conta — é assim que a potência de 24 horas atrás é medida.
	 */
	private double fontesPesadas(List<PapoRetoRepository.Fala> falas, Instant referencia) {
		Map<String, List<PapoRetoRepository.Fala>> porAutor = new HashMap<>();
		for (PapoRetoRepository.Fala fala : falas) {
			if (!fala.hora().isAfter(referencia)) {
				porAutor.computeIfAbsent(fala.autorId(), chave -> new ArrayList<>()).add(fala);
			}
		}
		double fontes = 0;
		for (List<PapoRetoRepository.Fala> doAutor : porAutor.values()) {
			List<PapoRetoRepository.Fala> contadas = doAutor.stream()
				.sorted(Comparator.comparing(PapoRetoRepository.Fala::hora).reversed())
				.limit(PapoAssunto.TETO_FALAS_POR_AUTOR)
				.toList();
			for (PapoRetoRepository.Fala fala : contadas) {
				fontes += pesoDaFonte(fala.fonte()) * PapoAssunto.decaimento(fala.hora(), referencia);
			}
		}
		return fontes;
	}

	/** Quantas falas do assunto existem na faixa (de, ate] — é a contagem que a tela mostra. */
	private static long contarEntre(List<PapoRetoRepository.Fala> falas, Instant de, Instant ate) {
		return falas.stream().filter(f -> f.hora().isAfter(de) && !f.hora().isAfter(ate)).count();
	}

	/**
	 * Quantas falas da faixa (de, ate] contam para a potência: a mesma contagem, com o teto por
	 * autor. É esta a última hora do termo de crescimento.
	 */
	private static long contarComTeto(List<PapoRetoRepository.Fala> falas, Instant de, Instant ate) {
		Map<String, Long> porAutor = new HashMap<>();
		for (PapoRetoRepository.Fala fala : falas) {
			if (fala.hora().isAfter(de) && !fala.hora().isAfter(ate)) {
				porAutor.merge(fala.autorId(), 1L, Long::sum);
			}
		}
		return porAutor.values()
			.stream()
			.mapToLong(contagem -> Math.min(contagem, PapoAssunto.TETO_FALAS_POR_AUTOR))
			.sum();
	}

	/** Quantas pessoas distintas já tinham falado o assunto até o instante de referência. */
	private static long pessoasAte(List<PapoRetoRepository.Fala> falas, Instant referencia) {
		return falas.stream()
			.filter(f -> !f.hora().isAfter(referencia))
			.map(PapoRetoRepository.Fala::autorId)
			.distinct()
			.count();
	}

	/** O peso da fonte da fala: comentário e publicação pesam diferente, como na configuração. */
	private double pesoDaFonte(String fonte) {
		return "COMMENT".equals(fonte) ? pesoComentarios : pesoPosts;
	}

	/**
	 * A potência pela régua ANTIGA, exposta para a leitura tolerar a linha gravada antes da
	 * virada (até o primeiro corte reescrevê-la). Não é usada por linha nova nenhuma.
	 */
	public double pontuacaoLegadaDe(PapoAssunto assunto, Instant agora) {
		return assunto.potenciaLegada(pesoPosts, pesoComentarios, pesoPessoas, pesoCrescimento, agora);
	}

	/** A hora cheia em UTC em que a publicação foi feita (o bucket). */
	static Instant bucketDaHora(Instant quando) {
		return Instant.ofEpochSecond((quando.getEpochSecond() / 3600) * 3600);
	}

}
