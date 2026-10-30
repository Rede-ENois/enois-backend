package com.socialcoisa.rede.emotes.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.emotes.application.port.out.EmoteSource;
import com.socialcoisa.rede.emotes.application.port.out.LiveEmoteSource;
import com.socialcoisa.rede.emotes.domain.Emote;
import com.socialcoisa.rede.emotes.domain.EmoteProvider;

class EmoteCatalogServiceTest {

	/** Id derivado do codigo: o mesmo codigo tem a mesma arte, codigos diferentes nao se atropelam. */
	private static Emote emote(String code, EmoteProvider provider, Integer rank) {
		return new Emote(code, provider, String.valueOf(Math.abs(code.hashCode())), false, rank);
	}

	private static LiveEmoteSource live(String nome, List<Emote> emotes) {
		return new LiveEmoteSource() {
			@Override
			public String name() {
				return nome;
			}

			@Override
			public List<Emote> load() {
				return emotes;
			}
		};
	}

	private static LiveEmoteSource falha(String nome) {
		return new LiveEmoteSource() {
			@Override
			public String name() {
				return nome;
			}

			@Override
			public List<Emote> load() {
				throw new IllegalStateException("provedor fora do ar");
			}
		};
	}

	private static EmoteSource embarcado(List<Emote> emotes) {
		return new EmoteSource() {
			@Override
			public String name() {
				return "EMBARCADO";
			}

			@Override
			public List<Emote> load() {
				return emotes;
			}
		};
	}

	@Test
	void nasceComRetratoEmbarcadoSemEsperarProvedor() {
		EmoteCatalogService servico = new EmoteCatalogService(List.of(falha("BTTV")),
				embarcado(List.of(emote("OMEGALUL", EmoteProvider.FFZ, 1))));

		assertThat(servico.catalog().emotes()).extracting(Emote::code).containsExactly("OMEGALUL");
		assertThat(servico.liveLoaded()).isFalse();
	}

	@Test
	void refreshJuntaArteAoVivoComPosicaoPopular() {
		EmoteCatalogService servico = new EmoteCatalogService(
				List.of(live("BTTV", List.of(emote("KEKW", EmoteProvider.BTTV, null))),
						live("FFZ-POPULAR", List.of(emote("KEKW", EmoteProvider.FFZ, 3), emote("catJAM", EmoteProvider.FFZ, 1)))),
				embarcado(List.of(emote("KEKW", EmoteProvider.FFZ, 2), emote("OMEGALUL", EmoteProvider.FFZ, 4))));

		servico.refresh();

		List<Emote> catalogo = servico.catalog().emotes();
		assertThat(catalogo).extracting(Emote::code).containsExactly("catJAM", "KEKW", "OMEGALUL");
		// Arte ao vivo manda e a posicao popular ao vivo vence a do retrato embarcado.
		Emote kekw = catalogo.stream().filter(e -> e.code().equals("KEKW")).findFirst().orElseThrow();
		assertThat(kekw.provider()).isEqualTo(EmoteProvider.BTTV);
		assertThat(kekw.popularRank()).isEqualTo(3);
		assertThat(servico.liveLoaded()).isTrue();
	}

	@Test
	void fonteQueFalhaNaoApagaOCatalogoMontado() {
		List<LiveEmoteSource> saudaveis = new ArrayList<>(
				List.of(live("BTTV", List.of(emote("KEKW", EmoteProvider.BTTV, null)))));
		EmoteCatalogService servico = new EmoteCatalogService(saudaveis,
				embarcado(List.of(emote("OMEGALUL", EmoteProvider.FFZ, 1))));
		servico.refresh();
		int antes = servico.catalog().emotes().size();

		saudaveis.clear();
		servico.refresh();

		assertThat(servico.catalog().emotes()).hasSize(antes);
	}

	@Test
	void catalogoVazioSoQuandoNadaResponde() {
		EmoteCatalogService servico = new EmoteCatalogService(List.of(falha("BTTV")), embarcado(List.of()));

		servico.refresh();

		assertThat(servico.catalog().emotes()).isEmpty();
		assertThat(servico.catalog().generatedAt()).isEqualTo(java.time.Instant.EPOCH);
	}

	@Test
	void paginaEIndiceSaemDoMesmoCatalogo() {
		EmoteCatalogService servico = new EmoteCatalogService(List.of(),
				embarcado(List.of(emote("OMEGALUL", EmoteProvider.FFZ, 2), emote("Pog", EmoteProvider.FFZ, 1),
						emote("KEKW", EmoteProvider.BTTV, null))));

		EmotePage populares = servico.page(1, 1, null, "POPULAR");

		assertThat(populares.total()).isEqualTo(2);
		assertThat(populares.items()).extracting(Emote::code).containsExactly("Pog");
		assertThat(servico.index()).hasSize(3);
	}

	@Test
	void listagemDeAnimadasAcrescentaAAnimacaoSemMudarAPosicaoPopular() {
		// A listagem popular nao publica o campo `animated`; a de animadas traz o mesmo id, sem posicao.
		Emote popular = new Emote("catJAM", EmoteProvider.FFZ, "777171", false, 5);
		Emote animada = new Emote("catJAM", EmoteProvider.FFZ, "777171", true, null);
		EmoteCatalogService servico = new EmoteCatalogService(
				List.of(live("FFZ-POPULAR", List.of(popular)), live("FFZ-ANIMADAS", List.of(animada))),
				embarcado(List.of()));

		servico.refresh();

		List<Emote> catalogo = servico.catalog().emotes();
		assertThat(catalogo).hasSize(1);
		assertThat(catalogo.get(0).animated()).isTrue();
		assertThat(catalogo.get(0).popularRank()).isEqualTo(5);
		assertThat(servico.isAnimated("FFZ", "777171")).isTrue();
		assertThat(servico.isAnimated("ffz", "777171")).isTrue();
		assertThat(servico.isAnimated("BTTV", "777171")).isFalse();
		assertThat(servico.isAnimated("SEILA", "777171")).isFalse();
		assertThat(servico.isAnimated("FFZ", null)).isFalse();
	}

	@Test
	void codigoRepetidoComEnviosDiferentesFicaComAArteDaPrimeiraFonte() {
		// O FFZ tem varios envios com o mesmo nome (catJAM estatico e catJAM animado tem ids
		// diferentes). A ordem das fontes e que decide o desenho — animadas antes da popular — e a
		// posicao de uso vem da lista popular mesmo assim.
		Emote animada = new Emote("catJAM", EmoteProvider.FFZ, "777171", true, null);
		Emote estatica = new Emote("catJAM", EmoteProvider.FFZ, "564062", false, 7);
		EmoteCatalogService servico = new EmoteCatalogService(
				List.of(live("FFZ-ANIMADAS", List.of(animada)), live("FFZ-POPULAR", List.of(estatica))),
				embarcado(List.of()));

		servico.refresh();

		Emote catjam = servico.catalog().emotes().get(0);
		assertThat(catjam.id()).isEqualTo("777171");
		assertThat(catjam.animated()).isTrue();
		assertThat(catjam.popularRank()).isEqualTo(7);
	}

	@Test
	void figurinhaDeNomeBarradoNaoEntraNoCatalogo() {
		Emote barrada = new Emote("bigbutt", EmoteProvider.FFZ, "999001", true, 1);
		Emote limpa = new Emote("KEKW", EmoteProvider.BTTV, "999002", true, null);
		EmoteCatalogService servico = new EmoteCatalogService(List.of(live("FFZ-ANIMADAS", List.of(barrada, limpa))),
				embarcado(List.of(new Emote("dick", EmoteProvider.FFZ, "999003", false, 2))));

		servico.refresh();

		assertThat(servico.catalog().emotes()).extracting(Emote::code).containsExactly("KEKW");
		assertThat(servico.index()).extracting(Emote::code).containsExactly("KEKW");
		assertThat(servico.page(1, 40, "butt", "ALL").total()).isZero();
	}

	@Test
	void aArteBarradaTambemERecusadaPeloIdDireto() {
		Emote barrada = new Emote("booty", EmoteProvider.FFZ, "999010", false, 1);
		EmoteCatalogService servico = new EmoteCatalogService(List.of(), embarcado(List.of(barrada)));

		// Sai do catalogo...
		assertThat(servico.catalog().emotes()).isEmpty();
		// ...e o id continua barrado, que e o que impede o proxy de buscar a arte no CDN.
		assertThat(servico.isBlocked("FFZ", "999010")).isTrue();
		assertThat(servico.isBlocked("ffz", "999010")).isTrue();
		assertThat(servico.isBlocked("BTTV", "999010")).isFalse();
		assertThat(servico.isBlocked("FFZ", "999011")).isFalse();
		assertThat(servico.isBlocked("SEILA", "999010")).isFalse();
		assertThat(servico.isBlocked("FFZ", null)).isFalse();
	}

	@Test
	void figurinhaLegitimaContinuaServida() {
		Emote limpa = new Emote("KEKW", EmoteProvider.BTTV, "999020", false, null);
		EmoteCatalogService servico = new EmoteCatalogService(List.of(), embarcado(List.of(limpa)));

		assertThat(servico.catalog().emotes()).extracting(Emote::code).containsExactly("KEKW");
		assertThat(servico.isBlocked("BTTV", "999020")).isFalse();
	}

}