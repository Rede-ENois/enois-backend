package com.socialcoisa.rede.marketplace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.coin.application.CoinService;
import com.socialcoisa.rede.marketplace.application.MarketplaceSanitizeWorker;
import com.socialcoisa.rede.marketplace.application.port.out.MarketplaceRepository;
import com.socialcoisa.rede.marketplace.domain.MarketplaceListing;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * A limpeza de resquícios do marketplace roda de hora em hora em cada instância: o custo dela não
 * pode crescer com o número de listagens no catálogo.
 *
 * <p>Antes: para CADA listagem ativa a rotina perguntava o vendedor (sanção), o perfil duas vezes
 * (nome suspeito e existência) e o item (tema ou genérico) — quatro viagens ao banco por linha, num
 * job que varre até 500 listagens: ~2.000 consultas por hora por instância só para concluir, na
 * esmagadora maioria dos casos, que nada precisa ser removido.</p>
 *
 * <p>A prova é a CONTAGEM das instruções SQL executadas de verdade: dobra-se o número de listagens
 * e o número de consultas tem de continuar idêntico.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MarketplaceSanitizeQueryCountTests.InspectorConfig.class)
class MarketplaceSanitizeQueryCountTests {

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

	/**
	 * Guarda as instruções SQL que o Hibernate mandou ao banco, mas só as da thread que estiver
	 * medindo: o gatilho @Scheduled da própria rotina dispara nos 90 segundos de vida do contexto
	 * e, no CI, pode correr em paralelo com a medição — sem esse filtro, o job concorrente suja a
	 * contagem (e até remove listagens no meio dela).
	 */
	static class CountingInspector implements StatementInspector {

		private final AtomicReference<List<String>> captured =
			new AtomicReference<>(Collections.synchronizedList(new ArrayList<>()));

		private final AtomicReference<String> recordingThread = new AtomicReference<>();

		@Override
		public String inspect(String sql) {
			if (Thread.currentThread().getName().equals(recordingThread.get())) {
				captured.get().add(sql);
			}
			return sql;
		}

		void reset() {
			recordingThread.set(Thread.currentThread().getName());
			captured.set(Collections.synchronizedList(new ArrayList<>()));
		}

		List<String> statements() {
			return captured.get();
		}
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private MarketplaceSanitizeWorker worker;

	@Autowired
	private CountingInspector inspector;

	@Autowired
	private MarketplaceRepository listings;

	/** Listagens ativas criadas por ESTE teste, pelas quais a rotina é responsável. */
	private Set<String> myListingIds = new HashSet<>();

	private int sanitizeCountingSelects() {
		inspector.reset();
		// Aquecimento: o banco de testes é compartilhado entre classes; resquícios de outros
		// testes podem ser removidos neste primeiro passe e não contam para a medição.
		worker.sanitizeNow();
		List<String> sql = List.copyOf(inspector.statements());
		return (int) sql.stream()
			.filter(statement -> statement.strip().toUpperCase().startsWith("SELECT"))
			.count();
	}

	private void publishTemplate(TestAuthHelper.AuthSession seller, String title) throws Exception {
		mockMvc.perform(post("/marketplace/items")
				.header("X-Session-Token", seller.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"kind", "TEMPLATE",
					"title", title,
					"description", "Template para o teste de contagem",
					"content", "<section><h1>Oi</h1></section>",
					"price", CoinService.FAIS(50)))))
			.andExpect(status().isOk());
	}

	/** Tema publicado na galeria e posto à venda: o caminho que confere o autor do tema. */
	private void publishTheme(TestAuthHelper.AuthSession seller, String name) throws Exception {
		String packageJson = """
				{"palette":{"background":"#f5e6c8","surface":"#f0e2c4","muted":"#e8d9b8",
				"border":"#d8c9a8","primary":"#7c3aed","onPrimary":"#ffffff","soft":"#ede8f5",
				"text":{"strong":"#3a2a10","subtle":"#5a4a30","date":"#777777"},"radius":"8px"}}""";
		MvcResult theme = mockMvc.perform(post("/temas/galeria")
				.header("X-Session-Token", seller.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", name, "description", "Tema de teste",
					"packageJson", packageJson, "modeDefault", "light"))))
			.andExpect(status().isOk())
			.andReturn();
		String themeId = objectMapper.readTree(theme.getResponse().getContentAsString()).get("id").asText();
		mockMvc.perform(post("/marketplace")
				.header("X-Session-Token", seller.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("itemKind", "THEME", "itemId", themeId,
					"title", name, "price", CoinService.FAIS(50)))))
			.andExpect(status().isOk());
	}

	@Test
	void theSanitizingPassCostDoesNotGrowWithTheNumberOfListings() throws Exception {
		var auth = new TestAuthHelper(mockMvc, objectMapper);
		var seller = auth.authenticate("mkt_sanitize_seller");
		for (int i = 0; i < 6; i++) {
			publishTemplate(seller, "Template saudável " + i);
		}
		publishTheme(seller, "Tema saudável");
		collectMyListings(seller);

		int withTwelve = sanitizeCountingSelects();

		for (int i = 0; i < 12; i++) {
			publishTemplate(seller, "Template extra " + i);
		}
		collectMyListings(seller);

		int withTwentyFour = sanitizeCountingSelects();

		assertThat(withTwentyFour)
			.as("a limpeza não pode consultar mais o banco por ter o dobro de listagens")
			.isEqualTo(withTwelve);
		assertThat(listings.findAll(500).stream().map(MarketplaceListing::id).toList())
			.as("as listagens saudáveis deste teste têm de continuar no catálogo")
			.containsAll(myListingIds);
	}

	private void collectMyListings(TestAuthHelper.AuthSession seller) {
		listings.findAll(500).stream()
			.filter(listing -> seller.userId().equals(listing.sellerId()))
			.map(MarketplaceListing::id)
			.forEach(myListingIds::add);
	}
}