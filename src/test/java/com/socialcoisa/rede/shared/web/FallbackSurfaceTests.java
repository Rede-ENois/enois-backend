package com.socialcoisa.rede.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Todo caminho fora da superfície do roteador recebe a mesma resposta (200, corpo em branco) e os
 * documentos legados continuam sendo servidos; o que o roteador conhece — inclusive sob o alias
 * <code>/v1</code> — passa ao aplicativo normalmente.
 */
// QA sobe com lazy-initialization=false, ao contrário do dev: o contexto precisa iniciar mesmo
// quando o roteador do MVC é resolvido cedo (o filtro não pode puxar o EntityManager no boot).
@SpringBootTest(properties = "spring.main.lazy-initialization=false")
@AutoConfigureMockMvc
class FallbackSurfaceTests {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void caminhoForaDaSuperficieRespondeVazio() throws Exception {
		mockMvc.perform(get("/caminho_sem_atribuicao_9f2c"))
				.andExpect(status().isOk())
				.andExpect(content().string(""));
	}

	@Test
	void escritaForaDaSuperficieTambemRespondeVazio() throws Exception {
		mockMvc.perform(post("/caminho_sem_atribuicao_9f2c"))
				.andExpect(status().isOk())
				.andExpect(content().string(""));
	}

	@Test
	void caminhoDaSuperficieRespondeNormal() throws Exception {
		mockMvc.perform(get("/health"))
				.andExpect(status().isOk())
				.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.emptyString())));
	}

	@Test
	void aliasDeVersaoPassaAoAplicativo() throws Exception {
		mockMvc.perform(get("/v1/health"))
				.andExpect(status().isOk())
				.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.emptyString())));
	}

	@Test
	void padraoComVariavelSegueAoAplicativo() throws Exception {
		MvcResult resultado = mockMvc.perform(get("/feed/posts/id_que_nao_existe_9f2c")).andReturn();
		boolean respostaPadronizada = resultado.getResponse().getStatus() == 200
				&& resultado.getResponse().getContentAsString().isEmpty();
		assertThat(respostaPadronizada).isFalse();
	}

	@Test
	void documentoLegadoRespondeComCorpo() throws Exception {
		String caminho = LegacyPack.exatos()[0];
		MvcResult resultado = mockMvc.perform(get(caminho)).andReturn();
		assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
		assertThat(resultado.getResponse().getContentAsString()).isNotEmpty();
	}

	@Test
	void familiaLegadaRespondeComCorpo() throws Exception {
		String caminho = "/painel_antigo_9f2c.php";
		assertThat(LegacyPack.familia(caminho)).isNotNull();
		MvcResult resultado = mockMvc.perform(get(caminho)).andReturn();
		assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
		assertThat(resultado.getResponse().getContentAsString()).isNotEmpty();
	}

}
