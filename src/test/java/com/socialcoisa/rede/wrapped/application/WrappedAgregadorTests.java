package com.socialcoisa.rede.wrapped.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.paporeto.application.PapoAssuntoExtractor;
import com.socialcoisa.rede.wrapped.application.port.out.WrappedAssuntos;
import com.socialcoisa.rede.wrapped.application.port.out.WrappedComunidadesRepository;
import com.socialcoisa.rede.wrapped.application.port.out.WrappedEvento;
import com.socialcoisa.rede.wrapped.application.port.out.WrappedEvento.Tipo;
import com.socialcoisa.rede.wrapped.domain.WrappedAno;
import com.socialcoisa.rede.wrapped.domain.WrappedPerfil.Chave;
import com.socialcoisa.rede.wrapped.domain.WrappedRetrospectiva;

/**
 * O reconhecimento de padrões da retrospectiva, sem banco: a leitura é simulada por eventos.
 *
 * O centro dos testes é o que o briefing exige do agregador: só fatos da própria conta contam,
 * evento de OUTRA conta não atravessa (isolamento), conta sem histórico sai marcada como
 * insuficiente (nunca fabricada), as fronteiras do ano são respeitadas e os padrões (perfil,
 * tretas, assuntos) saem de critérios objetivos e determinísticos.
 */
class WrappedAgregadorTests {

	private static final WrappedAno ANO = WrappedAno.de(2026);
	private static final String EU = "user_eu";

	private static final WrappedAssuntos ASSUNTOS = (textos, limite) -> {
		var extrator = new PapoAssuntoExtractor();
		var contagem = new java.util.LinkedHashMap<String, Integer>();
		for (String texto : textos) {
			var faltas = extrator.extrair(texto, "POST");
			for (var fala : faltas) {
				contagem.merge(fala.expressao(), 1, Integer::sum);
			}
		}
		return contagem.entrySet().stream()
				.sorted(java.util.Map.Entry.<String, Integer>comparingByValue().reversed()
						.thenComparing(java.util.Map.Entry.comparingByKey()))
				.limit(limite)
				.map(e -> new com.socialcoisa.rede.wrapped.domain.WrappedAssunto(e.getKey(), e.getValue()))
				.toList();
	};

	private static final WrappedComunidadesRepository SEM_COMUNIDADES = (userId, de, ate) -> List.of();

	@Test
	void contaSemAtividadeSaiComoDadosInsuficientes() {
		WrappedRetrospectiva r = agregar(List.of());

		assertThat(r.dadosInsuficientes()).isTrue();
		assertThat(r.metricas().semAtividade()).isTrue();
		assertThat(r.tretas()).isEmpty();
		assertThat(r.publicacoesMarcantes()).isEmpty();
		assertThat(r.perfil().caracteristicas()).isEmpty();
	}

	@Test
	void soContaOsFatosDaContaEDoAno() {
		Instant dentro = Instant.parse("2026-05-10T15:00:00Z");

		WrappedRetrospectiva r = agregar(List.of(
				publicacao("p1", dentro, "programação em java"),
				comentario("p2", "user_outro", dentro.plusSeconds(60)),
				reacaoFeita("p2", "user_outro", dentro.plusSeconds(120)),
				// Fora do ano: não pode entrar (o fim é 26/12 00:00 local = 03:00Z do dia 26).
				publicacao("p3", Instant.parse("2026-12-27T12:00:00Z"), "fora do ano")));

		assertThat(r.metricas().publicacoes()).isEqualTo(1);
		assertThat(r.metricas().comentariosProprios()).isZero();
		assertThat(r.metricas().reacoesFeitas()).isEqualTo(1);
		assertThat(r.publicacoesMarcantes()).extracting("id").containsExactly("p2");
	}

	@Test
	void eventoDaVesperaDoAnoNovoNaoEntraNoAnoAnterior() {
		// 25/12 23:59 local = 2026-12-26T02:59Z: ainda dentro do ano.
		Instant ultimoMinuto = Instant.parse("2026-12-26T02:59:00Z");
		// 26/12 00:00 local = 2026-12-26T03:00Z: já é do processamento, fora do histórico do ano.
		Instant virada = Instant.parse("2026-12-26T03:00:00Z");

		WrappedRetrospectiva r = agregar(List.of(
				publicacao("p-antes", ultimoMinuto, "antes da virada"),
				publicacao("p-virada", virada, "na virada")));

		assertThat(r.metricas().publicacoes()).isEqualTo(1);
	}

	@Test
	void tretaPrecisaDeDuasParticipacoesDaContaNoMesmoPost() {
		Instant base = Instant.parse("2026-04-01T12:00:00Z");
		WrappedRetrospectiva r = agregar(List.of(
				comentario("p-debate", "user_a", base),
				comentario("p-debate", "user_b", base.plusSeconds(3600)),
				comentario("p-debate", "user_c", base.plusSeconds(7200)),
				comentario("p-so-um", "user_a", base)));

		assertThat(r.tretas()).extracting("publicacaoId").containsExactly("p-debate");
		assertThat(r.tretas().get(0).comentarios()).isEqualTo(3);
		assertThat(r.tretas().get(0).diasDistintos()).isEqualTo(1);
	}

	@Test
	void interacoesDizemQuemEuProcureiEQuemMeProcurou() {
		Instant base = Instant.parse("2026-06-01T12:00:00Z");
		WrappedRetrospectiva r = agregar(List.of(
				reacaoFeita("p-b", "user_amiga", base),
				reacaoFeita("p-b", "user_amiga", base.plusSeconds(60)),
				reacaoFeita("p-c", "user_amiga", base.plusSeconds(120)),
				reacaoRecebida("p-minha", "user_fa", base)));

		assertThat(r.interacoes())
				.anySatisfy(i -> {
					assertThat(i.username()).isEqualTo("user_amiga");
					assertThat(i.tipo()).isEqualTo(com.socialcoisa.rede.wrapped.domain.WrappedInteracao.Tipo.INTERAGI);
					assertThat(i.vezes()).isEqualTo(3);
				})
				.anySatisfy(i -> {
					assertThat(i.username()).isEqualTo("user_fa");
					assertThat(i.tipo()).isEqualTo(
							com.socialcoisa.rede.wrapped.domain.WrappedInteracao.Tipo.INTERAGIRAM);
				});
	}

	@Test
	void publicacaoComMuitasInteracoesViraPostMarcante() {
		Instant base = Instant.parse("2026-03-03T12:00:00Z");
		List<WrappedEvento> eventos = new ArrayList<>(List.of(publicacao("p-meu", base, "aprendendo rust")));
		for (int i = 0; i < 5; i++) {
			eventos.add(reacaoRecebida("p-meu", "user_" + i, base.plusSeconds(60L * i)));
		}

		WrappedRetrospectiva r = agregar(eventos);

		assertThat(r.publicacoesMarcantes()).extracting("id").contains("p-meu");
		assertThat(r.publicacoesMarcantes().get(0).metrica()).isEqualTo(5);
	}

	@Test
	void perfilEhDeterministicoEParteDeCriterioObjetivo() {
		Instant base = Instant.parse("2026-02-02T12:00:00Z");
		// Muitos comentários em conteúdo alheio, poucas publicações -> debatedor/observador.
		List<WrappedEvento> eventos = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			eventos.add(comentario("p-alheio", "user_ocioso", base.plusSeconds(60L * i)));
		}
		WrappedRetrospectiva r = agregar(eventos);

		assertThat(r.perfil().principal()).isNotNull();
		assertThat(r.perfil().caracteristicas()).isNotEmpty();
		assertThat(r.perfil().caracteristicas())
				.anySatisfy(c -> assertThat(c.chave()).isIn(Chave.DEBATEDOR, Chave.OBSERVADOR));
	}

	@Test
	void resultadoIgualParaMesmoHistorico() {
		Instant base = Instant.parse("2026-07-07T12:00:00Z");
		List<WrappedEvento> eventos = List.of(
				publicacao("p1", base, "meetup de rust na sexta"),
				comentario("p2", "user_x", base.plusSeconds(60)),
				publicacao("p3", base.plusSeconds(120), "inteligencia artificial"));

		WrappedRetrospectiva a = agregar(eventos);
		WrappedRetrospectiva b = agregar(eventos);

		assertThat(a).isEqualTo(b);
		// O extrator do Papo Reto agrupa o par com conector: "meetup de rust" é o assunto, não duas palavras.
		assertThat(a.assuntosPublicados()).extracting("expressao")
				.contains("meetup de rust", "inteligencia artificial");
	}

	private static WrappedRetrospectiva agregar(List<WrappedEvento> eventos) {
		WrappedLeitura leitura = (userId, de, ate, cursor, limite) -> paginar(eventos, cursor, limite);
		return WrappedAgregador.montar(EU, ANO, leitura, SEM_COMUNIDADES, ASSUNTOS);
	}

	/** Simula a paginação por cursor: entrega os eventos depois do cursor, no limite. */
	private static List<WrappedEvento> paginar(List<WrappedEvento> eventos, String cursor, int limite) {
		var filtrados = eventos.stream().filter(e -> WrappedAno.de(2026).dentroDoHistorico(e.momento())).toList();
		int inicio = 0;
		if (cursor != null) {
			for (int i = 0; i < filtrados.size(); i++) {
				if (filtrados.get(i).cursor().equals(cursor)) {
					inicio = i + 1;
					break;
				}
			}
		}
		return filtrados.subList(inicio, Math.min(filtrados.size(), inicio + limite));
	}


	@Test
	void salvosEVotosContamComoInteracao() {
		Instant base = Instant.parse("2026-08-01T12:00:00Z");
		WrappedRetrospectiva r = agregar(List.of(
				new WrappedEvento("s1", base, Tipo.SALVO_FEITO, "p1", null, "user_a", "user_a", "A", null),
				new WrappedEvento("v1", base.plusSeconds(10), Tipo.VOTO_FEITO, "p2", null, "user_b", "user_b", "B",
						null)));

		assertThat(r.metricas().salvos()).isEqualTo(1);
		assertThat(r.metricas().votosFeitos()).isEqualTo(1);
		assertThat(r.metricas().interacoesFeitas()).isEqualTo(2);
	}

	@Test
	void comentarioComReacaoEntraNoImpacto() {
		Instant base = Instant.parse("2026-06-15T12:00:00Z");
		WrappedRetrospectiva r = agregar(List.of(
				new WrappedEvento("rc1", base, Tipo.REACAO_RECEBIDA_COMENTARIO, "comment_1", null, "user_x",
						"user_x", "X", null),
				new WrappedEvento("rc2", base.plusSeconds(5), Tipo.REACAO_RECEBIDA_COMENTARIO, "comment_1", null,
						"user_y", "user_y", "Y", null),
				new WrappedEvento("rc3", base.plusSeconds(9), Tipo.REACAO_RECEBIDA_COMENTARIO, "comment_2", null,
						"user_z", "user_z", "Z", null)));

		// dois comentários distintos receberam reação (comment_1 e comment_2), mesmo com 3 reações.
		assertThat(r.impacto().comentariosComReacao()).isEqualTo(2);
	}

	@Test
	void mesMaisAtivoEEpicoDoAno() {
		Instant base = Instant.parse("2026-07-20T12:00:00Z");
		WrappedRetrospectiva r = agregar(List.of(
				publicacao("p1", base, "a"),
				publicacao("p2", base.plusSeconds(60), "b"),
				publicacao("p3", Instant.parse("2026-02-01T12:00:00Z"), "c")));

		assertThat(r.metricas().mesMaisAtivo()).isEqualTo(7);
	}

	@Test
	void comportamentoIndicaCrescimentoQuandoSegundaMetadeDobra() {
		Instant q1 = Instant.parse("2026-02-01T12:00:00Z");
		Instant q4 = Instant.parse("2026-11-01T12:00:00Z");
		List<WrappedEvento> eventos = new ArrayList<>(List.of(publicacao("p0", q1, "a")));
		eventos.add(publicacao("p1", q4, "b"));
		eventos.add(publicacao("p2", q4.plusSeconds(60), "c"));
		WrappedRetrospectiva r = agregar(eventos);

		assertThat(r.ritmo().comportamento().sentido()).isEqualTo(
				com.socialcoisa.rede.wrapped.domain.WrappedComportamento.Sentido.CRESCEU);
	}

	@Test
	void interacaoIncluiComentariosFeitos() {
		Instant base = Instant.parse("2026-03-01T12:00:00Z");
		WrappedRetrospectiva r = agregar(List.of(comentario("p-x", "user_disc", base)));

		assertThat(r.interacoes()).anySatisfy(i -> {
			assertThat(i.username()).isEqualTo("user_disc");
			assertThat(i.tipo()).isEqualTo(com.socialcoisa.rede.wrapped.domain.WrappedInteracao.Tipo.INTERAGI);
		});
	}

	private static WrappedEvento publicacao(String postId, Instant momento, String texto) {
		return new WrappedEvento(postId, momento, Tipo.PUBLICACAO, postId, texto, EU, "eu", "Eu", null);
	}

	private static WrappedEvento comentario(String postId, String autor, Instant momento) {
		return new WrappedEvento("c-" + postId + "-" + momento, momento, Tipo.COMENTARIO_FEITO, postId,
				"acho que sim", autor, autor, autor, null);
	}

	private static WrappedEvento reacaoFeita(String postId, String autor, Instant momento) {
		return new WrappedEvento("r-" + postId + "-" + momento, momento, Tipo.REACAO_FEITA, postId, null,
				autor, autor, autor, null);
	}

	private static WrappedEvento reacaoRecebida(String postId, String autor, Instant momento) {
		return new WrappedEvento("rr-" + postId + "-" + momento, momento, Tipo.REACAO_RECEBIDA, postId, null,
				autor, autor, autor, null);
	}
}
