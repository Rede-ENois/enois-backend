package com.socialcoisa.rede.emotes.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.emotes.domain.EmoteImages.Variant;

class EmoteImagesTest {

	@Test
	void bttvAnimadaUsaGifNoTextoEAEscalaDaGradeNaMiniatura() {
		assertThat(EmoteImages.upstream(EmoteProvider.BTTV, "abc", Variant.THUMB, true))
			.isEqualTo("https://cdn.betterttv.net/emote/abc/1x.png");
		assertThat(EmoteImages.upstream(EmoteProvider.BTTV, "abc", Variant.FULL, true))
			.isEqualTo("https://cdn.betterttv.net/emote/abc/1x.gif");
		assertThat(EmoteImages.fallback(EmoteProvider.BTTV, "abc", Variant.FULL, true))
			.isEqualTo("https://cdn.betterttv.net/emote/abc/2x.png");
		assertThat(EmoteImages.fallback(EmoteProvider.BTTV, "abc", Variant.THUMB, true)).isNull();
	}

	@Test
	void bttvEstaticaNemTentaOGif() {
		assertThat(EmoteImages.upstream(EmoteProvider.BTTV, "abc", Variant.FULL, false))
			.isEqualTo("https://cdn.betterttv.net/emote/abc/2x.png");
		assertThat(EmoteImages.fallback(EmoteProvider.BTTV, "abc", Variant.FULL, false)).isNull();
	}

	@Test
	void ffzAnimadaUsaOArquivoEmMovimentoENaFaltaCaiNoEstatico() {
		assertThat(EmoteImages.upstream(EmoteProvider.FFZ, "100", Variant.THUMB, true))
			.isEqualTo("https://cdn.frankerfacez.com/emote/100/1");
		assertThat(EmoteImages.upstream(EmoteProvider.FFZ, "100", Variant.FULL, true))
			.isEqualTo("https://cdn.frankerfacez.com/emote/100/animated/2");
		assertThat(EmoteImages.fallback(EmoteProvider.FFZ, "100", Variant.FULL, true))
			.isEqualTo("https://cdn.frankerfacez.com/emote/100/2");
	}

	@Test
	void ffzEstaticaVaiDiretoNoEstaticoPorqueOAnimadoTravaConexao() {
		assertThat(EmoteImages.upstream(EmoteProvider.FFZ, "100", Variant.FULL, false))
			.isEqualTo("https://cdn.frankerfacez.com/emote/100/2");
		assertThat(EmoteImages.fallback(EmoteProvider.FFZ, "100", Variant.FULL, false)).isNull();
	}

	@Test
	void caminhoPublicoApontaParaOProprioBackend() {
		Emote animada = new Emote("catJAM", EmoteProvider.BTTV, "abc", true, null);
		Emote estatica = new Emote("KEKW", EmoteProvider.BTTV, "abc", false, null);

		assertThat(EmoteImages.publicPath(animada, Variant.THUMB)).isEqualTo("/emotes/image/BTTV/abc?variant=thumb");
		assertThat(EmoteImages.publicPath(animada, Variant.FULL))
			.isEqualTo("/emotes/image/BTTV/abc?variant=full&a=1");
		// Estatica nao carrega o marcador: o endereco nao muda quando o catalogo descobre animacao.
		assertThat(EmoteImages.publicPath(estatica, Variant.FULL)).isEqualTo("/emotes/image/BTTV/abc?variant=full");
	}

	@Test
	void figurinhaComCodigoOuIdentificadorForaDoPadraoNaoEntra() {
		assertThatThrownBy(() -> new Emote("com espaco", EmoteProvider.BTTV, "1", false, null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Emote("KEKW", EmoteProvider.BTTV, "../../etc/passwd", false, null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Emote("KEKW", EmoteProvider.BTTV, "1", false, 0))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void varianteDesconhecidaCaiNaArteNitida() {
		assertThat(Variant.parse(null)).isEqualTo(Variant.FULL);
		assertThat(Variant.parse("sei-la")).isEqualTo(Variant.FULL);
		assertThat(Variant.parse("thumb")).isEqualTo(Variant.THUMB);
	}
}