package com.socialcoisa.rede.emotes.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.socialcoisa.rede.emotes.adapters.out.images.EmoteImageCache;
import com.socialcoisa.rede.emotes.adapters.out.images.StoredImage;
import com.socialcoisa.rede.emotes.application.EmoteCatalogService;
import com.socialcoisa.rede.emotes.application.port.out.EmoteSource;
import com.socialcoisa.rede.emotes.domain.Emote;
import com.socialcoisa.rede.emotes.domain.EmoteProvider;

class EmoteControllerTest {

	private static final Emote KEKW = new Emote("KEKW", EmoteProvider.BTTV, "abc", false, null);

	private static final Emote OMEGALUL = new Emote("OMEGALUL", EmoteProvider.FFZ, "128054", false, 1);

	private static final Emote CATJAM = new Emote("catJAM", EmoteProvider.FFZ, "777171", true, null);

	private static MockMvc mvc(EmoteImageCache images, List<Emote> catalogo) {
		EmoteSource embarcado = new EmoteSource() {
			@Override
			public String name() {
				return "EMBARCADO";
			}

			@Override
			public List<Emote> load() {
				return catalogo;
			}
		};
		EmoteCatalogService servico = new EmoteCatalogService(List.of(), embarcado);
		return MockMvcBuilders.standaloneSetup(new EmoteController(servico, images)).build();
	}

	private static MockMvc mvc(List<Emote> catalogo) {
		return mvc(new EmoteImageCache((url) -> new StoredImage(new byte[] { 1 }, "image/png")), catalogo);
	}

	private static MockMvc mvc() {
		return mvc(List.of(KEKW, OMEGALUL));
	}

	@Test
	void listaPaginaAPaginaComOsEnderecosDeImagem() throws Exception {
		mvc().perform(get("/emotes").param("limit", "1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].code").value("OMEGALUL"))
			.andExpect(jsonPath("$.items[0].image").value("/emotes/image/FFZ/128054?variant=full"))
			.andExpect(jsonPath("$.items[0].thumb").value("/emotes/image/FFZ/128054?variant=thumb"))
			.andExpect(jsonPath("$.total").value(2))
			.andExpect(jsonPath("$.totalPages").value(2));
	}

	@Test
	void buscaEFiltroChegamNoServico() throws Exception {
		mvc().perform(get("/emotes").param("q", "kek").param("provider", "BTTV"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].code").value("KEKW"));
	}

	@Test
	void indiceTrazCodigoParaEnderecoDaImagem() throws Exception {
		mvc().perform(get("/emotes/index"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.entries.OMEGALUL").value("/emotes/image/FFZ/128054?variant=full"))
			.andExpect(jsonPath("$.entries.KEKW").value("/emotes/image/BTTV/abc?variant=full"));
	}

	@Test
	void indiceMarcaFigurinhaAnimadaNoProprioEndereco() throws Exception {
		mvc(List.of(CATJAM)).perform(get("/emotes/index"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.entries.catJAM").value("/emotes/image/FFZ/777171?variant=full&a=1"));
	}

	@Test
	void respostaTrazEtagECacheParaOLeitor() throws Exception {
		// O mesmo servico nas duas chamadas: o etag carrega o horario da montagem, e dois catalogos
		// montados em segundos diferentes nunca batem.
		MockMvc servidor = mvc();
		MvcResult resultado = servidor.perform(get("/emotes/index")).andExpect(status().isOk()).andReturn();
		String etag = resultado.getResponse().getHeader("ETag");

		assertThat(etag).isNotBlank();
		servidor.perform(get("/emotes/index").header("If-None-Match", etag)).andExpect(status().isNotModified());
	}

	@Test
	void imagemVemDoCacheComCacheLongo() throws Exception {
		EmoteImageCache comImagem = new EmoteImageCache((url) -> new StoredImage(new byte[] { 1, 2 }, "image/png"));

		mvc(comImagem, List.of(KEKW)).perform(get("/emotes/image/BTTV/abc").param("variant", "thumb"))
			.andExpect(status().isOk())
			.andExpect(header().string("Content-Type", "image/png"))
			.andExpect(header().string("X-Content-Type-Options", "nosniff"))
			.andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("immutable")));
	}

	@Test
	void imagemDeFigurinhaAnimadaPedeOArquivoEmMovimento() throws Exception {
		List<String> buscados = new ArrayList<>();
		EmoteImageCache comImagem = new EmoteImageCache((url) -> {
			buscados.add(url);
			return new StoredImage(new byte[] { 1 }, "image/webp");
		});

		mvc(comImagem, List.of(CATJAM)).perform(get("/emotes/image/FFZ/777171").param("variant", "full"))
			.andExpect(status().isOk());

		assertThat(buscados).containsExactly("https://cdn.frankerfacez.com/emote/777171/animated/2");
	}

	@Test
	void imagemDeFigurinhaDesconhecidaNaoTentaOArquivoAnimado() throws Exception {
		// Regressao: o endereco animado de uma estatica no FFZ fica pendurado (sem 404) ate o timeout.
		List<String> buscados = new ArrayList<>();
		EmoteImageCache comImagem = new EmoteImageCache((url) -> {
			buscados.add(url);
			return new StoredImage(new byte[] { 1 }, "image/png");
		});

		mvc(comImagem, List.of(KEKW)).perform(get("/emotes/image/FFZ/999").param("variant", "full"))
			.andExpect(status().isOk())
			.andExpect(header().string("Content-Type", "image/png"));

		assertThat(buscados).containsExactly("https://cdn.frankerfacez.com/emote/999/2");
	}

	@Test
	void imagemDeProvedorDesconhecidoResponde404() throws Exception {
		mvc().perform(get("/emotes/image/SEILA/abc")).andExpect(status().isNotFound());
		mvc().perform(get("/emotes/image/FFZ/..%2F..%2Fetc")).andExpect(status().isNotFound());
	}
}