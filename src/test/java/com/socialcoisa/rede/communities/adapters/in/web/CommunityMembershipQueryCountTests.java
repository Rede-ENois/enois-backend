package com.socialcoisa.rede.communities.adapters.in.web;

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
 * As listas de comunidades que mostram "sou membro" em cada card (favoritas e locais) consultam a
 * participacao EM LOTE — uma consulta para a pagina, nao uma por comunidade.
 *
 * <p>Antes: cada card chamava {@code isMember} (uma consulta por comunidade), entao favoritar 50
 * comunidades custava 51 consultas por abertura. A prova aqui e a CONTAGEM de consultas reais
 * durante a requisicao, que nao pode crescer com o numero de comunidades na pagina.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class CommunityMembershipQueryCountTests {

	@TestConfiguration
	static class InspectorConfig {

		@Bean
		CountingInspector countingInspector() {
			return new CountingInspector();
		}

		/** Liga o contador ao Hibernate da aplicacao (bean do proprio Hibernate, Boot 4). */
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
	void favoritasConsultamMembresiaUmaVezSo() throws Exception {
		var leitor = new TestAuthHelper(mockMvc, objectMapper).authenticate("commq_leitor_" + sufixo);
		var autor = new TestAuthHelper(mockMvc, objectMapper).authenticate("commq_autor_" + sufixo);

		// Tres comunidades favoritadas: com consulta por item seriam 3 consultas de membresia.
		for (int i = 0; i < 3; i++) {
			String id = criar(autor, "commq_com_" + sufixo + "_" + i);
			mockMvc.perform(post("/communities/" + id + "/favorite")
					.header("X-Session-Token", leitor.token())
					.header("Idempotency-Key", "commq-fav-" + i))
				.andExpect(status().isOk());
		}

		inspector.reset();
		mockMvc.perform(get("/communities/favorites").header("X-Session-Token", leitor.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.size()").value(3));

		long membershipQueries = inspector.sql().stream()
			.filter(sql -> sql.toLowerCase().contains("community_members"))
			.filter(sql -> sql.toLowerCase().contains("where"))
			.count();

		// Teto fixo: a membresia da pagina inteira em UMA consulta (antes: uma por comunidade).
		assertThat(membershipQueries)
			.as("consultas em community_members para listar 3 favoritas: " + inspector.sql())
			.isLessThanOrEqualTo(2);
	}

	private String criar(TestAuthHelper.AuthSession autor, String name) throws Exception {
		org.springframework.test.web.servlet.MvcResult result = mockMvc.perform(post("/communities")
				.header("X-Session-Token", autor.token())
				.header("Idempotency-Key", "commq-" + name)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"name", name, "description", "Comunidade de teste", "topic", "TECNOLOGIA"))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}
}
