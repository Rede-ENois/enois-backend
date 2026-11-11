package com.socialcoisa.rede.services.adapters.in.web;

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
 * Os DOIS catálogos de serviços mostram o @ / o nome de quem presta, e buscam o perfil desse
 * prestador em LOTE. Antes era uma consulta por linha: o `/servicos` (diretório de ofertas e
 * pedidos) e o `/services` (serviços da comunidade) chamavam `findById` dentro do laço, e cada
 * `findById` carregava o perfil inteiro com as três coleções EAGER — quatro idas ao banco por
 * prestador, numa página de até 60.
 *
 * <p>A prova é a CONTAGEM de consultas a {@code users} durante a listagem: não pode crescer com o
 * número de prestadores distintos na página.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ServicesCatalogQueryCountTests {

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

	/** Conta as instruções SQL da thread do teste (o MockMvc roda na própria thread). */
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

		long queriesOnUsers() {
			return captured.get().stream()
				.filter(sql -> sql.toLowerCase().contains("from users"))
				.count();
		}
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private CountingInspector inspector;

	private final String sufixo = UUID.randomUUID().toString().substring(0, 8);

	@Test
	void diretorioDeServicosBuscaOsPrestadoresEmUmaConsulta() throws Exception {
		// Três ofertas de TRÊS prestadores distintos: o pior caso para a busca por linha.
		for (int i = 0; i < 3; i++) {
			var prestador = new TestAuthHelper(mockMvc, objectMapper).authenticate("svcq_dir_" + sufixo + "_" + i);
			mockMvc.perform(post("/servicos/ofertas")
					.header("X-Session-Token", prestador.token())
					.header("Idempotency-Key", "svcq-dir-" + sufixo + "-" + i)
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(Map.of(
						"category", "DESENVOLVIMENTO", "title", "Oferta de teste " + sufixo + " " + i,
						"description", "Uma oferta de teste do catalogo de servicos.",
						"deliverables", "Codigo e documentacao", "price", 30_000, "deliveryDays", 5))))
				.andExpect(status().isOk());
		}

		inspector.reset();
		// O catálogo é compartilhado entre as classes do contexto: o que se afirma é o
		// comportamento do lote, não o tamanho da página.
		var resposta = mockMvc.perform(get("/servicos").param("listings", "60"))
			.andExpect(status().isOk())
			.andReturn();

		var ofertas = objectMapper.readTree(resposta.getResponse().getContentAsString()).get("ofertas");
		long nossas = 0;
		for (var oferta : ofertas) {
			if (oferta.get("title").asText().contains(sufixo)) {
				nossas++;
				// O nome de quem presta continua saindo igual: o lote não perdeu a informação.
				assertThat(oferta.get("sellerName").asText()).startsWith("svcq_dir_");
			}
		}
		assertThat(nossas).isEqualTo(3);

		assertThat(inspector.queriesOnUsers())
			.as("consultas a users ao listar o diretório: " + inspector.sql())
			.isLessThanOrEqualTo(2);
	}

	@Test
	void catalogoDeServicosDaComunidadeBuscaOsPrestadoresEmUmaConsulta() throws Exception {
		var admin = new TestAuthHelper(mockMvc, objectMapper).authenticate("group-admin");
		for (int i = 0; i < 3; i++) {
			var prestador = new TestAuthHelper(mockMvc, objectMapper).authenticate("svcq_com_" + sufixo + "_" + i);
			var criado = mockMvc.perform(post("/services")
					.header("X-Session-Token", prestador.token())
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(Map.of(
						"title", "Servico de teste " + sufixo + " " + i,
						"description", "Um servico de teste do catalogo da comunidade.",
						"link", "", "category", "OUTROS"))))
				.andExpect(status().isOk())
				.andReturn();
			String serviceId = objectMapper.readTree(criado.getResponse().getContentAsString()).get("id").asText();
			mockMvc.perform(post("/moderation/services/" + serviceId + "/approve")
					.header("X-Session-Token", admin.token())
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(Map.of("note", "ok"))))
				.andExpect(status().isOk());
		}

		inspector.reset();
		var resposta = mockMvc.perform(get("/services").param("limit", "60"))
			.andExpect(status().isOk())
			.andReturn();

		var servicos = objectMapper.readTree(resposta.getResponse().getContentAsString());
		long nossos = 0;
		for (var servico : servicos) {
			if (servico.get("title").asText().contains(sufixo)) {
				nossos++;
				// O @ do prestador continua saindo igual: é ele que monta o endereço público.
				assertThat(servico.get("username").asText()).startsWith("svcq-com-");
			}
		}
		assertThat(nossos).isEqualTo(3);

		assertThat(inspector.queriesOnUsers())
			.as("consultas a users ao listar o catálogo: " + inspector.sql())
			.isLessThanOrEqualTo(2);
	}
}