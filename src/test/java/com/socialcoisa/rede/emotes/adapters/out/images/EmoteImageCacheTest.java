package com.socialcoisa.rede.emotes.adapters.out.images;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class EmoteImageCacheTest {

	private static final StoredImage PNG = new StoredImage(new byte[] { 1, 2, 3 }, "image/png");

	@Test
	void provedorDesconhecidoNemChegaNoCdn() {
		AtomicInteger chamadas = new AtomicInteger();
		EmoteImageCache cache = new EmoteImageCache((url) -> {
			chamadas.incrementAndGet();
			return PNG;
		});

		assertThat(cache.find("SEILA", "1", "full", false)).isEmpty();
		assertThat(cache.find(null, "1", "full", false)).isEmpty();
		assertThat(chamadas).hasValue(0);
	}

	@Test
	void identificadorComCaminhoNaoViraEndereco() {
		AtomicInteger chamadas = new AtomicInteger();
		EmoteImageCache cache = new EmoteImageCache((url) -> {
			chamadas.incrementAndGet();
			return PNG;
		});

		assertThat(cache.find("FFZ", "../../etc/passwd", "full", false)).isEmpty();
		assertThat(cache.find("FFZ", "1/../../x", "full", false)).isEmpty();
		assertThat(chamadas).hasValue(0);
	}

	@Test
	void segundaBuscaDaMesmaFigurinhaSaiDoCache() {
		AtomicInteger chamadas = new AtomicInteger();
		EmoteImageCache cache = new EmoteImageCache((url) -> {
			chamadas.incrementAndGet();
			assertThat(url).isEqualTo("https://cdn.frankerfacez.com/emote/100/1");
			return PNG;
		});

		assertThat(cache.find("FFZ", "100", "thumb", false)).isPresent();
		assertThat(cache.find("FFZ", "100", "thumb", false)).isPresent();

		assertThat(chamadas).hasValue(1);
		assertThat(cache.cachedEntries()).isEqualTo(1);
	}

	@Test
	void tentaDuasVezesAntesDeDesistir() {
		AtomicInteger chamadas = new AtomicInteger();
		EmoteImageCache cache = new EmoteImageCache((url) -> {
			if (chamadas.incrementAndGet() == 1) {
				throw new java.net.http.HttpTimeoutException("timeout do CDN");
			}
			return PNG;
		});

		assertThat(cache.find("FFZ", "100", "full", false)).isPresent();
		assertThat(chamadas).hasValue(2);
	}

	@Test
	void cdnQueNaoRespondeDevolveVazioSemGravarNada() {
		AtomicInteger chamadas = new AtomicInteger();
		EmoteImageCache cache = new EmoteImageCache((url) -> {
			chamadas.incrementAndGet();
			throw new IllegalStateException("502");
		});

		assertThat(cache.find("BTTV", "abc", "thumb", false)).isEmpty();
		assertThat(chamadas).hasValue(2);
		assertThat(cache.cachedEntries()).isZero();
	}

	@Test
	void figurinhaEstaticaVaiDiretoNoArquivoEstatico() {
		// Regressao: pedir `animated/` de uma estatica no FFZ nao da 404, a conexao fica pendurada ate
		// o timeout — eram 12s por figurinha antes de cair para o estatico.
		List<String> tentativas = new ArrayList<>();
		EmoteImageCache cache = new EmoteImageCache((url) -> {
			tentativas.add(url);
			return PNG;
		});

		assertThat(cache.find("FFZ", "100", "full", false)).isPresent();
		assertThat(tentativas).containsExactly("https://cdn.frankerfacez.com/emote/100/2");
	}

	@Test
	void catalogoDizAnimadaMasCdnRecusaOGifCaiNoEstatico() {
		List<String> tentativas = new ArrayList<>();
		EmoteImageCache cache = new EmoteImageCache((url) -> {
			tentativas.add(url);
			if (url.contains("animated")) {
				throw new ImageNotFoundException("CDN de figurinha respondeu 404");
			}
			return PNG;
		});

		assertThat(cache.find("FFZ", "100", "full", true)).isPresent();
		assertThat(tentativas).containsExactly("https://cdn.frankerfacez.com/emote/100/animated/2",
				"https://cdn.frankerfacez.com/emote/100/2");

		// O desvio fica guardado: a proxima leitura sai do cache sem tocar no CDN.
		tentativas.clear();
		assertThat(cache.find("FFZ", "100", "full", true)).isPresent();
		assertThat(tentativas).isEmpty();
	}

	@Test
	void animacaoMudaAChaveDoCache() {
		// A estatica pode ja estar guardada; quando o catalogo descobre a animacao, a leitura seguinte
		// tem de buscar o arquivo em movimento (o endereco publico tambem passa a carregar `&a=1`).
		List<String> tentativas = new ArrayList<>();
		EmoteImageCache cache = new EmoteImageCache((url) -> {
			tentativas.add(url);
			return PNG;
		});

		assertThat(cache.find("FFZ", "100", "full", false)).isPresent();
		assertThat(cache.find("FFZ", "100", "full", true)).isPresent();

		assertThat(tentativas).containsExactly("https://cdn.frankerfacez.com/emote/100/2",
				"https://cdn.frankerfacez.com/emote/100/animated/2");
	}
}