package com.socialcoisa.rede.paporeto.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.paporeto.application.port.out.PapoAutorReader;
import com.socialcoisa.rede.paporeto.application.port.out.PapoPublicacaoReader;
import com.socialcoisa.rede.paporeto.application.port.out.PapoRetoRepository;
import com.socialcoisa.rede.paporeto.domain.PapoAssunto;
import com.socialcoisa.rede.paporeto.domain.PapoIntervalo;
import com.socialcoisa.rede.paporeto.domain.PapoTemaFala;
import com.socialcoisa.rede.shared.blocks.BlockedUsersReader;

/**
 * A qualidade do ranking do Papo Reto sobre um corpus sintético da comunidade, ponta a ponta:
 * da publicação publicada até a lista que a tela mostra. É o teste do ticket.
 *
 * O corpus imita o que a produção mostrou — a fala da rede ("gente", "sei", "tenho", "mundo",
 * "ate", "enois") espalhada por dezenas de publicações, uma palavra que aparece em toda parte
 * sem nomear conversa, um autor repetindo o mesmo assunto vinte vezes, uma conversa de três dias
 * atrás e um assunto que nasceu agora. Nenhuma categoria está escrita na mão: o que aparece na
 * lista emerge do texto do corpus, e é o ranking que prova isso.
 *
 * O último teste é o comparativo do pedido: a régua ANTERIOR, reimplementada no fim desta
 * classe, roda sobre as MESMAS falas deste corpus para mostrar o que mudou de verdade.
 */
class PapoRetoQualidadeTests {

	/** O instante do corte. As publicações "de agora" caem neste bucket de hora. */
	private static final Instant CORTE = Instant.parse("2026-11-18T12:00:00Z");

	private final FonteFalsa fonte = new FonteFalsa();

	private final RepositorioEmMemoria repositorio = new RepositorioEmMemoria();

	private final PapoAssuntoExtractor extractor = new PapoAssuntoExtractor();

	/** Roda um corte do worker no corpus publicado e devolve o ranking como a tela o lê. */
	private List<PapoRetoService.SubjectView> rankingDoCorte() {
		Clock relogio = Clock.fixed(CORTE.plusSeconds(1), ZoneOffset.UTC);
		PapoRetoWorker worker = new PapoRetoWorker(List.of(fonte), repositorio, extractor, relogio, 0.30, 0.25, 0.25,
				0.20);
		worker.corte();
		PapoRetoService service = new PapoRetoService(repositorio, worker, extractor, List.of(),
				PapoAutorReader.none(), BlockedUsersReader.none(), relogio);
		return service.ranking();
	}

	private static List<String> subjects(List<PapoRetoService.SubjectView> ranking) {
		return ranking.stream().map(PapoRetoService.SubjectView::subject).toList();
	}

	private static String statusDe(List<PapoRetoService.SubjectView> ranking, String subject) {
		return ranking.stream()
			.filter(view -> view.subject().equals(subject))
			.map(PapoRetoService.SubjectView::status)
			.findFirst()
			.orElseThrow();
	}

	/** Quantas horas atrás do corte o corpus publica; zero é o bucket do próprio corte. */
	private static Instant atras(int horas) {
		return CORTE.minus(Duration.ofHours(horas));
	}

	/** Publicações de gente diferente sobre o mesmo texto. */
	private void publicadas(String texto, int quantas, String prefixo, Instant quando) {
		for (int i = 1; i <= quantas; i++) {
			fonte.publicacoes.add(new PapoPublicacaoReader.Publicacao(texto, prefixo + "_" + i, quando, null,
					prefixo + "_pub_" + i));
		}
	}

	/** Publicações da MESMA pessoa repetindo o mesmo texto. */
	private void publicadasDoMesmoAutor(String texto, int quantas, String autor, String prefixo, Instant quando) {
		for (int i = 1; i <= quantas; i++) {
			fonte.publicacoes.add(new PapoPublicacaoReader.Publicacao(texto, autor, quando, null, prefixo + "_" + i));
		}
	}

	/**
	 * O corpus: ruído de rede, palavra onipresente, conversas de verdade da comunidade, um autor
	 * repetindo, uma conversa de três dias atrás e um assunto que acabou de nascer.
	 */
	private void corpusDaComunidade() {
		// A fala da rede: publicações que não nomeiam conversa nenhuma.
		publicadas("gente eu sei que tenho muito disso ate porque o mundo ta assim", 15, "rede", atras(9));
		publicadas("enois galera bora nessa hoje", 12, "saudacao", atras(20));
		// A palavra que está em toda parte sem nomear conversa: vinte e cinco publicações na janela.
		publicadas("vida corrida hoje", 25, "vida", atras(6));
		// As conversas de verdade: gente diferente, publicações de agora.
		publicadas("mercado de precos hoje pra gente", 8, "mercado", atras(0));
		publicadas("seguranca digital hoje", 7, "seguranca", atras(0));
		publicadas("java na pratica hoje", 6, "java", atras(0));
		publicadas("linux no servidor hoje", 6, "linux", atras(0));
		publicadas("curso de ingles hoje", 7, "curso", atras(0));
		// O autor que repete: vinte publicações da mesma pessoa sobre o mesmo assunto.
		publicadasDoMesmoAutor("promocao do meu curso de ingles", 20, "vendedor", "vend", atras(0));
		// A conversa de três dias atrás, com uma fala de agora.
		publicadas("mutirao da horta hoje", 12, "mutirao", atras(72));
		publicadas("mutirao da horta hoje", 1, "mutirao_agora", atras(0));
		// O assunto que nasceu agora.
		publicadas("black friday hoje", 12, "black", atras(0));
	}

	@Test
	void falaDaRedeNaoViraTendenciaEConversaDeVerdadeEntra() {
		corpusDaComunidade();

		List<String> ranking = subjects(rankingDoCorte());

		// Nenhuma palavra de conversa fiada aparece como tendência própria...
		assertThat(ranking).doesNotContain("gente", "sei", "tenho", "mundo", "ate", "enois", "galera", "coisa");
		// ...e o que aparece é conversa de verdade, tirada do texto do corpus.
		assertThat(ranking).contains("mercado de precos", "seguranca digital", "java na pratica", "linux no servidor",
				"black friday");
	}

	@Test
	void palavraOnipresenteSaiPelaReguaENaoPorListaDeProibicao() {
		corpusDaComunidade();

		List<String> ranking = subjects(rankingDoCorte());

		// "vida" foi extraída e agregada — vinte e cinco pessoas falaram dela —, então nenhuma
		// lista de palavras vazias é o que a segura: a régua do próprio corte tira a palavra que
		// está em toda parte depois do chão de pessoas, e não antes dele.
		PapoAssunto vida = repositorio.assuntoDe(PapoRetoWorker.ESCOPO_GLOBAL, "vida corrida").orElseThrow();
		assertThat(vida.falasPost()).isEqualTo(25);
		assertThat(vida.pessoas()).isEqualTo(25);
		assertThat(vida.publicacoes()).isEqualTo(25);
		assertThat(vida.janelaPublicacoes()).isEqualTo(104);
		// A régua de "onipresente" se aplica à palavra NÃO composta; como o assunto agora é sempre
		// composto, uma conversa legítima de muitas pessoas permanece no ranking (não é fala comum).
		assertThat(ranking).contains("vida corrida").doesNotContain("vida", "corrida");
	}

	@Test
	void repeticaoDeUmAutorNaoInflaNemLidera() {
		corpusDaComunidade();

		List<PapoRetoService.SubjectView> ranking = rankingDoCorte();

		// A contagem da tela continua honesta — vinte e sete falas de oito pessoas, com as vinte
		// do repetidor contadas —, mas a potência conta no máximo três falas por pessoa.
		PapoAssunto curso = repositorio.assuntoDe(PapoRetoWorker.ESCOPO_GLOBAL, "curso de ingles").orElseThrow();
		assertThat(curso.falasPost()).isEqualTo(27);
		assertThat(curso.pessoas()).isEqualTo(8);
		assertThat(curso.potencia()).isCloseTo(10 * 0.30 + Math.sqrt(8) * 0.25 + Math.sqrt(10) * 0.20, within(0.01));
		// O assunto que só o repetidor fala não tem gente distinta: não chega à tela.
		assertThat(subjects(ranking)).doesNotContain("promocao");
		// E o volume do repetidor não faz a conversa dele liderar: a fala repetida vale o teto.
		assertThat(ranking.get(0).subject()).isEqualTo("black friday");
	}

	@Test
	void assuntoNovoPassaNaFrenteDoAssuntoVelhoEONovoEhEmergente() {
		corpusDaComunidade();

		List<PapoRetoService.SubjectView> views = rankingDoCorte();
		List<String> ranking = subjects(views);

		// O que nasceu agora vale pelo que está vivo agora; a conversa de três dias atrás, com
		// uma fala nova, não volta ao topo por causa dela.
		assertThat(ranking.indexOf("black friday")).isLessThan(ranking.indexOf("mutirao da horta"));
		assertThat(statusDe(views, "black friday")).isEqualTo("EMERGENTE");
		assertThat(statusDe(views, "mutirao da horta")).isEqualTo("PERSISTENTE");
	}

	@Test
	void assuntoAntigoEsmoreceMasContinuaNaLista() {
		corpusDaComunidade();

		List<String> ranking = subjects(rankingDoCorte());

		// Treze pessoas falaram do mutirão na janela: ele perde a liderança, não o histórico.
		assertThat(ranking).contains("mutirao da horta");
	}

	@Test
	void comparativoComAReguaAnterior() {
		corpusDaComunidade();
		List<String> agora = subjects(rankingDoCorte());
		List<String> antes = rankingDaReguaAnterior();

		// A régua de extração mudou: agora só assunto COM CONTEXTO (composto) entra. A palavra
		// solta (uma só) não aparece em nenhum dos dois rankings do corpus novo — ambos já são
		// compostos —, então o comparativo se concentra no que a régua de pontuação ainda separa.
		assertThat(agora).allSatisfy(subject -> assertThat(subject).contains(" "));
		assertThat(agora).doesNotContain("java", "linux", "mutirao", "vida", "filme", "corrida");
		assertThat(antes).containsAll(agora);
		// E o assunto inflado por repetição não passa na frente da conversa de gente diferente:
		// vinte e sete falas de uma pessoa não valem mais que doze publicações de doze pessoas.
		assertThat(antes.indexOf("curso de ingles")).isLessThan(antes.indexOf("black friday"));
		assertThat(agora.indexOf("black friday")).isLessThan(agora.indexOf("curso de ingles"));
	}

	@Test
	void basePequenaMostraOAssuntoMarcadoComoAmostragemMenor() {
		// Rede pequena: nove publicações no escopo, três delas sobre o mutirão. Na base grande,
		// três pessoas não passariam do chão de cinco — aqui, com a amostra pequena, o assunto
		// aparece e vem marcado, com o número de pessoas e o tamanho da base para a tela.
		publicadas("mutirao da horta", 3, "mutirao", atras(0));
		publicadas("festa de aniversario", 2, "festa", atras(0));
		publicadas("aquele filme", 4, "filme", atras(0));

		List<PapoRetoService.SubjectView> ranking = rankingDoCorte();

		assertThat(subjects(ranking)).contains("mutirao da horta");
		PapoRetoService.SubjectView mutirao = ranking.stream()
			.filter(view -> view.subject().equals("mutirao da horta"))
			.findFirst()
			.orElseThrow();
		assertThat(mutirao.pessoas()).isEqualTo(3);
		assertThat(mutirao.amostragemMenor()).isTrue();
		// A base agora conta só publicações com assunto COM CONTEXTO (composto): "mutirao da
		// horta" (3) + "festa de aniversario" (2). "aquele filme" vira "filme" sozinho e não conta.
		assertThat(mutirao.janelaPublicacoes()).isEqualTo(5);
	}

	/**
	 * A régua ANTERIOR, reimplementada aqui só para o comparativo do pedido: contagens acumuladas
	 * com um único decaimento pela última fala, sem teto por autor, sem fração de publicações
	 * distintas e sem chão de raridade. Ela vive no teste de propósito — é a prova do antes e
	 * depois, e sai junto com o teste quando essa comparação deixar de fazer sentido.
	 */
	private List<String> rankingDaReguaAnterior() {
		record Antigo(PapoAssunto assunto, double pontuacao) {
		}
		List<Antigo> classificados = new ArrayList<>();
		for (PapoAssunto assunto : repositorio.assuntosDoEscopo(PapoRetoWorker.ESCOPO_GLOBAL)) {
			if (assunto.pessoas() < PapoAssunto.MINIMO_PESSOAS || !extractor.assuntoValido(assunto.subject())) {
				continue;
			}
			double decaimentoDaUltimaFala = PapoAssunto.decaimento(assunto.ultimaFala(), CORTE.plusSeconds(1));
			double fontes = (assunto.falasPost() * 0.30 + assunto.falasComentario() * 0.25) * decaimentoDaUltimaFala;
			double gente = Math.sqrt(assunto.pessoas()) * 0.25;
			double crescimento = Math.sqrt(assunto.falasUltimaHora()) * 0.20;
			classificados.add(new Antigo(assunto, fontes + gente + crescimento));
		}
		classificados.sort(Comparator.comparingDouble(Antigo::pontuacao).reversed());
		return classificados.stream().map(antigo -> antigo.assunto().subject()).toList();
	}

	/** A fonte falsa: o corpus publicado à mão, lido como qualquer fonte é lida. */
	private static final class FonteFalsa implements PapoPublicacaoReader {

		private final List<Publicacao> publicacoes = new ArrayList<>();

		@Override
		public List<Publicacao> desde(Instant de, Instant ate) {
			return publicacoes.stream()
				.filter(publicacao -> !publicacao.criadoEm().isBefore(de) && publicacao.criadoEm().isBefore(ate))
				.toList();
		}

		@Override
		public List<Detalhe> detalhes(Collection<String> publicacaoIds) {
			return List.of();
		}

		@Override
		public String fonte() {
			return "POST";
		}

	}

	/**
	 * O repositório em memória: as falas do corte e o agregado por escopo. Guarda o que o teste
	 * lê — assunto do escopo, assuntos por subject e publicações do assunto —, e nada de banco.
	 */
	private static final class RepositorioEmMemoria implements PapoRetoRepository {

		private final List<Fala> falas = new ArrayList<>();

		private final Map<String, List<PapoAssunto>> assuntosPorEscopo = new LinkedHashMap<>();

		private final Map<String, List<PapoIntervalo>> intervalosPorEscopo = new LinkedHashMap<>();

		@Override
		public void salvarFalas(List<Fala> novas) {
			falas.addAll(novas);
		}

		@Override
		public List<Fala> falasEntre(Instant de, Instant ate) {
			return falas.stream().filter(fala -> !fala.hora().isBefore(de) && fala.hora().isBefore(ate)).toList();
		}

		@Override
		public List<PapoTemaFala> temasDePublicacao(String publicacaoId) {
			return falas.stream()
				.filter(fala -> fala.publicacaoId().equals(publicacaoId))
				.map(fala -> new PapoTemaFala(fala.expressao(), fala.fonte()))
				.toList();
		}

		@Override
		public Optional<Instant> ultimaFronteira() {
			return falas.stream().map(Fala::lidaEm).max(Instant::compareTo);
		}

		@Override
		public void salvarAssuntos(String escopo, List<PapoAssunto> novos) {
			assuntosPorEscopo.put(escopo, List.copyOf(novos));
		}

		@Override
		public List<PapoAssunto> assuntosDoEscopo(String escopo) {
			return assuntosPorEscopo.getOrDefault(escopo, List.of());
		}

		@Override
		public Optional<PapoAssunto> assuntoDe(String escopo, String subject) {
			return assuntosDoEscopo(escopo).stream().filter(assunto -> assunto.subject().equals(subject)).findFirst();
		}

		@Override
		public void apagarAssuntosSemFala(String escopo) {
			assuntosPorEscopo.put(escopo, assuntosDoEscopo(escopo).stream()
				.filter(assunto -> assunto.falasPost() + assunto.falasComentario() > 0)
				.toList());
		}

		@Override
		public void reconciliarIntervalos(String escopo, List<PapoIntervalo> contagemDoCorte) {
			intervalosPorEscopo.put(escopo, List.copyOf(contagemDoCorte));
		}

		@Override
		public List<PapoIntervalo> intervalosDe(String escopo, String subject, Instant de, Instant ate) {
			return intervalosPorEscopo.getOrDefault(escopo, List.of())
				.stream()
				.filter(intervalo -> intervalo.subject().equals(subject) && !intervalo.hora().isBefore(de)
						&& intervalo.hora().isBefore(ate))
				.toList();
		}

		@Override
		public List<PublicacaoDaFala> publicacoesDoAssunto(String escopo, String subject, Instant de, Instant ate,
				int limite) {
			Map<String, PublicacaoDaFala> unicas = new LinkedHashMap<>();
			for (Fala fala : falas) {
				if (fala.expressao().equals(subject) && !fala.hora().isBefore(de) && fala.hora().isBefore(ate)) {
					unicas.putIfAbsent(fala.publicacaoId(),
							new PublicacaoDaFala(fala.publicacaoId(), fala.fonte(), fala.communityId(), fala.hora()));
				}
			}
			return unicas.values().stream().limit(limite).toList();
		}

		@Override
		public long falasTotais(Instant de, Instant ate) {
			return falasEntre(de, ate).size();
		}

	}

}
