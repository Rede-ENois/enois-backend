package com.socialcoisa.rede.groups;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestAuthHelper.AuthSession;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import(GroupListQueryCountTests.InspectorConfig.class)
class GroupListQueryCountTests {

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

		private final AtomicReference<Thread> recordingThread = new AtomicReference<>();
		private final AtomicReference<List<String>> captured = new AtomicReference<>(new ArrayList<>());

		@Override
		public String inspect(String sql) {
			if (Thread.currentThread() == recordingThread.get()) {
				captured.get().add(sql);
			}
			return sql;
		}

		void reset() {
			captured.set(new ArrayList<>());
			recordingThread.set(Thread.currentThread());
		}

		long consultas() {
			return captured.get().stream()
				.map(sql -> sql.strip().toLowerCase(Locale.ROOT))
				.filter(sql -> sql.startsWith("select"))
				.filter(sql -> sql.contains("from group_members") || sql.contains("from group_join_requests"))
				.count();
		}
	}

	private record Medicao(JsonNode itens, long consultas) {
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private CountingInspector inspector;

	@Test
	void descobertaConsultaDadosDosCartoesPorPagina() throws Exception {
		String marcador = "grqlote" + UUID.randomUUID().toString().substring(0, 8);
		TestAuthHelper auth = new TestAuthHelper(mockMvc, objectMapper);
		AuthSession dono = auth.authenticate("grq_d_" + marcador);
		AuthSession leitor = auth.authenticate("grq_l_" + marcador);
		AuthSession outro = auth.authenticate("grq_o_" + marcador);

		List<String> grupos = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			String modo = i > 0 && i < 5 ? "APPROVAL" : "OPEN";
			grupos.add(criar(dono, marcador, i, modo));
		}

		entrar(leitor, grupos.get(0), "membro");
		entrar(leitor, grupos.get(1), "pendente");
		entrar(leitor, grupos.get(2), "pedido-antigo");
		mockMvc.perform(patch("/groups/{id}", grupos.get(2))
				.header("X-Session-Token", dono.token())
				.header("Idempotency-Key", "grq-abre-" + grupos.get(2))
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("joinMode", "OPEN"))))
			.andExpect(status().isOk());
		entrar(leitor, grupos.get(2), "entrada-aberta");
		mockMvc.perform(get("/groups/{id}/requests", grupos.get(2)).header("X-Session-Token", dono.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1));

		entrar(leitor, grupos.get(3), "recusado");
		var pedidos = mockMvc.perform(get("/groups/{id}/requests", grupos.get(3))
				.header("X-Session-Token", dono.token()))
			.andExpect(status().isOk())
			.andReturn();
		String pedido = objectMapper.readTree(pedidos.getResponse().getContentAsString())
			.get("items").get(0).get("id").asText();
		mockMvc.perform(post("/groups/{id}/requests/{pedido}/reject", grupos.get(3), pedido)
				.header("X-Session-Token", dono.token())
				.header("Idempotency-Key", "grq-recusa-" + pedido))
			.andExpect(status().isOk());
		entrar(outro, grupos.get(4), "outra-pessoa");

		JsonNode anonimo = compararPaginas(marcador, null);
		JsonNode pessoal = compararPaginas(marcador, leitor.token());
		JsonNode proprietario = compararPaginas(marcador, dono.token());

		for (JsonNode item : anonimo) {
			assertThat(item.get("viewerRole").isNull()).isTrue();
			assertThat(item.get("joined").asBoolean()).isFalse();
			assertThat(item.get("requestPending").asBoolean()).isFalse();
		}
		for (JsonNode item : proprietario) {
			assertThat(item.get("viewerRole").asText()).isEqualTo("OWNER");
			assertThat(item.get("requestPending").asBoolean()).isFalse();
		}

		JsonNode membro = item(pessoal, grupos.get(0));
		assertThat(membro.get("members").asInt()).isEqualTo(2);
		assertThat(membro.get("viewerRole").asText()).isEqualTo("MEMBER");
		assertThat(membro.get("joined").asBoolean()).isTrue();

		JsonNode pendente = item(pessoal, grupos.get(1));
		assertThat(pendente.get("members").asInt()).isEqualTo(1);
		assertThat(pendente.get("viewerRole").isNull()).isTrue();
		assertThat(pendente.get("joined").asBoolean()).isFalse();
		assertThat(pendente.get("requestPending").asBoolean()).isTrue();

		JsonNode membroComPedido = item(pessoal, grupos.get(2));
		assertThat(membroComPedido.get("viewerRole").asText()).isEqualTo("MEMBER");
		assertThat(membroComPedido.get("requestPending").asBoolean()).isFalse();
		assertThat(item(pessoal, grupos.get(3)).get("requestPending").asBoolean()).isFalse();
		assertThat(item(pessoal, grupos.get(4)).get("requestPending").asBoolean()).isFalse();
		assertThat(item(pessoal, grupos.get(5)).get("requestPending").asBoolean()).isFalse();
	}

	private String criar(AuthSession dono, String marcador, int numero, String modo) throws Exception {
		var resposta = mockMvc.perform(post("/groups")
				.header("X-Session-Token", dono.token())
				.header("Idempotency-Key", "grq-cria-" + marcador + "-" + numero)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"name", "Grupo " + marcador + " " + numero,
					"description", "Grupo de teste",
					"joinMode", modo))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(resposta.getResponse().getContentAsString()).get("id").asText();
	}

	private void entrar(AuthSession pessoa, String grupo, String chave) throws Exception {
		mockMvc.perform(post("/groups/{id}/join", grupo)
				.header("X-Session-Token", pessoa.token())
				.header("Idempotency-Key", "grq-entra-" + grupo + "-" + chave))
			.andExpect(status().isOk());
	}

	private JsonNode compararPaginas(String marcador, String token) throws Exception {
		Medicao pequena = medir(marcador, token, 2);
		Medicao grande = medir(marcador, token, 6);
		assertThat(pequena.itens().size()).isEqualTo(2);
		assertThat(grande.itens().size()).isEqualTo(6);
		assertThat(grande.consultas()).isEqualTo(pequena.consultas());
		assertThat(grande.consultas()).isEqualTo(token == null ? 1 : 3);

		for (JsonNode item : grande.itens()) {
			var requisicao = get("/groups/{slug}", item.get("id").asText());
			if (token != null) {
				requisicao.header("X-Session-Token", token);
			}
			var resposta = mockMvc.perform(requisicao).andExpect(status().isOk()).andReturn();
			assertThat(item).isEqualTo(objectMapper.readTree(resposta.getResponse().getContentAsString()));
		}

		Medicao vazia = medir(marcador + "ausente", token, 6);
		assertThat(vazia.itens().size()).isZero();
		assertThat(vazia.consultas()).isZero();
		return grande.itens();
	}

	private Medicao medir(String marcador, String token, int limite) throws Exception {
		var requisicao = get("/groups").param("query", marcador).param("limit", String.valueOf(limite));
		if (token != null) {
			requisicao.header("X-Session-Token", token);
		}
		inspector.reset();
		var resposta = mockMvc.perform(requisicao).andExpect(status().isOk()).andReturn();
		long consultas = inspector.consultas();
		JsonNode itens = objectMapper.readTree(resposta.getResponse().getContentAsString()).get("items");
		return new Medicao(itens, consultas);
	}

	private JsonNode item(JsonNode itens, String id) {
		for (JsonNode item : itens) {
			if (id.equals(item.get("id").asText())) {
				return item;
			}
		}
		throw new AssertionError("Grupo ausente da página: " + id);
	}
}
