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

	@Test
	void assuntoSemGenteSuficienteNaoEntraNoRanking() {
		repositorio.assuntos.add(new PapoAssunto("meetup", 10, 0, 4, 0, AGORA));
		repositorio.assuntos.add(new PapoAssunto("violao", 10, 0, 6, 0, AGORA));

		List<PapoRetoService.SubjectView> ranking = service().ranking();

		// 4 pessoas é menos que o chão (5): só o violão passa.
		assertThat(ranking).hasSize(1);
		assertThat(ranking.get(0).subject()).isEqualTo("violao");
	}

	@Test
	void rankingOrdenaPorPontuacaoEEmpataPeloSubject() {
		repositorio.assuntos.add(new PapoAssunto("zebra", 8, 0, 8, 0, AGORA));
		repositorio.assuntos.add(new PapoAssunto("meetup", 10, 0, 10, 5, AGORA));
		repositorio.assuntos.add(new PapoAssunto("violao", 10, 0, 10, 5, AGORA));

		List<PapoRetoService.SubjectView> ranking = service().ranking();

		// meetup e violão empatam (mesmos números); o empate cai para o subject.
		assertThat(ranking).extracting(PapoRetoService.SubjectView::subject)
			.containsExactly("meetup", "violao", "zebra");
	}

	@Test
	void rankingDaComunidadeLeOEscopoDaComunidade() {
		repositorio.assuntosPorEscopo.put("com_1", List.of(new PapoAssunto("mutirao", 10, 0, 7, 0, AGORA)));
		repositorio.assuntos.add(new PapoAssunto("meetup", 10, 0, 10, 0, AGORA));

		assertThat(service().ranking()).extracting(PapoRetoService.SubjectView::subject).containsExactly("meetup");
		assertThat(service().rankingDaComunidade("com_1"))
			.extracting(PapoRetoService.SubjectView::subject)
			.containsExactly("mutirao");
	}

	@Test
	void rankingNaoListaRuidoQueJaEstavaGravado() {
		// O "http" entrou antes da correção do extrator e ainda está na janela: a régua da
		// leitura tira da lista sem esperar a fala expirar.
		repositorio.assuntos.add(new PapoAssunto("http", 10, 0, 9, 0, AGORA));
		repositorio.assuntos.add(new PapoAssunto("meetup", 10, 0, 9, 0, AGORA));

		assertThat(service().ranking()).extracting(PapoRetoService.SubjectView::subject).containsExactly("meetup");
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
