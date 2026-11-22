package com.socialcoisa.rede.paporeto.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.paporeto.application.port.out.PapoAutorReader;
import com.socialcoisa.rede.paporeto.application.port.out.PapoPublicacaoReader;
import com.socialcoisa.rede.paporeto.application.port.out.PapoRetoRepository;
import com.socialcoisa.rede.paporeto.domain.PapoAssunto;
import com.socialcoisa.rede.paporeto.domain.PapoIntervalo;
import com.socialcoisa.rede.paporeto.domain.PapoTemaFala;
import com.socialcoisa.rede.shared.blocks.BlockedUsersReader;

/**
 * A leitura do Papo Reto: o ranking é o teto do que a tela vê — agregado, sem autoria, e com
 * o chão de pessoas distintas valendo dentro do service também (o assunto sem gente não chega
 * à lista nem com potência alta). As publicações relacionadas são a exceção declarada: trazem
 * o texto público de origem, com a autoria que o espaço permite e o bloqueio do visitante.
 */
class PapoRetoServiceTests {

	private static final Instant AGORA = Instant.parse("2026-11-17T20:00:00Z");

	private final RepositorioRaso repositorio = new RepositorioRaso();

	private final FonteFalsa fonte = new FonteFalsa("POST");

	private final AutoresFalsos autores = new AutoresFalsos();

	private PapoRetoService service() {
		return serviceCom(BlockedUsersReader.none());
	}

	private PapoRetoService serviceCom(BlockedUsersReader bloqueios) {
		PapoRetoWorker worker = new PapoRetoWorker(List.of(), repositorio, new PapoAssuntoExtractor(),
				Clock.fixed(AGORA, ZoneOffset.UTC), 0.30, 0.25, 0.25, 0.20);
		return new PapoRetoService(repositorio, worker, new PapoAssuntoExtractor(), List.of(fonte), autores,
				bloqueios, Clock.fixed(AGORA, ZoneOffset.UTC));
	}

	private PapoRetoService serviceComFonte(PapoPublicacaoReader fonteUsada) {
		PapoRetoWorker worker = new PapoRetoWorker(List.of(), repositorio, new PapoAssuntoExtractor(),
				Clock.fixed(AGORA, ZoneOffset.UTC), 0.30, 0.25, 0.25, 0.20);
		return new PapoRetoService(repositorio, worker, new PapoAssuntoExtractor(), List.of(fonteUsada), autores,
				BlockedUsersReader.none(), Clock.fixed(AGORA, ZoneOffset.UTC));
	}

	/**
	 * O assunto como o corte grava: as contagens da tela, a janela do escopo e as duas potências
	 * medidas no corte. A janela de publicações é larga de propósito — é ela que deixa a régua de
	 * discriminação julgar, como num corte de verdade.
	 */
	private static PapoAssunto agregado(String subject, long falasPost, long pessoas, double potencia,
			double potenciaAntes) {
		return new PapoAssunto(subject, falasPost, 0, pessoas, 0, AGORA, pessoas, 400, potencia, potenciaAntes);
	}

	/** O agregado com os volumes das duas vésperas medidos — a matéria do crescimento exibido. */
	private static PapoAssunto agregadoComJanelas(String subject, long falasDia, long falasDiaAnterior) {
		return new PapoAssunto(subject, 10, 0, 10, 0, AGORA, 10, 400, 40.0, 39.0, falasDia, falasDiaAnterior);
	}

	@Test
	void assuntoSemGenteSuficienteNaoEntraNoRanking() {
		repositorio.assuntos.add(agregado("meetup", 10, 4, 9, 9));
		repositorio.assuntos.add(agregado("violao", 10, 6, 9, 9));

		List<PapoRetoService.SubjectView> ranking = service().ranking();

		// 4 pessoas é menos que o chão (5): só o violão passa.
		assertThat(ranking).hasSize(1);
		assertThat(ranking.get(0).subject()).isEqualTo("violao");
	}

	@Test
	void rankingOrdenaPorPontuacaoEEmpataPeloSubject() {
		repositorio.assuntos.add(agregado("zebra", 8, 8, 1.0, 1.0));
		repositorio.assuntos.add(agregado("meetup", 10, 10, 10.0, 10.0));
		repositorio.assuntos.add(agregado("violao", 10, 10, 10.0, 10.0));

		List<PapoRetoService.SubjectView> ranking = service().ranking();

		// meetup e violão empatam (mesmos números); o empate cai para o subject.
		assertThat(ranking).extracting(PapoRetoService.SubjectView::subject)
			.containsExactly("meetup", "violao", "zebra");
	}

	@Test
	void rankingDaComunidadeLeOEscopoDaComunidade() {
		repositorio.assuntosPorEscopo.put("com_1", List.of(agregado("mutirao", 10, 7, 9, 9)));
		repositorio.assuntos.add(agregado("meetup", 10, 10, 9, 9));

		assertThat(service().ranking()).extracting(PapoRetoService.SubjectView::subject).containsExactly("meetup");
		assertThat(service().rankingDaComunidade("com_1"))
			.extracting(PapoRetoService.SubjectView::subject)
			.containsExactly("mutirao");
	}

	@Test
	void rankingNaoListaRuidoQueJaEstavaGravado() {
		// O "http" entrou antes da correção do extrator e ainda está na janela: a régua da
		// leitura tira da lista sem esperar a fala expirar.
		repositorio.assuntos.add(agregado("http", 10, 9, 9, 9));
		repositorio.assuntos.add(agregado("meetup", 10, 9, 9, 9));

		assertThat(service().ranking()).extracting(PapoRetoService.SubjectView::subject).containsExactly("meetup");
	}

	@Test
	void palavraOnipresenteNaoEntraMasExpressaoCompostaEntra() {
		// "vida" em 100 das 400 publicações da janela: fala comum da rede, não conversa — mesmo
		// com 30 pessoas e potência alta. A expressão composta onipresente fica (com metade da
		// potência), porque conversa grande e legítima não pode desaparecer.
		repositorio.assuntos.add(new PapoAssunto("vida", 200, 0, 30, 10, AGORA, 100, 400, 40.0, 30.0));
		repositorio.assuntos.add(new PapoAssunto("mercado de trabalho", 200, 0, 30, 10, AGORA, 100, 400, 40.0, 30.0));

		List<PapoRetoService.SubjectView> ranking = service().ranking();

		assertThat(ranking).extracting(PapoRetoService.SubjectView::subject).containsExactly("mercado de trabalho");
		assertThat(ranking.get(0).pontuacao()).isEqualTo(20.0);
	}

	@Test
	void palavraRaraValeMaisQueAPalavraComumDeMesmaPotencia() {
		// Mesma potência, mesma gente: a que aparece em menos publicações da janela é a conversa
		// da comunidade; a que aparece em muitas é a fala de todo mundo.
		repositorio.assuntos.add(new PapoAssunto("pangolim", 30, 0, 8, 2, AGORA, 8, 400, 12.0, 12.0));
		repositorio.assuntos.add(new PapoAssunto("cadeira", 30, 0, 8, 2, AGORA, 50, 400, 12.0, 12.0));

		List<PapoRetoService.SubjectView> ranking = service().ranking();

		assertThat(ranking).extracting(PapoRetoService.SubjectView::subject)
			.containsExactly("pangolim", "cadeira");
		assertThat(ranking.get(0).pontuacao()).isGreaterThan(ranking.get(1).pontuacao());
	}

	@Test
	void linhaDaReguaAntigaContinuaSendoLida() {
		// A linha gravada antes da virada não tem potência nem publicações: ela é lida pela régua
		// antiga até o primeiro corte reescrevê-la, e não some da tela no meio do caminho.
		String estadoAntigo = "{\"falasPost\":10,\"falasComentario\":0,\"pessoas\":9,\"falasUltimaHora\":0,"
				+ "\"ultimaFalaEpoch\":" + AGORA.getEpochSecond() + "}";
		repositorio.assuntos.add(PapoAssunto.de("meetup", estadoAntigo));

		List<PapoRetoService.SubjectView> ranking = service().ranking();

		assertThat(ranking).hasSize(1);
		assertThat(ranking.get(0).subject()).isEqualTo("meetup");
		assertThat(ranking.get(0).pontuacao()).isGreaterThan(0);
	}

	@Test
	void statusECrescimentoSaemDaTrajetoriaMedidaNoCorte() {
		repositorio.assuntos.add(agregado("violao", 10, 10, 40.0, 1.0));
		repositorio.assuntos.add(agregado("mercado", 10, 10, 40.0, 39.0));

		List<PapoRetoService.SubjectView> ranking = service().ranking();

		// A pontuação empatou (mesmos números); o status, não: nasceu agora × sustentou.
		assertThat(statusDe(ranking, "violao")).isEqualTo("EMERGENTE");
		assertThat(statusDe(ranking, "mercado")).isEqualTo("PERSISTENTE");
		// A linha sem janelas medidas (legada ou de teste) não inventa percentual.
		assertThat(crescimentoDe(ranking, "violao")).isZero();
		assertThat(crescimentoDe(ranking, "mercado")).isZero();
	}

	@Test
	void crescimentoEhAProporcaoDeVolumeRealDasDuasVesperas() {
		// Seis falas hoje contra duas ontem: cresceu três vezes — +200%, e não o número da
		// potência (que soma gente e decaimento e não é proporção de nada).
		repositorio.assuntos.add(agregadoComJanelas("foguete", 6, 2));
		repositorio.assuntos.add(agregadoComJanelas("queda", 2, 8));
		repositorio.assuntos.add(agregadoComJanelas("estavel", 4, 4));

		List<PapoRetoService.SubjectView> ranking = rankingPor("foguete", "queda", "estavel");

		assertThat(crescimentoDe(ranking, "foguete")).isEqualTo(200.0);
		// Queda não vira percentual negativo na tela: estável.
		assertThat(crescimentoDe(ranking, "queda")).isZero();
		assertThat(crescimentoDe(ranking, "estavel")).isZero();
	}

	@Test
	void crescimentoNuloQuandoNaoHaVesperaComparavel() {
		// O assunto nasceu hoje: a véspera é zero e o denominador seria zero — dividir inventaria
		// "+100%" (foi o "+127%" da produção). Nulo diz "novo em alta".
		repositorio.assuntos.add(agregadoComJanelas("novidade", 6, 0));

		List<PapoRetoService.SubjectView> ranking = service().ranking();

		assertThat(ranking.get(0).crescimento()).isNull();
	}

	@Test
	void crescimentoSemFalaNenhumaNosDoisDiasViraZero() {
		repositorio.assuntos.add(agregadoComJanelas("quieto", 0, 0));

		List<PapoRetoService.SubjectView> ranking = service().ranking();

		assertThat(ranking.get(0).crescimento()).isZero();
	}

	/** Os subjects do teste, na ordem pedida (os fixtures têm pontuação empatada). */
	private List<PapoRetoService.SubjectView> rankingPor(String... subjects) {
		List<PapoRetoService.SubjectView> ranking = service().ranking();
		List<PapoRetoService.SubjectView> naOrdem = new ArrayList<>();
		for (String subject : subjects) {
			ranking.stream()
				.filter(v -> v.subject().equals(subject))
				.findFirst()
				.ifPresent(naOrdem::add);
		}
		return naOrdem;
	}

	/** O status do subject no ranking, sem depender da ordem do empate. */
	private String statusDe(List<PapoRetoService.SubjectView> ranking, String subject) {
		return ranking.stream()
			.filter(v -> v.subject().equals(subject))
			.findFirst()
			.orElseThrow()
			.status();
	}

	private static Double crescimentoDe(List<PapoRetoService.SubjectView> ranking, String subject) {
		return ranking.stream()
			.filter(v -> v.subject().equals(subject))
			.findFirst()
			.orElseThrow()
			.crescimento();
	}

	@Test
	void timelineDevolveOsBucketsDaJanela() {
		repositorio.intervalos.add(new PapoIntervalo("", "meetup", AGORA.minusSeconds(3600), 3));
		repositorio.intervalos.add(new PapoIntervalo("", "meetup", AGORA.minusSeconds(7200), 5));
		repositorio.intervalos.add(new PapoIntervalo("", "meetup", AGORA.minusSeconds(3600 * 30), 9));

		List<PapoRetoService.IntervaloView> timeline = service().timeline("", "meetup",
				java.time.Duration.ofHours(24));

		// A janela de 24h pega os dois buckets recentes; o de 30 horas fica fora.
		assertThat(timeline).hasSize(2);
		assertThat(timeline.get(0).hora()).isEqualTo(AGORA.minusSeconds(7200).toString());
		assertThat(timeline.get(0).falas()).isEqualTo(5);
	}

	@Test
	void falasAnalisadasContaAJanela() {
		repositorio.totalFalas = 42;

		assertThat(service().falasAnalisadas()).isEqualTo(42);
	}

	@Test
	void publicacaoRelacionadaTrazTextoAutorELink() {
		repositorio.publicacoes.add(new PapoRetoRepository.PublicacaoDaFala("pub_1", "POST", null, AGORA));
		fonte.detalhes.put("pub_1", new PapoPublicacaoReader.Detalhe("pub_1", "cogumelo magico no quintal",
				"autor_1", AGORA, null, null));
		autores.porId.put("autor_1", new PapoAutorReader.Autor("Mel", "melmonfre"));

		List<PapoRetoService.PublicacaoRelacionadaView> relacionadas = service()
			.publicacoesRelacionadas("", "cogumelo magico", Duration.ofDays(7), "visitante");

		assertThat(relacionadas).hasSize(1);
		PapoRetoService.PublicacaoRelacionadaView view = relacionadas.get(0);
		assertThat(view.texto()).isEqualTo("cogumelo magico no quintal");
		assertThat(view.autor()).isEqualTo("Mel");
		assertThat(view.handle()).isEqualTo("melmonfre");
		assertThat(view.href()).isEqualTo("/post/pub_1");
		assertThat(view.anonima()).isFalse();
	}

	@Test
	void desabafoSaiSemAutorEComLinkDoEspaco() {
		FonteFalsa desabafos = new FonteFalsa("DESABAFO");
		desabafos.detalhes.put("des_1", new PapoPublicacaoReader.Detalhe("des_1", "cansada do trabalho",
				"autor_2", AGORA, null, null));
		repositorio.publicacoes.add(new PapoRetoRepository.PublicacaoDaFala("des_1", "DESABAFO", null, AGORA));
		autores.porId.put("autor_2", new PapoAutorReader.Autor("Mel", "melmonfre"));

		List<PapoRetoService.PublicacaoRelacionadaView> relacionadas = serviceComFonte(desabafos)
			.publicacoesRelacionadas("", "trabalho", Duration.ofDays(7), "visitante");

		// O anonimato é do espaço: o nome existe no perfil, mas nunca chega à view.
		assertThat(relacionadas).hasSize(1);
		assertThat(relacionadas.get(0).autor()).isNull();
		assertThat(relacionadas.get(0).handle()).isNull();
		assertThat(relacionadas.get(0).anonima()).isTrue();
		assertThat(relacionadas.get(0).href()).isEqualTo("/desabafos");
	}

	@Test
	void comentarioAbreADiscussaoDoPost() {
		FonteFalsa comentarios = new FonteFalsa("COMMENT");
		comentarios.detalhes.put("cmt_1", new PapoPublicacaoReader.Detalhe("cmt_1", "cogumelo magico tambem",
				"autor_1", AGORA, null, "post_9"));
		repositorio.publicacoes.add(new PapoRetoRepository.PublicacaoDaFala("cmt_1", "COMMENT", null, AGORA));

		List<PapoRetoService.PublicacaoRelacionadaView> relacionadas = serviceComFonte(comentarios)
			.publicacoesRelacionadas("", "cogumelo magico", Duration.ofDays(7), "visitante");

		assertThat(relacionadas.get(0).href()).isEqualTo("/post/post_9");
	}

	@Test
	void bloqueadoNaoApareceNasRelacionadas() {
		repositorio.publicacoes.add(new PapoRetoRepository.PublicacaoDaFala("pub_1", "POST", null, AGORA));
		fonte.detalhes.put("pub_1", new PapoPublicacaoReader.Detalhe("pub_1", "cogumelo magico",
				"bloqueado", AGORA, null, null));
		BlockedUsersReader escondendo = new BlockedUsersReader() {
			@Override
			public Set<String> hiddenIdsFor(String userId) {
				return Set.of("bloqueado");
			}

			@Override
			public boolean blockedBetween(String userA, String userB) {
				return false;
			}

			@Override
			public Set<String> blockedIdsFor(String userId) {
				return Set.of();
			}
		};

		assertThat(serviceCom(escondendo).publicacoesRelacionadas("", "cogumelo magico", Duration.ofDays(7),
				"visitante"))
			.isEmpty();
	}

	@Test
	void banidoSaiSemNome() {
		repositorio.publicacoes.add(new PapoRetoRepository.PublicacaoDaFala("pub_1", "POST", null, AGORA));
		fonte.detalhes.put("pub_1", new PapoPublicacaoReader.Detalhe("pub_1", "cogumelo magico",
				"autor_1", AGORA, null, null));
		autores.porId.put("autor_1", new PapoAutorReader.Autor("Mel", "melmonfre"));
		autores.banidos.add("autor_1");

		List<PapoRetoService.PublicacaoRelacionadaView> relacionadas = service()
			.publicacoesRelacionadas("", "cogumelo magico", Duration.ofDays(7), "visitante");

		assertThat(relacionadas).hasSize(1);
		assertThat(relacionadas.get(0).autor()).isNull();
		assertThat(relacionadas.get(0).handle()).isNull();
	}

	@Test
	void assuntoDeRuidoNaoAbrePublicacoes() {
		repositorio.publicacoes.add(new PapoRetoRepository.PublicacaoDaFala("pub_1", "POST", null, AGORA));
		fonte.detalhes.put("pub_1", new PapoPublicacaoReader.Detalhe("pub_1", "http no ranking",
				"autor_1", AGORA, null, null));

		assertThat(service().publicacoesRelacionadas("", "http", Duration.ofDays(7), "visitante")).isEmpty();
	}

	@Test
	void previaCortaTextoLongo() {
		String longo = "a".repeat(400);
		repositorio.publicacoes.add(new PapoRetoRepository.PublicacaoDaFala("pub_1", "POST", null, AGORA));
		fonte.detalhes.put("pub_1", new PapoPublicacaoReader.Detalhe("pub_1", longo, "autor_1", AGORA, null, null));

		String texto = service().publicacoesRelacionadas("", "cogumelo magico", Duration.ofDays(7), "visitante")
			.get(0)
			.texto();

		assertThat(texto).hasSize(280).endsWith("…");
	}

	/** A fonte falsa: só os detalhes, que é o que a leitura das relacionadas usa. */
	private static final class FonteFalsa implements PapoPublicacaoReader {

		private final String fonte;

		private final Map<String, Detalhe> detalhes = new HashMap<>();

		private FonteFalsa(String fonte) {
			this.fonte = fonte;
		}

		@Override
		public List<Publicacao> desde(Instant de, Instant ate) {
			return List.of();
		}

		@Override
		public List<Detalhe> detalhes(Collection<String> publicacaoIds) {
			return publicacaoIds.stream().map(detalhes::get).filter(java.util.Objects::nonNull).toList();
		}

		@Override
		public String fonte() {
			return fonte;
		}

	}

	/** A autoria falsa: o mapa de perfis e o conjunto de banidos do teste. */
	private static final class AutoresFalsos implements PapoAutorReader {

		private final Map<String, Autor> porId = new HashMap<>();

		private final Set<String> banidos = new HashSet<>();

		@Override
		public Map<String, Autor> autores(Collection<String> ids) {
			Map<String, Autor> autores = new HashMap<>();
			for (String id : ids) {
				if (porId.containsKey(id)) {
					autores.put(id, porId.get(id));
				}
			}
			return autores;
		}

		@Override
		public Set<String> banidos(Collection<String> ids) {
			return ids.stream().filter(banidos::contains).collect(Collectors.toSet());
		}

	}

	/** O repositório raso: só o que o service lê. */
	private static final class RepositorioRaso implements PapoRetoRepository {

		List<PapoAssunto> assuntos = new ArrayList<>();

		Map<String, List<PapoAssunto>> assuntosPorEscopo = new java.util.LinkedHashMap<>();

		List<PapoIntervalo> intervalos = new ArrayList<>();

		List<PublicacaoDaFala> publicacoes = new ArrayList<>();

		long totalFalas;

		@Override
		public void salvarFalas(List<Fala> falas) {
		}

		@Override
		public List<Fala> falasEntre(Instant de, Instant ate) {
			return List.of();
		}

		@Override
		public List<PapoTemaFala> temasDePublicacao(String publicacaoId) {
			return List.of();
		}

		@Override
		public Optional<Instant> ultimaFronteira() {
			return Optional.empty();
		}

		@Override
		public void salvarAssuntos(String escopo, List<PapoAssunto> novos) {
			assuntosPorEscopo.put(escopo, novos);
		}

		@Override
		public List<PapoAssunto> assuntosDoEscopo(String escopo) {
			if (assuntosPorEscopo.containsKey(escopo)) {
				return assuntosPorEscopo.get(escopo);
			}
			return escopo.isEmpty() ? assuntos : List.of();
		}

		@Override
		public Optional<PapoAssunto> assuntoDe(String escopo, String subject) {
			return assuntosDoEscopo(escopo).stream().filter(a -> a.subject().equals(subject)).findFirst();
		}

		@Override
		public void apagarAssuntosSemFala(String escopo) {
		}

		@Override
		public void reconciliarIntervalos(String escopo, List<PapoIntervalo> contagemDoCorte) {
		}

		@Override
		public List<PapoIntervalo> intervalosDe(String escopo, String subject, Instant de, Instant ate) {
			return intervalos.stream()
				.filter(i -> i.escopo().equals(escopo) && i.subject().equals(subject) && !i.hora().isBefore(de)
						&& i.hora().isBefore(ate))
				.collect(Collectors.toCollection(ArrayList::new));
		}

		@Override
		public List<PublicacaoDaFala> publicacoesDoAssunto(String escopo, String subject, Instant de, Instant ate,
				int limite) {
			return publicacoes.stream().limit(limite).toList();
		}

		@Override
		public long falasTotais(Instant de, Instant ate) {
			return totalFalas;
		}

	}

}
