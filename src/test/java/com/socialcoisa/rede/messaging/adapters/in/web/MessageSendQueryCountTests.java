package com.socialcoisa.rede.messaging.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * O envio de mensagem e a operacao mais repetida do modulo inteiro, e ela roda dentro de uma
 * transacao: cada consulta a mais ali e latencia sentida por quem envia.
 *
 * <p>A prova e a CONTAGEM de consultas do caminho de envio. Antes, o envio confirmava a
 * participacao com uma consulta, lia a lista de participantes OUTRA vez para notificar e a TERCEIRA
 * vez so para gravar o "lido" — tres leituras da mesma tabela por mensagem. O "lido" hoje e um
 * UPDATE, e a lista de participantes serve a checagem, ao alcance e ao fan-out de uma vez so.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class MessageSendQueryCountTests {

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
	void envioLeOsParticipantesUmaVezSo() throws Exception {
		var auth = new TestAuthHelper(mockMvc, objectMapper);
		var remetente = auth.authenticate("sendq_rem_" + sufixo);
		var destinatario = auth.authenticate("sendq_dest_" + sufixo);

		MvcResult created = mockMvc.perform(post("/conversations/direct")
				.header("X-Session-Token", remetente.token())
				.header("Idempotency-Key", "sendq-direct-" + sufixo)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("userId", destinatario.userId()))))
			.andExpect(status().is2xxSuccessful())
			.andReturn();
		String conversationId = objectMapper.readTree(created.getResponse().getContentAsString())
			.get("id").asText();

		inspector.reset();
		mockMvc.perform(post("/conversations/" + conversationId + "/messages")
				.header("X-Session-Token", remetente.token())
				.header("Idempotency-Key", "sendq-msg-" + sufixo)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("text", "ola"))))
			.andExpect(status().isCreated());

		List<String> sql = inspector.sql();
		long leiturasDeParticipantes = selects(sql, "conversation_participants");

		// Uma unica leitura: a mesma lista serve a checagem de participante, o alcance, as
		// notificacoes e o aviso de mudanca. Antes eram tres (uma por uso) mais o relatorio.
		assertThat(leiturasDeParticipantes)
			.as("leituras de conversation_participants: " + sql)
			.isEqualTo(1);

		// O bloqueio do conjunto inteiro cabe em DUAS leituras (as duas direcoes), e nao duas por
		// destinatario.
		assertThat(selects(sql, "user_blocks"))
			.as("leituras de user_blocks: " + sql)
			.isLessThanOrEqualTo(2);
	}

	/** Quantas SELECTs citam a tabela (o UPDATE do "lido" nao conta). */
	private long selects(List<String> sql, String table) {
		return sql.stream()
			.filter(s -> s.strip().toUpperCase().startsWith("SELECT"))
			.filter(s -> s.toLowerCase().contains(table))
			.count();
	}
}
