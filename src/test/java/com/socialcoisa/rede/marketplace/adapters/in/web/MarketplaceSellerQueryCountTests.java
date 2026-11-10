package com.socialcoisa.rede.marketplace.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * O catalogo do marketplace mostra "por @fulano" em cada anuncio e busca o perfil dos vendedores EM
 * LOTE. Antes era uma consulta por vendedor distinto: uma pagina de 20 anuncios de 20 pessoas custava
 * 20 idas ao banco so para escrever os nomes.
 *
 * <p>A prova e a CONTAGEM de consultas a {@code users} durante a listagem: nao pode crescer com o
 * numero de vendedores distintos na pagina.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class MarketplaceSellerQueryCountTests {

	@TestConfiguration
	static class InspectorConfig {

		@Bean
		CountingInspector countingInspector() {
			return new CountingInspector();
		}

		@Bean
		org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer statementInspector(
				CountingInspector countingInspector) {
			return properties -> properties.put(
				org.hibernate.cfg.AvailableSettings.STATEMENT_INSPECTOR, countingInspector);
		}
	}

	/** Conta as instrucoes SQL da thread do teste (o MockMvc roda na propria thread). */
	static class CountingInspector implements StatementInspector {

		private final AtomicReference<List<String>> captured =
			new AtomicReference<>(java.util.Collections.synchronizedList(new ArrayList<>()));

		@Override
		public String inspect(String sql) {
			captured.get().add(sql);
			return sql;
		}

		void reset() {
			captured.set(java.util.Collections.synchronizedList(new ArrayList<>()));
		}

		List<String> sql() {
			return List.copyOf(captured.get());
		}
	}

	@Autowired private MockMvc mockMvc;
	@Autowired private ObjectMapper objectMapper;
	@Autowired private CountingInspector inspector;

	private final String sufixo = UUID.randomUUID().toString().substring(0, 8);

	@Test
	void catalogoBuscaOsVendedoresEmUmaConsulta() throws Exception {
		// Tres anuncios de TRES vendedores distintos: o pior caso para a busca por item.
		for (int i = 0; i < 3; i++) {
			var vendedor = new TestAuthHelper(mockMvc, objectMapper).authenticate("mktq_" + sufixo + "_" + i);
			mockMvc.perform(post("/marketplace/items")
					.header("X-Session-Token", vendedor.token())
					.header("Idempotency-Key", "mktq-" + sufixo + "-" + i)
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(Map.of(
						"kind", "TEMPLATE", "title", "Item de teste " + sufixo + " " + i,
						"description", "Descricao", "content", "conteudo", "price", 50_000))))
				.andExpect(status().isOk());
		}

		inspector.reset();
		// O catalogo e compartilhado entre classes (outras suites publicam anuncios): o que o teste
		// afirma e o comportamento do lote, nao o tamanho da pagina.
		mockMvc.perform(get("/marketplace").param("limit", "60"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.title == 'Item de teste " + sufixo + " 0')]").isNotEmpty())
			.andExpect(jsonPath("$[?(@.title == 'Item de teste " + sufixo + " 1')]").isNotEmpty())
			.andExpect(jsonPath("$[?(@.title == 'Item de teste " + sufixo + " 2')]").isNotEmpty());

		long userQueries = inspector.sql().stream()
			.filter(sql -> sql.toLowerCase().contains("from users"))
			.count();

		// Teto fixo: os perfis de TODOS os vendedores da pagina em UMA consulta (antes: uma por
		// vendedor distinto). O catalogo compartilhado pode ter 21 anuncios; continua uma consulta.
		assertThat(userQueries)
			.as("consultas a users ao listar o catalogo: " + inspector.sql())
			.isLessThanOrEqualTo(2);
	}
}
