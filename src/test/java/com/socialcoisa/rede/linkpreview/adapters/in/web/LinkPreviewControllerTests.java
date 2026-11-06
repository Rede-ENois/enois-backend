package com.socialcoisa.rede.linkpreview.adapters.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.linkpreview.application.LinkPreviewService;
import com.socialcoisa.rede.linkpreview.domain.ExternalLinkPreview;
import com.socialcoisa.rede.linkpreview.domain.LinkPreview;

/**
 * O contrato da rota: leitura sem sessao, corpo no formato que o front espera e a resposta marcada
 * para nao ir a cache compartilhado. O teste vive aqui porque a lista de caminhos publicos muda
 * facil, e a rota precisa continuar aberta e nao-cacheavel depois de qualquer mudanca nela.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LinkPreviewControllerTests {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private LinkPreviewService previews;

	@Test
	void leituraAnonimaDevolveOCartaoDoSiteDeFora() throws Exception {
		when(previews.previewOf(eq("https://exemplo.com/artigo"), any())).thenReturn(Optional.of(
			LinkPreview.external(new ExternalLinkPreview("https://exemplo.com/artigo", "Titulo", "Resumo",
				"https://exemplo.com/c.png", "Exemplo"))));

		mockMvc.perform(get("/link-preview").param("url", "https://exemplo.com/artigo"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.preview.kind").value("EXTERNAL"))
			.andExpect(jsonPath("$.preview.title").value("Titulo"))
			.andExpect(jsonPath("$.preview.siteName").value("Exemplo"))
			// A resposta depende de quem le: cache compartilhado nunca pode servir para outra pessoa.
			.andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
	}

	@Test
	void semNadaParaMostrarResponde200ComPreviewNulo() throws Exception {
		when(previews.previewOf(eq("https://fora-do-ar.test/x"), any())).thenReturn(Optional.empty());

		mockMvc.perform(get("/link-preview").param("url", "https://fora-do-ar.test/x"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.preview").doesNotExist());
	}

	@Test
	void urlAusenteNaoQuebraARota() throws Exception {
		mockMvc.perform(get("/link-preview")).andExpect(status().isBadRequest());
	}
}
