package com.socialcoisa.rede.messaging.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * A lista de conversas do chat (GET /conversations) nao pode fazer consultas por item.
 *
 * <p>Antes: cada conversa da pagina disparava 4 a 5 consultas (participantes, participante do
 * leitor + contagem de nao lidas, ultima mensagem e identidade profissional), entao uma pagina de
 * 20 custava ~80 a 100 consultas. Como a tela do chat fica aberta o tempo todo, esse e o caminho
 * mais quente depois do feed. A prova aqui e a CONTAGEM de consultas reais, nao o tempo:
 * com os lotes, o numero nao pode crescer com o tamanho da pagina.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConversationListQueryCountTests {

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
	void listaDeConversasNaoCresceComOTamanhoDaPagina() throws Exception {
		var leitor = new TestAuthHelper(mockMvc, objectMapper).authenticate("convq_leitor_" + sufixo);

		// Tres conversas de uma pessoa: com consulta por item seriam ~5 por conversa.
		for (int i = 0; i < 3; i++) {
			criarConversaCom(leitor, "convq_par_" + sufixo + "_" + i);
		}

		inspector.reset();
		mockMvc.perform(get("/conversations").header("X-Session-Token", leitor.token()))
			.andExpect(status().isOk());

		long selects = inspector.sql().stream()
			.filter(sql -> sql.strip().toUpperCase().startsWith("SELECT"))
			.count();

		// Teto fixo: a lista de 3 conversas nao pode passar de um punhado de consultas. Com
		// consulta por item seriam ~15 (5 x 3); com lotes ficam poucas, independentes da pagina.
		assertThat(selects)
			.as("SELECTs para listar 3 conversas: " + inspector.sql())
			.isLessThanOrEqualTo(8);
	}

	private void criarConversaCom(TestAuthHelper.AuthSession leitor, String outro) throws Exception {
		var par = new TestAuthHelper(mockMvc, objectMapper).authenticate(outro);
		mockMvc.perform(post("/conversations")
				.header("X-Session-Token", leitor.token())
				.header("Idempotency-Key", "convq-" + outro)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"kind", "DIRECT", "participantIds", List.of(par.userId())))))
			.andExpect(status().isCreated());
	}
}
