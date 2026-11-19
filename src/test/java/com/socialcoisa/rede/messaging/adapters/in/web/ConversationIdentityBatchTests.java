package com.socialcoisa.rede.messaging.adapters.in.web;

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
 * A identidade profissional das conversas da caixa de entrada vem EM LOTE: uma consulta para a
 * pagina inteira, nao uma por conversa (item 2 da #708).
 *
 * <p>Antes: cada conversa PROFESSIONAL da pagina chamava activeByUserIds na propria linha, entao
 * uma pagina com N conversas profissionais pagava N SELECTs em corporate_profiles com o mesmo
 * trabalho repetido. A prova e a contagem de SELECTs na tabela durante o GET /conversations,
 * com a resposta continuando a trazer a identidade de cada participante.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConversationIdentityBatchTests {

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
	void identidadesProfissionaisDaPaginaValemUmaConsultaSo() throws Exception {
		var leitor = new TestAuthHelper(mockMvc, objectMapper).authenticate("convbi_leitor_" + sufixo);
		new TestAuthHelper(mockMvc, objectMapper).activateProfessional(leitor);

		// Duas conversas profissionais do leitor (business-direct exige identidade no criador),
		// com pares diferentes: com consulta por item seriam 2 SELECTs; o lote faz 1.
		for (int i = 0; i < 2; i++) {
			var par = new TestAuthHelper(mockMvc, objectMapper).authenticate("convbi_par_" + sufixo + "_" + i);
			criarConversaProfissional(leitor, par);
		}

		inspector.reset();
		mockMvc.perform(get("/conversations").header("X-Session-Token", leitor.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.context == 'PROFESSIONAL')]").isNotEmpty());

		long selectsIdentidade = inspector.sql().stream()
			.filter(sql -> sql.strip().toUpperCase().startsWith("SELECT"))
			.filter(sql -> sql.toLowerCase().contains("corporate_profiles"))
			.count();

		// Duas conversas profissionais na pagina: 1 consulta em lote, nao 1 por conversa.
		// Com consulta por item seriam 2; o lote traz ambos os pares numa ida so.
		assertThat(selectsIdentidade)
			.as("SELECTs em corporate_profiles para 2 conversas profissionais: " + inspector.sql())
			.isEqualTo(1);
	}

	private void criarConversaProfissional(TestAuthHelper.AuthSession leitor, TestAuthHelper.AuthSession par)
			throws Exception {
		mockMvc.perform(post("/conversations/business-direct")
				.header("X-Session-Token", leitor.token())
				.header("Idempotency-Key", "convbi-" + par.userId())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("userId", par.userId()))))
			.andExpect(status().isOk());
	}
}
