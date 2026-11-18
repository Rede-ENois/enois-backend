package com.socialcoisa.rede.paporeto.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.paporeto.application.port.out.PapoPublicacaoReader;
import com.socialcoisa.rede.paporeto.application.port.out.PapoRetoRepository;
import com.socialcoisa.rede.paporeto.domain.PapoAssunto;
import com.socialcoisa.rede.paporeto.domain.PapoIntervalo;
import com.socialcoisa.rede.paporeto.domain.PapoTemaFala;

/**
 * A leitura do Papo Reto: o ranking é o teto do que a tela vê — agregado, sem autoria, e com
 * o chão de pessoas distintas valendo dentro do service também (o assunto sem gente não chega
 * à lista nem com potência alta).
 */
class PapoRetoServiceTests {

	private static final Instant AGORA = Instant.parse("2026-11-17T20:00:00Z");

	private final RepositorioRaso repositorio = new RepositorioRaso();

	private PapoRetoService service() {
		PapoRetoWorker worker = new PapoRetoWorker(List.of(), repositorio, new PapoAssuntoExtractor(),
				Clock.fixed(AGORA, ZoneOffset.UTC), 0.30, 0.25, 0.25, 0.20);
		return new PapoRetoService(repositorio, worker, Clock.fixed(AGORA, ZoneOffset.UTC));
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

	/** O repositório raso: só o que o service lê. */
	private static final class RepositorioRaso implements PapoRetoRepository {

		List<PapoAssunto> assuntos = new ArrayList<>();

		java.util.Map<String, List<PapoAssunto>> assuntosPorEscopo = new java.util.LinkedHashMap<>();

		List<PapoIntervalo> intervalos = new ArrayList<>();

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
		public long falasTotais(Instant de, Instant ate) {
			return totalFalas;
		}

	}

}
