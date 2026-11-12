package com.socialcoisa.rede.marketplace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.coin.application.CoinService;
import com.socialcoisa.rede.marketplace.application.MarketplaceSanitizeWorker;
import com.socialcoisa.rede.marketplace.application.port.out.MarketplaceRepository;
import com.socialcoisa.rede.marketplace.domain.MarketplaceListing;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * O que a limpeza REMOVE é a regra de negócio; a contagem de consultas é só o custo.
 *
 * <p>Estes casos travam os quatro critérios de resquício: item vendido que não existe mais, tema
 * apagado (ou de outro autor), conta de teste reconhecida pelo padrão de triagem e conta que
 * sumiu. Uma lista saudável tem de sobreviver intacta.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class MarketplaceSanitizeBehaviorTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private MarketplaceSanitizeWorker worker;

	@Autowired
	private MarketplaceRepository repository;

	@Autowired
	private JdbcTemplate jdbc;

	/** Publica um template e devolve o id do ITEM (a resposta traz o id da listagem). */
	private String publishTemplate(TestAuthHelper.AuthSession seller, String title) throws Exception {
		mockMvc.perform(post("/marketplace/items")
				.header("X-Session-Token", seller.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"kind", "TEMPLATE",
					"title", title,
					"description", "Template para o teste de limpeza",
					"content", "<section><h1>Oi</h1></section>",
					"price", CoinService.FAIS(50)))))
			.andExpect(status().isOk())
			.andReturn();
		return jdbc.queryForObject("select id from marketplace_items where seller_id = ? and title = ?",
			String.class, seller.userId(), title);
	}

	private String publishTheme(TestAuthHelper.AuthSession seller, String name) throws Exception {
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
		return themeId;
	}

	private List<String> activeItemIds() {
		return repository.findAll(500).stream()
			.filter(listing -> listing.status() == MarketplaceListing.Status.ACTIVE)
			.map(MarketplaceListing::itemId)
			.toList();
	}

	@Test
	void theSanitizingPassRemovesOnlyTheLeftoversAndKeepsHealthyListings() throws Exception {
		var auth = new TestAuthHelper(mockMvc, objectMapper);
		var seller = auth.authenticate("mkt_sane_seller");
		// A triagem reconhece contas de teste pelo padrão "probe" no nome; a conta abaixo é uma delas.
		var probe = auth.authenticate("mkt_probe_seller");
		var banned = auth.authenticate("mkt_banned_seller");

		String healthyTemplate = publishTemplate(seller, "Template que fica");
		String orphanTemplate = publishTemplate(seller, "Template sem item");
		String healthyTheme = publishTheme(seller, "Tema que fica");
		String orphanTheme = publishTheme(seller, "Tema apagado");
		String probeTemplate = publishTemplate(probe, "Template de conta de teste");
		String bannedTemplate = publishTemplate(banned, "Template do vendedor banido");

		// Resquícios: o item vendido não existe mais e o tema foi apagado da galeria.
		jdbc.update("delete from marketplace_items where id = ?", orphanTemplate);
		jdbc.update("delete from theme_gallery where id = ?", orphanTheme);
		// Vendedor banido: sanção ativa do tipo BAN (a triagem insere direto no banco).
		jdbc.update("""
			insert into user_sanctions (id, user_id, type, reason, status, created_at)
			values (?, ?, 'BAN', 'Conta de teste banida na triagem.', 'ACTIVE', now())
			""", "san_mkt_ban", banned.userId());

		int removed = worker.sanitizeNow();

		// A base H2 é compartilhada entre classes: outra classe pode ter deixado resquício que
		// esta mesma passagem também remove. O número GLOBAL não é deste teste para afirmar; o
		// que é nosso é o destino dos quatro casos criados aqui, verificado abaixo.
		assertThat(removed).as("a passagem removeu ao menos os quatro resquícios deste teste")
			.isGreaterThanOrEqualTo(4);
		assertThat(activeItemIds())
			.as("as listagens saudáveis continuam à venda")
			.contains(healthyTemplate, healthyTheme)
			.doesNotContain(orphanTemplate, orphanTheme, probeTemplate, bannedTemplate);
	}
}