package com.socialcoisa.rede.termometro.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.termometro.application.port.out.ManifestacaoReader;
import com.socialcoisa.rede.termometro.application.port.out.TermometroRepository;
import com.socialcoisa.rede.termometro.domain.CorteTermico;
import com.socialcoisa.rede.termometro.domain.Emocao;
import com.socialcoisa.rede.termometro.domain.TermometroPeriodo;

/**
 * O corte incremental do Termômetro: lê SÓ o que mudou desde a fronteira, grava a fatia com o
 * `lidaEm` da rodada e reescreve os cortes das janelas a partir das fatias — nunca das fontes.
 * O que este teste pina é a PROPRIEDADE que impede contagem dupla: rodar o corte duas vezes
 * não duplica manifestação, e o autor interno conta uma vez por janela, não por texto.
 */
class TermometroWorkerTests {

	/** Fonte falsa: o que ela devolve é o que o worker "leu" das publicações. */
	private static final class FonteFalsa implements ManifestacaoReader {

		private List<Manifestacao> publicacoes = List.of();

		@Override
		public List<Manifestacao> desde(Instant de, Instant ate) {
			return publicacoes.stream()
				.filter(m -> !m.criadoEm().isBefore(de) && m.criadoEm().isBefore(ate))
				.toList();
		}

		@Override
		public String fonte() {
			return "POST";
		}
	}

	/** Repositório falso: fatias e cortes em memória, como o banco gravaria. */
	private static final class RepositorioFalso implements TermometroRepository {

		final List<Fatia> fatias = new ArrayList<>();
		final Map<TermometroPeriodo, CorteTermico> cortes = new HashMap<>();

		@Override
		public void salvarFatias(List<Fatia> novas) {
			fatias.addAll(novas);
		}

		@Override
		public List<Fatia> fatiasEntre(Instant de, Instant ate) {
			return fatias.stream().filter(f -> !f.hora().isBefore(de) && f.hora().isBefore(ate)).toList();
		}

		@Override
		public Optional<Instant> ultimaFronteira() {
			return fatias.stream().map(TermometroRepository.Fatia::lidaEm).max(Instant::compareTo);
		}

		@Override
		public void salvarCorte(CorteTermico corte) {
			cortes.put(corte.periodo(), corte);
		}

		@Override
		public Optional<CorteTermico> corteDe(TermometroPeriodo periodo) {
			return Optional.ofNullable(cortes.get(periodo));
		}
	}

	private static final Instant T0 = Instant.parse("2026-11-16T10:00:00Z");

	private FonteFalsa fonte = new FonteFalsa();

	private final RepositorioFalso repositorio = new RepositorioFalso();

	private TermometroWorker workerNo(Instant agora) {
		return new TermometroWorker(List.of(fonte), repositorio, new SentimentoLexicon(),
				Clock.fixed(agora, ZoneOffset.UTC));
	}

	@Test
	void classificaEGuardaSomenteOQueTemEmocao() {
		fonte.publicacoes = List.of(
			new ManifestacaoReader.Manifestacao("consegui o emprego!!", "autor_1", T0),
			new ManifestacaoReader.Manifestacao("bom dia", "autor_2", T0),
			new ManifestacaoReader.Manifestacao("estou mal hoje", "autor_3", T0));

		workerNo(T0.plusSeconds(300)).corte();

		// Duas manifestações classificadas; "bom dia" não entra em caixa nenhuma.
		assertThat(repositorio.fatias).hasSize(2);
		assertThat(repositorio.fatias.stream().map(TermometroRepository.Fatia::emocao))
			.containsExactlyInAnyOrder(Emocao.CELEBRANDO.name(), Emocao.PRECISANDO.name());
		// O texto nunca é gravado: a fatia é contabilidade, não acervo.
		assertThat(repositorio.fatias.stream().noneMatch(f -> f.fonte().isBlank())).isTrue();
		// O bucket é da hora de PUBLICAÇÃO (hora cheia), e o lidaEm é o momento da LEITURA.
		TermometroRepository.Fatia primeira = repositorio.fatias.get(0);
		assertThat(primeira.hora()).isEqualTo(Instant.parse("2026-11-16T10:00:00Z"));
		assertThat(primeira.lidaEm()).isEqualTo(T0.plusSeconds(300));
	}

	@Test
	void rodarDuasVezesNaoDuplicaManifestacao() {
		fonte.publicacoes = List.of(new ManifestacaoReader.Manifestacao("que raiva disso", "autor_1", T0));

		workerNo(T0.plusSeconds(300)).corte();
		int fatiasAposPrimeira = repositorio.fatias.size();

		// Segunda rodada SEM publicação nova: a fonte não devolve nada (a fronteira corta),
		// e a contagem da janela continua a mesma.
		workerNo(T0.plusSeconds(600)).corte();

		assertThat(repositorio.fatias).hasSize(fatiasAposPrimeira);
		CorteTermico corte24h = repositorio.cortes.get(TermometroPeriodo.ULTIMAS_24H);
		assertThat(corte24h.manifestacoes()).isEqualTo(1);
		assertThat(corte24h.porcentagemDe(Emocao.PESADA)).isEqualTo(100);
	}

	@Test
	void leituraSoPegaOPublicadoDepoisDaFronteira() {
		// Primeira rodada lê o post antigo.
		fonte.publicacoes = List.of(new ManifestacaoReader.Manifestacao("feliz demais hoje", "autor_1", T0));
		workerNo(T0.plusSeconds(300)).corte();
		assertThat(repositorio.fatias).hasSize(1);

		// Publicação NOVA entra; na segunda rodada SÓ ela vira fatia (a fronteira avançou),
		// mas o corte da janela soma as duas.
		fonte.publicacoes = List.of(
			new ManifestacaoReader.Manifestacao("feliz demais hoje", "autor_1", T0),
			new ManifestacaoReader.Manifestacao("alguem me ajuda com isso", "autor_2", T0.plusSeconds(400)));
		workerNo(T0.plusSeconds(600)).corte();

		assertThat(repositorio.fatias).hasSize(2);
		CorteTermico corte24h = repositorio.cortes.get(TermometroPeriodo.ULTIMAS_24H);
		assertThat(corte24h.manifestacoes()).isEqualTo(2);
		assertThat(corte24h.participantes()).isEqualTo(2);
	}

	@Test
	void autorRepetidoContaUmaVezNosParticipantesMasContaNasManifestacoes() {
		// A mesma pessoa publica três vezes: três manifestações, UMA pessoa distinta.
		fonte.publicacoes = List.of(
			new ManifestacaoReader.Manifestacao("consegui", "autor_1", T0),
			new ManifestacaoReader.Manifestacao("consegui de novo", "autor_1", T0.plusSeconds(60)),
			new ManifestacaoReader.Manifestacao("funcionou!", "autor_1", T0.plusSeconds(120)));

		workerNo(T0.plusSeconds(300)).corte();

		CorteTermico corte24h = repositorio.cortes.get(TermometroPeriodo.ULTIMAS_24H);
		assertThat(corte24h.manifestacoes()).isEqualTo(3);
		assertThat(corte24h.participantes()).isEqualTo(1);
	}

	@Test
	void janelaComPoucaGenteNaoMostraIndicador() {
		fonte.publicacoes = List.of(
			new ManifestacaoReader.Manifestacao("consegui", "autor_1", T0),
			new ManifestacaoReader.Manifestacao("to mal", "autor_2", T0));

		workerNo(T0.plusSeconds(300)).corte();

		// Menos gente que o chão (20 participantes): a janela existe, o indicador não.
		CorteTermico corte24h = repositorio.cortes.get(TermometroPeriodo.ULTIMAS_24H);
		assertThat(corte24h.temAmoSuficiente()).isFalse();
	}

	@Test
	void asQuatroJanelasSaoReescritasACadaCorte() {
		fonte.publicacoes = List.of(new ManifestacaoReader.Manifestacao("consegui", "autor_1", T0));
		workerNo(T0.plusSeconds(300)).corte();

		// O corte é o mesmo para as janelas largas quando tudo aconteceu agora; o HOJE é o
		// mesmo dia. O que interessa: as quatro linhas existem depois da rodada.
		assertThat(repositorio.cortes.keySet()).containsExactlyInAnyOrder(TermometroPeriodo.HOJE,
				TermometroPeriodo.ULTIMAS_24H, TermometroPeriodo.ULTIMOS_7D, TermometroPeriodo.ULTIMOS_30D);
	}

	@Test
	void inicioDaJanelaHojeEAMeiaNoiteUTC() {
		// 10h do dia 16: o HOJE começa na meia-noite do dia 16 em UTC.
		Instant meiaNoite = Instant.parse("2026-11-16T00:00:00Z");
		assertThat(TermometroWorker.inicioDaJanela(TermometroPeriodo.HOJE, T0)).isEqualTo(meiaNoite);
		assertThat(TermometroWorker.inicioDaJanela(TermometroPeriodo.ULTIMAS_24H, T0))
			.isEqualTo(T0.minusSeconds(86400));
		assertThat(TermometroWorker.inicioDaJanela(TermometroPeriodo.ULTIMOS_7D, T0))
			.isEqualTo(T0.minusSeconds(7 * 86400));
	}

	@Test
	void bucketDaHoraArredondaParaBaixo() {
		assertThat(TermometroWorker.bucketDaHora(Instant.parse("2026-11-16T10:37:42Z")))
			.isEqualTo(Instant.parse("2026-11-16T10:00:00Z"));
		assertThat(TermometroWorker.bucketDaHora(Instant.parse("2026-11-16T10:00:00Z")))
			.isEqualTo(Instant.parse("2026-11-16T10:00:00Z"));
	}

}
