package com.socialcoisa.rede.paporeto.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.paporeto.application.port.out.PapoPublicacaoReader;
import com.socialcoisa.rede.paporeto.application.port.out.PapoRetoRepository;
import com.socialcoisa.rede.paporeto.domain.PapoAssunto;
import com.socialcoisa.rede.paporeto.domain.PapoIntervalo;
import com.socialcoisa.rede.paporeto.domain.PapoStatus;
import com.socialcoisa.rede.paporeto.domain.PapoTemaFala;

/**
 * O corte do Papo Reto: leitura incremental com dedup por publicação, chão de pessoas
 * distintas, escopo global e por comunidade, e a propriedade que impede contagem dupla na
 * timeline — o mesmo bucket volta ao mesmo número, mesmo com a fonte relendo publicação.
 */
class PapoRetoWorkerTests {

	private static final Instant T0 = Instant.parse("2026-11-17T18:00:00Z");

	/** A fonte falsa: publica na mão e respeita (ou não) a fronteira de leitura. */
	private static final class FonteFalsa implements PapoPublicacaoReader {

		private List<PapoPublicacaoReader.Publicacao> publicacoes = List.of();

		private boolean ignoraFronteira = false;

		@Override
		public List<PapoPublicacaoReader.Publicacao> desde(Instant de, Instant ate) {
			if (ignoraFronteira) {
				// Simula uma fonte que relê tudo (recomeço do horizonte): o dedup do worker
				// é quem impede a publicação de falar duas vezes.
				return publicacoes.stream().filter(p -> p.criadoEm().isBefore(ate)).toList();
			}
			return publicacoes.stream()
				.filter(p -> !p.criadoEm().isBefore(de) && p.criadoEm().isBefore(ate))
				.toList();
		}

		@Override
		public List<PapoPublicacaoReader.Detalhe> detalhes(java.util.Collection<String> publicacaoIds) {
			return List.of();
		}

		@Override
		public String fonte() {
			return "POST";
		}

	}

	/** O repositório falso: falas, assuntos por escopo e intervalos na memória do teste. */
	private static final class RepositorioFalso implements PapoRetoRepository {

		List<Fala> falas = new ArrayList<>();

		Map<String, List<PapoAssunto>> assuntosPorEscopo = new LinkedHashMap<>();

		Map<String, PapoIntervalo> intervalos = new LinkedHashMap<>();

		@Override
		public void salvarFalas(List<Fala> novas) {
			falas.addAll(novas);
		}

		@Override
		public List<Fala> falasEntre(Instant de, Instant ate) {
			return falas.stream().filter(f -> !f.hora().isBefore(de) && f.hora().isBefore(ate)).toList();
		}

		@Override
		public List<PapoTemaFala> temasDePublicacao(String publicacaoId) {
			return falas.stream()
				.filter(f -> f.publicacaoId().equals(publicacaoId))
				.map(f -> new PapoTemaFala(f.expressao(), f.fonte()))
				.toList();
		}

		@Override
		public Optional<Instant> ultimaFronteira() {
			return falas.stream().map(PapoRetoRepository.Fala::lidaEm).max(Instant::compareTo);
		}

		@Override
		public void salvarAssuntos(String escopo, List<PapoAssunto> novos) {
			assuntosPorEscopo.put(escopo, novos);
		}

		@Override
		public List<PapoAssunto> assuntosDoEscopo(String escopo) {
			return assuntosPorEscopo.getOrDefault(escopo, List.of());
		}

		@Override
		public Optional<PapoAssunto> assuntoDe(String escopo, String subject) {
			return assuntosDoEscopo(escopo).stream()
				.filter(a -> a.subject().equals(subject))
				.findFirst();
		}

		@Override
		public void apagarAssuntosSemFala(String escopo) {
			List<PapoAssunto> vivos = assuntosDoEscopo(escopo).stream()
				.filter(a -> a.falasPost() + a.falasComentario() > 0)
				.toList();
			if (!vivos.isEmpty() || assuntosPorEscopo.containsKey(escopo)) {
				assuntosPorEscopo.put(escopo, vivos);
			}
		}

		@Override
		public void reconciliarIntervalos(String escopo, List<PapoIntervalo> contagemDoCorte) {
			intervalos.keySet().removeIf(id -> intervalos.get(id).escopo().equals(escopo));
			for (PapoIntervalo intervalo : contagemDoCorte) {
				intervalos.put(escopo + "@" + intervalo.subject() + "@" + intervalo.hora(), intervalo);
			}
		}

		@Override
		public List<PapoIntervalo> intervalosDe(String escopo, String subject, Instant de, Instant ate) {
			return intervalos.values().stream()
				.filter(i -> i.escopo().equals(escopo) && i.subject().equals(subject) && !i.hora().isBefore(de)
						&& i.hora().isBefore(ate))
				.toList();
		}

		@Override
		public List<PublicacaoDaFala> publicacoesDoAssunto(String escopo, String subject, Instant de, Instant ate,
				int limite) {
			return falas.stream()
				.filter(f -> f.expressao().equals(subject) && !f.hora().isBefore(de) && f.hora().isBefore(ate))
				.filter(f -> escopo.isEmpty() || escopo.equals(f.communityId()))
				.collect(Collectors.toMap(Fala::publicacaoId,
						f -> new PublicacaoDaFala(f.publicacaoId(), f.fonte(), f.communityId(), f.hora()),
						(primeira, segunda) -> primeira))
				.values()
				.stream()
				.limit(limite)
				.toList();
		}

		@Override
		public long falasTotais(Instant de, Instant ate) {
			return falasEntre(de, ate).size();
		}

	}

	private final FonteFalsa fonte = new FonteFalsa();

	private final RepositorioFalso repositorio = new RepositorioFalso();

	private PapoRetoWorker workerNo(Instant agora) {
		return new PapoRetoWorker(List.of(fonte), repositorio, new PapoAssuntoExtractor(),
				Clock.fixed(agora, ZoneOffset.UTC), 0.30, 0.25, 0.25, 0.20);
	}

	@Test
	void gravaSomenteAExpressaoNaoOTexto() {
		fonte.publicacoes = List.of(new PapoPublicacaoReader.Publicacao("inteligencia artificial mudou meu trabalho", "autor_1", T0,
				null, "pub_1"));

		workerNo(T0.plusSeconds(300)).corte();

		assertThat(repositorio.falas).isNotEmpty();
		// A fala é o assunto do cluster: o bigrama cobre os seus unigramas na publicação.
		assertThat(repositorio.falas.stream().map(PapoRetoRepository.Fala::expressao))
			.contains("inteligencia artificial", "trabalho");
		// Nenhuma fala guarda o texto original nem um pedaço dele.
		assertThat(repositorio.falas.stream().map(PapoRetoRepository.Fala::expressao))
			.noneMatch(expressao -> expressao.contains("mudou meu"));
	}

	@Test
	void tagDaPublicacaoViraAssuntoDeclarado() {
		fonte.publicacoes = List.of(new PapoPublicacaoReader.Publicacao("foto do setup novo", "autor_1", T0, null,
				"pub_1", List.of("Home Office", "http://exemplo.com")));

		workerNo(T0.plusSeconds(300)).corte();

		// A tag é assunto declarado pelo autor — entra mesmo sem aparecer no texto; a tag de
		// link é ruído e não nasce fala.
		assertThat(repositorio.falas.stream().map(PapoRetoRepository.Fala::expressao))
			.contains("home office")
			.doesNotContain("http");
	}

	@Test
	void linkEComemoracaoNaoViramAssunto() {
		fonte.publicacoes = List.of(new PapoPublicacaoReader.Publicacao(
				"https://enois.ia.br/papo?utm=abc kkkkk :KEKW:", "autor_1", T0, null, "pub_1"));

		workerNo(T0.plusSeconds(300)).corte();

		assertThat(repositorio.falas).isEmpty();
	}

	@Test
	void umaSoPessoaNaoViraAlta() {
		fonte.publicacoes = List.of(
			new PapoPublicacaoReader.Publicacao("meetup de rust na sexta", "autor_1", T0, null, "pub_1"),
			new PapoPublicacaoReader.Publicacao("meetup de rust na sexta", "autor_1", T0.plusSeconds(600), null, "pub_2"));

		workerNo(T0.plusSeconds(1200)).corte();

		PapoAssunto meetup = repositorio.assuntoDe(PapoRetoWorker.ESCOPO_GLOBAL, "meetup").orElseThrow();
		assertThat(meetup.pessoas()).isEqualTo(1);
		assertThat(meetup.pessoas()).isLessThan(PapoAssunto.MINIMO_PESSOAS);
	}

	@Test
	void pessoasDistintasSaoContadasUmaVez() {
		List<PapoPublicacaoReader.Publicacao> deSeisPessoas = new ArrayList<>();
		for (int i = 1; i <= 6; i++) {
			deSeisPessoas.add(new PapoPublicacaoReader.Publicacao("meetup de rust na sexta", "autor_" + i, T0, null, "pub_" + i));
		}
		fonte.publicacoes = deSeisPessoas;

		workerNo(T0.plusSeconds(300)).corte();

		PapoAssunto meetup = repositorio.assuntoDe(PapoRetoWorker.ESCOPO_GLOBAL, "meetup").orElseThrow();
		assertThat(meetup.pessoas()).isEqualTo(6);
		assertThat(meetup.falasPost()).isGreaterThanOrEqualTo(6);
	}

	@Test
	void fonteQueReleNaoDuplicaFalaNemTimeline() {
		fonte.ignoraFronteira = true;
		fonte.publicacoes = List.of(new PapoPublicacaoReader.Publicacao("meetup de rust na sexta", "autor_1", T0, null, "pub_1"));

		workerNo(T0.plusSeconds(300)).corte();
		int falasAposPrimeiroCorte = repositorio.falas.size();
		long falasDoBucket = repositorio.intervalos.values().stream()
			.filter(i -> i.subject().equals("meetup"))
			.mapToLong(PapoIntervalo::falas)
			.sum();

		// O segundo corte relê a MESMA publicação: sem fala nova e com o MESMO número de
		// timeline — a reconciliação substitui, não soma.
		workerNo(T0.plusSeconds(600)).corte();

		assertThat(repositorio.falas).hasSize(falasAposPrimeiroCorte);
		long falasDoBucketDepois = repositorio.intervalos.values().stream()
			.filter(i -> i.subject().equals("meetup"))
			.mapToLong(PapoIntervalo::falas)
			.sum();
		assertThat(falasDoBucketDepois).isEqualTo(falasDoBucket);
	}

	@Test
	void escopoDaComunidadeESeparadoDoGlobal() {
		fonte.publicacoes = List.of(
			new PapoPublicacaoReader.Publicacao("meetup de rust na sexta", "autor_1", T0, "com_1", "pub_1"),
			new PapoPublicacaoReader.Publicacao("meetup de rust na sexta", "autor_2", T0, "com_1", "pub_2"),
			new PapoPublicacaoReader.Publicacao("meetup de rust na sexta", "autor_3", T0, "com_1", "pub_3"),
			new PapoPublicacaoReader.Publicacao("meetup de rust na sexta", "autor_4", T0, "com_1", "pub_4"),
			new PapoPublicacaoReader.Publicacao("meetup de rust na sexta", "autor_5", T0, "com_1", "pub_5"),
			new PapoPublicacaoReader.Publicacao("aula de violao hoje", "autor_6", T0, null, "pub_6"),
			new PapoPublicacaoReader.Publicacao("aula de violao hoje", "autor_7", T0, null, "pub_7"));

		workerNo(T0.plusSeconds(300)).corte();

		// No global, o meetup tem 5 pessoas; na comunidade, o mesmo. O violão é só do feed
		// geral: não existe no escopo da comunidade. "aula" e "violao" não colam em bigrama
		// porque o "de" está no meio — o subject é o unigrama.
		assertThat(repositorio.assuntoDe(PapoRetoWorker.ESCOPO_GLOBAL, "meetup").orElseThrow().pessoas())
			.isEqualTo(5);
		assertThat(repositorio.assuntoDe("com_1", "meetup").orElseThrow().pessoas()).isEqualTo(5);
		assertThat(repositorio.assuntoDe(PapoRetoWorker.ESCOPO_GLOBAL, "violao").orElseThrow().pessoas())
			.isEqualTo(2);
		assertThat(repositorio.assuntoDe("com_1", "violao")).isEmpty();
	}

	@Test
	void comentarioContaComoComentarioNoAgregado() {
		PapoPublicacaoReader comentarios = new PapoPublicacaoReader() {
			@Override
			public List<PapoPublicacaoReader.Publicacao> desde(Instant de, Instant ate) {
				List<PapoPublicacaoReader.Publicacao> publicacoes = new ArrayList<>();
				for (int i = 1; i <= 6; i++) {
					publicacoes.add(new PapoPublicacaoReader.Publicacao("meetup de rust", "comentador_" + i, T0, null, "cmt_" + i));
				}
				return publicacoes;
			}

			@Override
			public List<PapoPublicacaoReader.Detalhe> detalhes(java.util.Collection<String> publicacaoIds) {
				return List.of();
			}

			@Override
			public String fonte() {
				return "COMMENT";
			}
		};
		List<PapoPublicacaoReader.Publicacao> deSeisPessoas = new ArrayList<>();
		for (int i = 1; i <= 6; i++) {
			deSeisPessoas.add(new PapoPublicacaoReader.Publicacao("meetup de rust na sexta", "autor_" + i, T0, null, "pub_" + i));
		}
		fonte.publicacoes = deSeisPessoas;
		PapoRetoWorker workerComComentarios = new PapoRetoWorker(List.of(fonte, comentarios), repositorio,
				new PapoAssuntoExtractor(), Clock.fixed(T0.plusSeconds(300), ZoneOffset.UTC), 0.30, 0.25, 0.25,
				0.20);

		workerComComentarios.corte();

		PapoAssunto meetup = repositorio.assuntoDe(PapoRetoWorker.ESCOPO_GLOBAL, "meetup").orElseThrow();
		assertThat(meetup.falasPost()).isGreaterThanOrEqualTo(6);
		assertThat(meetup.falasComentario()).isGreaterThanOrEqualTo(6);
		assertThat(meetup.pessoas()).isEqualTo(12);
	}

	@Test
	void timelineGuardaOBucketDaHora() {
		List<PapoPublicacaoReader.Publicacao> publicacoes = new ArrayList<>();
		for (int i = 1; i <= 6; i++) {
			publicacoes.add(new PapoPublicacaoReader.Publicacao("meetup de rust na sexta", "autor_" + i, T0.plusSeconds(i), null,
					"pub_" + i));
		}
		fonte.publicacoes = publicacoes;

		workerNo(T0.plusSeconds(3600)).corte();

		List<PapoIntervalo> doMeetup = repositorio.intervalosDe(PapoRetoWorker.ESCOPO_GLOBAL, "meetup",
				T0.minusSeconds(60), T0.plusSeconds(3600));
		assertThat(doMeetup).hasSize(1);
		assertThat(doMeetup.get(0).hora()).isEqualTo(Instant.parse("2026-11-17T18:00:00Z"));
		assertThat(doMeetup.get(0).falas()).isGreaterThanOrEqualTo(6);
	}

	@Test
	void bucketDaHoraArredondaParaBaixo() {
		assertThat(PapoRetoWorker.bucketDaHora(Instant.parse("2026-11-17T18:37:42Z")))
			.isEqualTo(Instant.parse("2026-11-17T18:00:00Z"));
		assertThat(PapoRetoWorker.bucketDaHora(T0)).isEqualTo(T0);
	}

	@Test
	void pontuacaoDecaiComAIdadeDaUltimaFala() {
		PapoAssunto fresco = new PapoAssunto("meetup", 10, 0, 10, 5, T0);
		PapoAssunto velho = new PapoAssunto("meetup", 10, 0, 10, 5, T0.minusSeconds(PapoAssunto.MEIA_VIDA.getSeconds()));

		double potenciaFresca = workerNo(T0).pontuacaoDe(fresco, T0);
		double potenciaVelha = workerNo(T0).pontuacaoDe(velho, T0);

		assertThat(potenciaVelha).isLessThan(potenciaFresca);
		// Uma meia-vida atrás, a parte das fontes vale metade; a gente e o crescimento não decaem.
		assertThat(potenciaVelha).isGreaterThan(potenciaFresca * 0.5);
	}

	@Test
	void statusLeATrajetoriaDoAssuntoNasTresFaixas() {
		// Nasceu agora: o antes era quase nada (ou nada).
		assertThat(PapoStatus.de(100.0, 10.0)).isEqualTo(PapoStatus.EMERGENTE);
		assertThat(PapoStatus.de(100.0, 0.0)).isEqualTo(PapoStatus.EMERGENTE);
		// Esquentou de verdade: o antes existia, mas longe do tamanho de hoje.
		assertThat(PapoStatus.de(100.0, 50.0)).isEqualTo(PapoStatus.HOT);
		// Sustentou: o antes já era praticamente o tamanho de hoje.
		assertThat(PapoStatus.de(100.0, 80.0)).isEqualTo(PapoStatus.PERSISTENTE);
		assertThat(PapoStatus.de(100.0, 120.0)).isEqualTo(PapoStatus.PERSISTENTE);
	}

}
