package com.socialcoisa.rede.emotes.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.emotes.domain.Emote;
import com.socialcoisa.rede.emotes.domain.EmoteProvider;

class EmotePageTest {

	private static Emote emote(String code, EmoteProvider provider, Integer rank) {
		return new Emote(code, provider, "1", false, rank);
	}

	@Test
	void popularesVemPrimeiroNaOrdemDeUso() {
		List<Emote> catalogo = List.of(emote("AAA", EmoteProvider.FFZ, 2), emote("BBB", EmoteProvider.BTTV, null),
				emote("CCC", EmoteProvider.FFZ, 1));

		List<Emote> filtrados = EmotePage.filter(catalogo, null, "ALL");

		assertThat(filtrados).extracting(Emote::code).containsExactly("CCC", "AAA", "BBB");
	}

	@Test
	void abaPopularSoTrazQuemTemPosicaoDeUso() {
		List<Emote> catalogo = List.of(emote("AAA", EmoteProvider.FFZ, 2), emote("BBB", EmoteProvider.BTTV, null),
				emote("CCC", EmoteProvider.FFZ, 1));

		assertThat(EmotePage.filter(catalogo, "", "popular")).extracting(Emote::code).containsExactly("CCC", "AAA");
	}

	@Test
	void filtroPorProvedorTrazSoAColecaoDele() {
		List<Emote> catalogo = List.of(emote("KEKW", EmoteProvider.BTTV, null), emote("catJAM", EmoteProvider.FFZ, null));

		assertThat(EmotePage.filter(catalogo, null, "BTTV")).extracting(Emote::code).containsExactly("KEKW");
		assertThat(EmotePage.filter(catalogo, null, "FFZ")).extracting(Emote::code).containsExactly("catJAM");
	}

	@Test
	void buscaIgnoraCaixaEDevolveTudoComTermoVazio() {
		List<Emote> catalogo = List.of(emote("KEKW", EmoteProvider.BTTV, null), emote("catJAM", EmoteProvider.FFZ, null),
				emote("kekwait", EmoteProvider.FFZ, null));

		assertThat(EmotePage.filter(catalogo, "KEK", null)).extracting(Emote::code).containsExactly("KEKW", "kekwait");
		assertThat(EmotePage.filter(catalogo, "   ", null)).hasSize(3);
	}

	@Test
	void paginaCortaEmQuarentaEPrendeAoIntervaloQueExiste() {
		List<Emote> catalogo = new ArrayList<>();
		for (int i = 0; i < 100; i++) {
			catalogo.add(emote("E" + i, EmoteProvider.BTTV, null));
		}

		EmotePage primeira = EmotePage.of(catalogo, 1, 40);
		assertThat(primeira.items()).hasSize(40);
		assertThat(primeira.total()).isEqualTo(100);
		assertThat(primeira.totalPages()).isEqualTo(3);

		EmotePage ultima = EmotePage.of(catalogo, 99, 40);
		assertThat(ultima.page()).isEqualTo(3);
		assertThat(ultima.items()).hasSize(20);

		EmotePage zero = EmotePage.of(catalogo, 0, 40);
		assertThat(zero.page()).isEqualTo(1);
	}

	@Test
	void catalogoVazioAindaTemUmaPagina() {
		EmotePage vazia = EmotePage.of(List.of(), 1, 40);

		assertThat(vazia.items()).isEmpty();
		assertThat(vazia.total()).isZero();
		assertThat(vazia.totalPages()).isEqualTo(1);
	}

	@Test
	void provedorDesconhecidoNaoFiltra() {
		List<Emote> catalogo = List.of(emote("KEKW", EmoteProvider.BTTV, null), emote("catJAM", EmoteProvider.FFZ, null));

		assertThat(EmotePage.filter(catalogo, null, "sei-la")).hasSize(2);
	}
}