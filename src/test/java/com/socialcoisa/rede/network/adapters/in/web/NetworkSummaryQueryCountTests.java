package com.socialcoisa.rede.network.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.hibernate.cfg.AvailableSettings;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import(NetworkSummaryQueryCountTests.InspectorConfig.class)
class NetworkSummaryQueryCountTests {

	@TestConfiguration
	static class InspectorConfig {

		@Bean
		CountingInspector countingInspector() {
			return new CountingInspector();
		}

		@Bean
		HibernatePropertiesCustomizer statementInspector(CountingInspector inspector) {
			return properties -> properties.put(AvailableSettings.STATEMENT_INSPECTOR, inspector);
		}
	}

	static class CountingInspector implements StatementInspector {

		private final ThreadLocal<List<String>> capturadas = new ThreadLocal<>();

		@Override
		public String inspect(String sql) {
			List<String> consultas = capturadas.get();
			if (consultas != null) {
				consultas.add(sql);
			}
			return sql;
		}

		void iniciar() {
			capturadas.set(new ArrayList<>());
		}

		List<String> encerrar() {
			List<String> consultas = List.copyOf(capturadas.get());
			capturadas.remove();
			return consultas;
		}
	}

	@Autowired private MockMvc mockMvc;
	@Autowired private ObjectMapper objectMapper;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private CountingInspector inspector;

	@Test
	void resumoContaPedidosVisiveisSemCarregarALista() throws Exception {
		String sufixo = UUID.randomUUID().toString().substring(0, 8);
		var auth = new TestAuthHelper(mockMvc, objectMapper);
		var eu = auth.authenticate("nsq_eu_" + sufixo);
		var visivel = auth.authenticate("nsq_visivel_" + sufixo);
		var silenciado = auth.authenticate("nsq_silenciado_" + sufixo);
		var bloqueado = auth.authenticate("nsq_bloqueado_" + sufixo);
		var bloqueador = auth.authenticate("nsq_bloqueador_" + sufixo);
		var removido = auth.authenticate("nsq_removido_" + sufixo);
		var enviada = auth.authenticate("nsq_enviada_" + sufixo);
		var aceita = auth.authenticate("nsq_aceita_" + sufixo);

		mockMvc.perform(get("/network/summary").header("X-Session-Token", eu.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.pendingRequests").value(0));

		conexao(visivel.userId(), eu.userId(), "PENDING");
		conexao(silenciado.userId(), eu.userId(), "PENDING");
		conexao(bloqueado.userId(), eu.userId(), "PENDING");
		conexao(bloqueador.userId(), eu.userId(), "PENDING");
		conexao(removido.userId(), eu.userId(), "PENDING");
		conexao("nsq_ausente_" + sufixo, eu.userId(), "PENDING");
		conexao(eu.userId(), enviada.userId(), "PENDING");
		conexao(eu.userId(), eu.userId(), "PENDING");
		conexao(aceita.userId(), eu.userId(), "ACCEPTED");
		jdbc.update("insert into user_blocks (blocker_id, blocked_id, created_at) values (?, ?, current_timestamp)",
			eu.userId(), bloqueado.userId());
		jdbc.update("insert into user_blocks (blocker_id, blocked_id, created_at) values (?, ?, current_timestamp)",
			bloqueador.userId(), eu.userId());
		jdbc.update("insert into user_mutes (muter_id, muted_id, created_at) values (?, ?, current_timestamp)",
			eu.userId(), silenciado.userId());
		jdbc.update("update users set username = ? where id = ?", "removed-" + sufixo, removido.userId());

		mockMvc.perform(get("/network/requests").header("X-Session-Token", eu.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(4))
			.andExpect(jsonPath("$.items[?(@.peer.id == '" + visivel.userId() + "')]").isNotEmpty())
			.andExpect(jsonPath("$.items[?(@.peer.id == '" + silenciado.userId() + "')]").isNotEmpty());

		List<String> consultas;
		inspector.iniciar();
		try {
			mockMvc.perform(get("/network/summary").header("X-Session-Token", eu.token()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.connections").value(1))
				.andExpect(jsonPath("$.pendingRequests").value(2));
		}
		finally {
			consultas = inspector.encerrar();
		}

		var consultasDeConexao = consultas.stream()
			.map(sql -> sql.toLowerCase(Locale.ROOT))
			.filter(sql -> sql.contains("from user_connections"))
			.toList();
		assertThat(consultasDeConexao)
			.as("consultas de conexões no resumo: " + consultas)
			.hasSize(3)
			.allMatch(sql -> sql.contains("select count("));
	}

	private void conexao(String origem, String destino, String estado) {
		jdbc.update("""
			insert into user_connections (requester_user_id, receiver_user_id, status, created_at)
			values (?, ?, ?, current_timestamp)
			""", origem, destino, estado);
	}
}
