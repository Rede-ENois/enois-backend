package com.socialcoisa.rede.termometro.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.termometro.application.port.out.ManifestacaoReader;
import com.socialcoisa.rede.termometro.application.port.out.TermometroRepository;
import com.socialcoisa.rede.termometro.application.port.out.TermometroRepository.Fatia;
import com.socialcoisa.rede.termometro.domain.CorteTermico;
import com.socialcoisa.rede.termometro.domain.Emocao;
import com.socialcoisa.rede.termometro.domain.TermometroPeriodo;

/**
 * O corte incremental do Termômetro: lê o horizonte de 24 horas (com folga de uma), classifica o
 * que ainda não tem fatia da rodada, grava uma fatia por publicação e reescreve os cortes das
 * janelas a partir das fatias — nunca das fontes.
 *
 * O que este teste pina é a PROPRIEDADE que impede contagem dupla e fantasma: rodar o corte
 * várias vezes não duplica manifestação (mesmo relendo o horizonte inteiro), publicação
 * atrasada no relógio não fica de fora, publicação apagada sai da janela, e o autor interno
 * conta uma vez por janela, não por texto.
 */
class TermometroWorkerTests {

	/**
	 * Fonte falsa: ordena como a consulta real, corta pelo intervalo como a consulta real, e só
	 * poda a publicação quando ela é removida — o worker é quem decide o que é releitura.
	 */
	private static final class FonteFalsa implements ManifestacaoReader {

		private final List<Manifestacao> publicacoes = new ArrayList<>();

		/** Os ids apagados: a fonte deixa de devolvê-los, como uma publicação removida. */
		private final Set<String> apagadas = new HashSet<>();

		/** Cada intervalo pedido ao reader — o teste confere até onde a leitura foi. */
		private final List<Intervalo> leituras = new ArrayList<>();

		private int publicadas;

		@Override
		public List<Manifestacao> desde(Instant de, Instant ate) {
			leituras.add(new Intervalo(de, ate));
			java.util.Set<String> apagadasAgora = new HashSet<>(apagadas);
			return publicacoes.stream()
				.filter(m -> !apagadasAgora.contains(m.publicacaoId()))
				.filter(m -> !m.criadoEm().isBefore(de) && m.criadoEm().isBefore(ate))
				.sorted(Comparator.comparing(Manifestacao::criadoEm).thenComparing(Manifestacao::publicacaoId))
				.toList();
		}

		@Override
		public String fonte() {
			return "POST";
		}

		void publicar(String texto, String autor, Instant quando) {
			publicacoes.add(new Manifestacao(texto, autor, quando, "pub_" + (++publicadas)));
		}

		void apagar(String publicacaoId) {
			apagadas.add(publicacaoId);
		}

		/** A edição deixou a publicação sem conteúdo: a fonte ainda a devolve, com texto vazio. */
		void esvaziar(String publicacaoId) {
			int indice = java.util.stream.IntStream.range(0, publicacoes.size())
				.filter(i -> publicacoes.get(i).publicacaoId().equals(publicacaoId))
				.findFirst()
				.orElseThrow();
			Manifestacao original = publicacoes.get(indice);
			publicacoes.set(indice,
					new Manifestacao(" ", original.autorId(), original.criadoEm(), original.publicacaoId()));
		}


	}

	private record Intervalo(Instant de, Instant ate) {
	}

	/** Repositório falso: fatias e cortes em memória, com as mesmas regras do banco. */
	private static final class RepositorioFalso implements TermometroRepository {

		final Map<String, Fatia> fatias = new HashMap<>();
		final Map<TermometroPeriodo, CorteTermico> cortes = new HashMap<>();

		/** Quantas gravações reais de fatia houve (o upsert conta uma por chamada). */
		int gravacoes;

		@Override
		public void salvarFatias(List<Fatia> novas) {
			for (Fatia fatia : novas) {
				fatias.put(fatia.fonte() + "|" + fatia.publicacaoId(), fatia);
				gravacoes++;
			}
		}

		@Override
		public List<Fatia> fatiasEntre(Instant de, Instant ate) {
			return fatias.values()
				.stream()
				.filter(f -> !f.hora().isBefore(de) && f.hora().isBefore(ate))
				.sorted(Comparator.comparing(Fatia::hora).thenComparing(Fatia::publicacaoId))
				.toList();
		}

		@Override
		public Optional<Instant> leituraDaFatia(String fonte, String publicacaoId) {
			return Optional.ofNullable(fatias.get(fonte + "|" + publicacaoId)).map(Fatia::lidaEm);
		}

		@Override
		public void removerFatiasDaJanela(Instant de, Instant ate, List<ManifestacaoReader.Manifestacao> lidas) {
			Set<String> comTexto = new HashSet<>();
			Set<String> semTexto = new HashSet<>();
			for (ManifestacaoReader.Manifestacao manifestacao : lidas) {
				if (manifestacao.texto() == null || manifestacao.texto().isBlank()) {
					semTexto.add(manifestacao.publicacaoId());
				}
				else {
					comTexto.add(manifestacao.publicacaoId());
				}
			}
			fatias.entrySet().removeIf(entrada -> {
				Fatia fatia = entrada.getValue();
				boolean naJanela = !fatia.hora().isBefore(de) && fatia.hora().isBefore(ate);
				boolean relidaSemConteudo = semTexto.contains(fatia.publicacaoId())
						&& !comTexto.contains(fatia.publicacaoId());
				return naJanela && relidaSemConteudo;
			});
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

	private final FonteFalsa fonte = new FonteFalsa();


	private final RepositorioFalso repositorio = new RepositorioFalso();

	private TermometroWorker workerNo(Instant agora) {
		return new TermometroWorker(List.of(fonte), repositorio, new SentimentoLexicon(),
				Clock.fixed(agora, ZoneOffset.UTC));
	}

	private CorteTermico corteDe24h() {
		return repositorio.cortes.get(TermometroPeriodo.ULTIMAS_24H);
	}

	@Test
	void classificaEGuardaSomenteOQueTemEmocao() {
		fonte.publicar("consegui o emprego!!", "autor_1", T0);
		fonte.publicar("bom dia", "autor_2", T0);
		fonte.publicar("estou mal hoje", "autor_3", T0);

		workerNo(T0.plusSeconds(300)).corte();

		// Duas manifestações classificadas; "bom dia" não entra em caixa nenhuma.
		assertThat(repositorio.fatias.values()).hasSize(2);
		assertThat(repositorio.fatias.values().stream().map(Fatia::emocao))
			.containsExactlyInAnyOrder(Emocao.CELEBRANDO.name(), Emocao.PRECISANDO.name());
		// O bucket é da hora de PUBLICAÇÃO (hora cheia), e o lidaEm é o momento da LEITURA.
		Fatia primeira = repositorio.fatias.values().iterator().next();
		assertThat(primeira.hora()).isEqualTo(Instant.parse("2026-11-16T10:00:00Z"));
		assertThat(primeira.lidaEm()).isEqualTo(T0.plusSeconds(300));
	}

	@Test
	void rodarDuasVezesNaoDuplicaManifestacao() {
		fonte.publicar("que raiva disso", "autor_1", T0);

		workerNo(T0.plusSeconds(300)).corte();
		int gravacoesAposPrimeira = repositorio.gravacoes;

		// Segunda rodada, 5 minutos depois: a fonte DEVOLVE a publicação de novo (o horizonte é
		// de 24 horas, não dos últimos 5 minutos) e o worker a pula — já tem fatia da rodada.
		workerNo(T0.plusSeconds(600)).corte();

		assertThat(repositorio.fatias.values()).hasSize(1);
		assertThat(repositorio.gravacoes).isEqualTo(gravacoesAposPrimeira);
		CorteTermico corte24h = corteDe24h();
		assertThat(corte24h.manifestacoes()).isEqualTo(1);
		assertThat(corte24h.porcentagemDe(Emocao.PESADA)).isEqualTo(100);
	}

	@Test
	void relerOHorizonteInteiroNaoDuplicaNemInchaAContagem() {
		fonte.publicar("feliz demais hoje", "autor_1", T0);
		fonte.publicar("que raiva disso", "autor_2", T0.plusSeconds(60));

		workerNo(T0.plusSeconds(300)).corte();
		// Roda de hora em hora: cada rodada relê o horizonte de 25 horas inteiro, e cada
		// publicação continua valendo UMA fatia — antes, cada releitura virava outra linha.
		for (int hora = 1; hora <= 6; hora++) {
			workerNo(T0.plus(Duration.ofHours(hora))).corte();
			assertThat(repositorio.fatias.values()).hasSize(2);
			assertThat(corteDe24h().manifestacoes()).isEqualTo(2);
			assertThat(corteDe24h().participantes()).isEqualTo(2);
		}
	}

	@Test
	void publicacaoAntigaAindaDentroDoHorizonteEntraNaContagem() {
		// Publicada ontem às 12h (22 horas antes da primeira rodada): a fronteira antiga era o
		// agora da última leitura e ela nunca era lida. Agora o horizonte cobre as 24 horas.
		Instant ontem = T0.minus(Duration.ofHours(22));
		fonte.publicar("to mal hoje", "autor_1", ontem);

		workerNo(T0).corte();

		assertThat(repositorio.fatias.values()).hasSize(1);
		assertThat(corteDe24h().manifestacoes()).isEqualTo(1);
	}

	@Test
	void publicacaoAtrasadaNoRelogioNaoFicaDeFora() {
		// Publicação com data de 23h atrás: entra na leitura de agora mesmo sem rodada nova,
		// porque a fronteira recua uma hora além da janela de 24 horas.
		Instant atrasada = T0.minus(Duration.ofHours(23)).minus(Duration.ofMinutes(50));
		fonte.publicar("alguem me ajuda com isso", "autor_1", atrasada);

		workerNo(T0).corte();

		assertThat(fonte.leituras).containsExactly(new Intervalo(T0.minus(Duration.ofHours(25)), T0));
		assertThat(corteDe24h().manifestacoes()).isEqualTo(1);
	}

	@Test
	void publicacaoApagadaNoBancoSomeParaAJanela() {
		// Apagada de verdade (desativada na origem): some da leitura e não há contradição, então a
		// fatia fica até expirar com a janela — a contagem do corte continua a de quando foi lida.
		fonte.publicar("feliz demais hoje", "autor_1", T0);
		fonte.publicar("que raiva disso", "autor_2", T0.plusSeconds(60));
		workerNo(T0.plusSeconds(300)).corte();
		assertThat(corteDe24h().manifestacoes()).isEqualTo(2);

		fonte.apagar(fonte.publicacoes.get(1).publicacaoId());
		workerNo(T0.plusSeconds(420)).corte();

		assertThat(repositorio.fatias.values()).hasSize(2);
		assertThat(corteDe24h().manifestacoes()).isEqualTo(2);

		fonte.esvaziar(fonte.publicacoes.get(0).publicacaoId());
		workerNo(T0.plusSeconds(540)).corte();

		assertThat(repositorio.fatias.values()).hasSize(1);
		assertThat(corteDe24h().manifestacoes()).isEqualTo(1);
		assertThat(corteDe24h().participantes()).isEqualTo(1);
	}

	@Test
	void publicacaoEditadaParaVaziaSaiDaContagem() {
		fonte.publicar("que raiva disso", "autor_1", T0);
		workerNo(T0.plusSeconds(300)).corte();
		assertThat(corteDe24h().manifestacoes()).isEqualTo(1);

		// Editado para o texto vazio (a fonte devolve o id sem conteúdo): a fatia sai da janela.
		fonte.esvaziar(fonte.publicacoes.get(0).publicacaoId());
		workerNo(T0.plusSeconds(540)).corte();

		assertThat(repositorio.fatias.values()).isEmpty();
		assertThat(corteDe24h().manifestacoes()).isZero();
	}

	@Test
	void textoNeutroNaoCriaFatia() {
		// A fonte devolve o texto editado para algo sem emoção: não há fatia nova e a que existia
		// continua, porque só a publicação que a fonte devolve VAZIA perdeu o que contar.
		fonte.publicar("que raiva disso", "autor_1", T0);
		workerNo(T0.plusSeconds(300)).corte();

		fonte.publicacoes.set(0, new ManifestacaoReader.Manifestacao("bom dia", "autor_1", T0,
				fonte.publicacoes.get(0).publicacaoId()));
		workerNo(T0.plusSeconds(480)).corte();

		assertThat(repositorio.fatias.values()).hasSize(1);
		assertThat(corteDe24h().manifestacoes()).isEqualTo(1);
	}

	@Test
	void fatiaForaDoIntervaloRelidoNaoEhApagada() {
		// Publicação de 20h30 atrás: está na janela de 24 horas só na primeira rodada; na rodada de
		// 3 horas depois a fronteira (25h atrás) já não a alcança, e a ausência na leitura não apaga.
		fonte.publicar("feliz demais hoje", "autor_1", T0.minus(Duration.ofMinutes(1230)));
		workerNo(T0).corte();
		assertThat(corteDe24h().manifestacoes()).isEqualTo(1);
		Fatia fatia = repositorio.fatias.values().iterator().next();

		workerNo(T0.plus(Duration.ofHours(3))).corte();

		assertThat(repositorio.fatias.values()).containsExactly(fatia);
		assertThat(corteDe24h().manifestacoes()).isEqualTo(1);
	}

	@Test
	void autorRepetidoContaUmaVezNosParticipantesMasContaNasManifestacoes() {
		// A mesma pessoa publica três vezes: três manifestações, UMA pessoa distinta.
		fonte.publicar("consegui", "autor_1", T0);
		fonte.publicar("consegui de novo", "autor_1", T0.plusSeconds(60));
		fonte.publicar("funcionou!", "autor_1", T0.plusSeconds(120));

		workerNo(T0.plusSeconds(300)).corte();

		assertThat(corteDe24h().manifestacoes()).isEqualTo(3);
		assertThat(corteDe24h().participantes()).isEqualTo(1);
	}

	@Test
	void janelaComPoucaGenteNaoMostraIndicador() {
		fonte.publicar("consegui", "autor_1", T0);
		fonte.publicar("to mal", "autor_2", T0);

		workerNo(T0.plusSeconds(300)).corte();

		// Menos gente que o chão (20 participantes): a janela existe, o indicador não.
		CorteTermico corte24h = corteDe24h();
		assertThat(corte24h.temAmoSuficiente()).isFalse();
	}

	@Test
	void cincoManifestacoesDePessoasDistintasMostramIndicador() {
		// A régua nova: cinco manifestações, de pelo menos duas pessoas.
		for (int i = 1; i <= CorteTermico.MINIMO_MANIFESTACOES; i++) {
			fonte.publicar("consegui", "autor_" + i, T0.plusSeconds(i));
		}

		workerNo(T0.plusSeconds(3600)).corte();

		assertThat(corteDe24h().manifestacoes()).isEqualTo(CorteTermico.MINIMO_MANIFESTACOES);
		assertThat(corteDe24h().participantes()).isGreaterThanOrEqualTo(CorteTermico.MINIMO_PARTICIPANTES);
		assertThat(corteDe24h().temAmoSuficiente()).isTrue();
	}

	@Test
	void quatroManifestacoesAindaSaoAmostraPequena() {
		for (int i = 1; i <= CorteTermico.MINIMO_MANIFESTACOES - 1; i++) {
			fonte.publicar("consegui", "autor_" + i, T0.plusSeconds(i));
		}

		workerNo(T0.plusSeconds(3600)).corte();

		assertThat(corteDe24h().temAmoSuficiente()).isFalse();
	}

	@Test
	void cincoManifestacoesDeUmaPessoaSoNaoSaoClimaDaRede() {
		for (int i = 1; i <= CorteTermico.MINIMO_MANIFESTACOES; i++) {
			fonte.publicar("consegui", "autor_unico", T0.plusSeconds(i));
		}

		workerNo(T0.plusSeconds(3600)).corte();

		assertThat(corteDe24h().manifestacoes()).isGreaterThanOrEqualTo(CorteTermico.MINIMO_MANIFESTACOES);
		assertThat(corteDe24h().participantes()).isEqualTo(1);
		assertThat(corteDe24h().temAmoSuficiente()).isFalse();
	}

	@Test
	void asQuatroJanelasSaoReescritasACadaCorte() {
		fonte.publicar("consegui", "autor_1", T0);
		workerNo(T0.plusSeconds(300)).corte();

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
