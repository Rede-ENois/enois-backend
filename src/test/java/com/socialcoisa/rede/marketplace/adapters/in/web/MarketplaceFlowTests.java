package com.socialcoisa.rede.marketplace.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.socialcoisa.rede.coin.application.CoinRateService;
import com.socialcoisa.rede.coin.application.CoinService;
import com.socialcoisa.rede.coin.domain.UserStreak;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

/** Escrow real: cada HTTP confirma sua transação; leituras JDBC posteriores verificam a persistência. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MarketplaceFlowTests.ConfiguracaoDeterministica.class)
class MarketplaceFlowTests {

	@Autowired private MockMvc mockMvc;
	@Autowired private ObjectMapper objectMapper;
	@Autowired private CoinRateService rates;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private PlatformTransactionManager transactions;
	@Autowired private RelogioCenario clock;

	// Só este contexto: nenhum worker dispara pelo relógio da máquina durante o cenário.
	@MockitoBean(name = "taskScheduler")
	private TaskScheduler agendador;

	@MockitoBean
	private JavaMailSender correio;

	private String marcador;
	private String cotacaoCenario;
	private final List<String> contas = new ArrayList<>();

	@BeforeEach
	void prepararCenario() {
		exigirForaDeTransacao();
		when(correio.createMimeMessage()).thenAnswer(invocacao ->
			new jakarta.mail.internet.MimeMessage((jakarta.mail.Session) null));
		marcador = "m683" + UUID.randomUUID().toString().replace("-", "");
		cotacaoCenario = null;
		contas.clear();
		Instant ultima = jdbc.query("select max(effective_at) from coin_rate_history union all select max(created_at) from coin_entries",
			(rs, row) -> rs.getTimestamp(1) == null ? Instant.EPOCH : rs.getTimestamp(1).toInstant())
			.stream().max(Instant::compareTo).orElse(Instant.EPOCH);
		Instant agora = clock.instant();
		if (agora.isAfter(ultima)) ultima = agora;
		// Não retroceder após recolher a cotação: o ledger compartilhado já confirmou lançamentos.
		clock.iniciar(ultima.atZone(UserStreak.ZONE).toLocalDate().plusDays(1)
			.atTime(12, 0).atZone(UserStreak.ZONE).toInstant());
	}

	@AfterEach
	void recolherFixturesPublicas() {
		// Saldos, hashes e ledger ficam intactos: o dinheiro continua nas contas que o receberam.
		new TransactionTemplate(transactions).executeWithoutResult(status -> {
			if (cotacaoCenario != null) {
				jdbc.update("delete from coin_rate_history where id = ?", cotacaoCenario);
			}
			for (String conta : contas) {
				jdbc.update("update marketplace_listings set status = 'REMOVED' where seller_id = ?", conta);
				jdbc.update("update theme_gallery set status = 'HIDDEN' where author_id = ?", conta);
			}
		});
		if (cotacaoCenario != null) {
			exigirForaDeTransacao();
			assertThat(jdbc.queryForList("select id from coin_rate_history where id = ?", cotacaoCenario)).isEmpty();
		}
		for (String conta : contas) {
			assertThat(jdbc.queryForList("select id from marketplace_listings where seller_id = ? and status = 'ACTIVE'",
				conta)).isEmpty();
			assertThat(jdbc.queryForList("select id from theme_gallery where author_id = ? and status = 'PUBLISHED'",
				conta)).isEmpty();
		}
	}

	@ParameterizedTest(name = "cotação {0}: presença {1} mf, saldo retido {3} mf")
	@CsvSource({
		"10000, 5000, 105000, 55000, 150000, 5000",
		"9900, 5051, 105051, 55051, 150051, 5051"
	})
	void listarComprarConfirmarEReembolsar(long cotacao, long presenca, long inicial,
			long aposCompra, long aposRepasse, long segundaRetencao) throws Exception {
		var cotacaoPublicada = rates.publish(cotacao, marcador);
		cotacaoCenario = cotacaoPublicada.id();
		assertThat(rates.latest().id()).isEqualTo(cotacaoPublicada.id());
		var autor = autenticar("autor");
		var comprador = autenticar("comprador");
		abrirCarteira(autor, cotacao, presenca, inicial);
		abrirCarteira(comprador, cotacao, presenca, inicial);

		String anuncio = publicarTema(autor, "venda", "#fdf6e3", "#586e75");
		mockMvc.perform(post("/marketplace/" + anuncio + "/buy")
				.header("X-Session-Token", autor.token()).header("Idempotency-Key", marcador + "-autocompra"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("OWN_ITEM"));
		esperarSaldo(autor, inicial);
		assertThat(jdbc.queryForList("select id from marketplace_purchases where listing_id = ?", anuncio)).isEmpty();

		JsonNode compra = comprar(comprador, anuncio, "compra");
		String compraId = compra.get("purchaseId").asText();
		esperarCompra(compraId, "HELD");
		esperarSaldo(comprador, aposCompra);
		esperarSaldo(autor, inicial);
		esperarLancamentos(comprador.userId(), "ESCROW_OUT", -50_000);
		String retencao = referenciaRetencao(comprador, anuncio);
		esperarLancamentoRef(CoinService.ESCROW_ACCOUNT, "ESCROW_HOLD", retencao, 50_000);

		JsonNode replay = comprar(comprador, anuncio, "compra");
		assertThat(replay).isEqualTo(compra);
		esperarCompra(compraId, "HELD");
		esperarSaldo(comprador, aposCompra);
		esperarLancamentos(comprador.userId(), "ESCROW_OUT", -50_000);
		esperarLancamentoRef(CoinService.ESCROW_ACCOUNT, "ESCROW_HOLD", retencao, 50_000);

		decidir(comprador, compraId, "confirm", "RELEASED");
		esperarSaldo(autor, aposRepasse);
		esperarSaldo(comprador, aposCompra);
		esperarLancamentos(autor.userId(), "ESCROW_IN", 45_000);
		esperarLancamentoRef(autor.userId(), "ESCROW_IN", "mcmp:" + compraId, 45_000);
		esperarLancamentoRef(CoinService.TREASURY_ACCOUNT, "FEE_IN", "mcmp:" + compraId, 5_000);
		esperarLancamentoRef(CoinService.ESCROW_ACCOUNT, "ESCROW_RELEASE", "mcmp:" + compraId, -50_000);
		decidir(comprador, compraId, "confirm", "RELEASED");
		esperarSaldo(autor, aposRepasse);
		esperarLancamentoRef(CoinService.TREASURY_ACCOUNT, "FEE_IN", "mcmp:" + compraId, 5_000);

		String segundoAnuncio = publicarTema(autor, "reembolso", "#eee8d5", "#073642");
		JsonNode segundaCompra = comprar(comprador, segundoAnuncio, "segunda-compra");
		String segundaCompraId = segundaCompra.get("purchaseId").asText();
		esperarCompra(segundaCompraId, "HELD");
		esperarSaldo(comprador, segundaRetencao);
		esperarLancamentos(comprador.userId(), "ESCROW_OUT", -50_000, -50_000);
		String segundaRetencaoRef = referenciaRetencao(comprador, segundoAnuncio);
		esperarLancamentoRef(CoinService.ESCROW_ACCOUNT, "ESCROW_HOLD", segundaRetencaoRef, 50_000);
		decidir(comprador, segundaCompraId, "refund", "REFUNDED");
		esperarSaldo(comprador, aposCompra);
		esperarSaldo(autor, aposRepasse);
		esperarLancamentos(comprador.userId(), "REFUND_IN", 50_000);
		esperarLancamentoRef(comprador.userId(), "REFUND_IN", "mcmp:" + segundaCompraId + ":refund", 50_000);
		esperarLancamentoRef(CoinService.TREASURY_ACCOUNT, "FEE_IN", "mcmp:" + compraId, 5_000);
		esperarLancamentoRef(CoinService.TREASURY_ACCOUNT, "FEE_IN", "mcmp:" + segundaCompraId);
		decidir(comprador, segundaCompraId, "refund", "REFUNDED");
		assertThat(comprar(comprador, segundoAnuncio, "segunda-compra")).isEqualTo(segundaCompra);
		esperarCompra(segundaCompraId, "REFUNDED");
		esperarSaldo(comprador, aposCompra);
		esperarSaldo(autor, aposRepasse);
		esperarLancamentos(comprador.userId(), "REFUND_IN", 50_000);
		esperarLancamentos(autor.userId(), "ESCROW_IN", 45_000);
		esperarLancamentos(autor.userId(), "REWARD", presenca);
		esperarLancamentos(comprador.userId(), "REWARD", presenca);
		esperarLancamentoRef(CoinService.ESCROW_ACCOUNT, "ESCROW_HOLD", retencao, 50_000);
		esperarLancamentoRef(CoinService.ESCROW_ACCOUNT, "ESCROW_HOLD", segundaRetencaoRef, 50_000);
		esperarLancamentoRef(CoinService.ESCROW_ACCOUNT, "ESCROW_RELEASE", "mcmp:" + compraId, -50_000);
		esperarLancamentoRef(CoinService.ESCROW_ACCOUNT, "ESCROW_RELEASE", "mcmp:" + segundaCompraId, -50_000);
		esperarLancamentoRef(CoinService.TREASURY_ACCOUNT, "FEE_IN", "mcmp:" + compraId, 5_000);
		esperarLancamentoRef(CoinService.TREASURY_ACCOUNT, "FEE_IN", "mcmp:" + segundaCompraId);
		assertThat(jdbc.queryForObject("select count(*) from marketplace_purchases where buyer_id = ?",
			Integer.class, comprador.userId())).isEqualTo(2);
		assertThat(rates.latest().id()).isEqualTo(cotacaoPublicada.id());
	}

	private void abrirCarteira(TestAuthHelper.AuthSession conta, long cotacao, long presenca, long inicial) throws Exception {
		esperarSaldo(conta, inicial);
		esperarSaldo(conta, inicial);
		mockMvc.perform(get("/carteira").header("X-Session-Token", conta.token()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.cotacaoBrlMicros").value(cotacao))
			.andExpect(jsonPath("$.escala").value(1_000))
			.andExpect(jsonPath("$.streak.currentDays").value(1))
			.andExpect(jsonPath("$.streak.todayDone").value(true));
		esperarLancamentos(conta.userId(), "AIRDROP", 100_000);
		esperarLancamentos(conta.userId(), "REWARD", presenca);
		esperarLancamentoRef(CoinService.TREASURY_ACCOUNT, "GENESIS_TREASURY", "airdrop-tesouraria:" + conta.userId(), -100_000);
		esperarLancamentoRef(CoinService.TREASURY_ACCOUNT, "GENESIS_TREASURY",
			"presenca-tesouraria:presenca:" + conta.userId() + ":" + UserStreak.today(clock), -presenca);
	}

	private JsonNode comprar(TestAuthHelper.AuthSession comprador, String anuncio, String chave) throws Exception {
		return objectMapper.readTree(mockMvc.perform(post("/marketplace/" + anuncio + "/buy")
				.header("Idempotency-Key", marcador + "-" + chave).header("X-Session-Token", comprador.token()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.state").value("HELD"))
			.andExpect(jsonPath("$.price").value(50_000)).andReturn().getResponse().getContentAsString());
	}

	private void decidir(TestAuthHelper.AuthSession comprador, String compra, String acao, String estado) throws Exception {
		mockMvc.perform(post("/marketplace/purchases/" + compra + "/" + acao)
				.header("Idempotency-Key", marcador + "-" + acao + "-" + compra)
				.header("X-Session-Token", comprador.token()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.state").value(estado));
		esperarCompra(compra, estado);
	}

	private void esperarCompra(String compra, String estado) {
		exigirForaDeTransacao();
		assertThat(jdbc.queryForObject("select state from marketplace_purchases where id = ?", String.class, compra))
			.isEqualTo(estado);
		assertThat(jdbc.queryForObject("select price from marketplace_purchases where id = ?", Long.class, compra))
			.isEqualTo(50_000);
	}

	private String publicarTema(TestAuthHelper.AuthSession autor, String chave, String fundo, String texto) throws Exception {
		String tema = objectMapper.readTree(mockMvc.perform(post("/temas/galeria")
				.header("X-Session-Token", autor.token()).header("Idempotency-Key", marcador + "-tema-" + chave)
				.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of(
					"name", "Tema " + chave, "description", "tema do marketplace", "modeDefault", "light",
					"packageJson", pkg(fundo, texto)))))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asText();
		exigirForaDeTransacao();
		assertThat(jdbc.queryForObject("select author_id from theme_gallery where id = ?", String.class, tema))
			.isEqualTo(autor.userId());
		String anuncio = objectMapper.readTree(mockMvc.perform(post("/marketplace")
				.header("X-Session-Token", autor.token()).header("Idempotency-Key", marcador + "-anuncio-" + chave)
				.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of(
					"itemId", tema, "title", "Tema " + chave, "price", 50_000))))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString()).get("id").asText();
		exigirForaDeTransacao();
		assertThat(jdbc.queryForObject("select price from marketplace_listings where id = ?", Long.class, anuncio))
			.isEqualTo(50_000);
		return anuncio;
	}

	private void esperarSaldo(TestAuthHelper.AuthSession conta, long esperado) throws Exception {
		mockMvc.perform(get("/carteira").header("X-Session-Token", conta.token()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.balance").value(esperado));
		exigirForaDeTransacao();
		assertThat(jdbc.queryForObject("select balance from coin_accounts where account_id = ?", Long.class, conta.userId()))
			.isEqualTo(esperado);
		assertThat(jdbc.queryForObject("select sum(amount) from coin_entries where account_id = ?", Long.class, conta.userId()))
			.isEqualTo(esperado);
	}

	private void esperarLancamentos(String conta, String tipo, long... esperados) {
		exigirForaDeTransacao();
		assertThat(contas).as("somente contas exclusivas do cenário").contains(conta);
		assertThat(jdbc.queryForList("select amount from coin_entries where account_id = ? and kind = ?", Long.class, conta, tipo))
			.as("%s da conta %s", tipo, conta)
			.containsExactlyInAnyOrder(Arrays.stream(esperados).boxed().toArray(Long[]::new));
	}

	private void esperarLancamentoRef(String conta, String tipo, String ref, long... esperados) {
		exigirForaDeTransacao();
		assertThat(jdbc.queryForList("select amount from coin_entries where account_id = ? and kind = ? and ref_id = ?",
			Long.class, conta, tipo, ref)).as("%s da referência %s na conta %s", tipo, ref, conta)
			.containsExactlyInAnyOrder(Arrays.stream(esperados).boxed().toArray(Long[]::new));
	}

	private String referenciaRetencao(TestAuthHelper.AuthSession comprador, String anuncio) {
		exigirForaDeTransacao();
		// A retenção usa ref aleatória derivada do anúncio; a decisão usa mcmp:<purchaseId>.
		List<String> refs = jdbc.queryForList(
			"select ref_id from coin_entries where account_id = ? and kind = 'ESCROW_OUT' and ref_id like ?",
			String.class, comprador.userId(), "mkt_" + anuncio.substring(4) + ":%");
		assertThat(refs).hasSize(1);
		return refs.getFirst();
	}

	private TestAuthHelper.AuthSession autenticar(String papel) throws Exception {
		var sessao = new TestAuthHelper(mockMvc, objectMapper).authenticate(marcador + "_" + papel);
		contas.add(sessao.userId());
		return sessao;
	}

	private void exigirForaDeTransacao() {
		assertThat(TransactionSynchronizationManager.isActualTransactionActive())
			.as("HTTP deve executar sem transação de teste envolvente").isFalse();
	}

	private String pkg(String bg, String strong) {
		return "{\"palette\":{\"background\":\"" + bg + "\",\"surface\":\"" + bg
			+ "\",\"muted\":\"" + bg + "\",\"border\":\"" + bg
			+ "\",\"primary\":\"#7c3aed\",\"onPrimary\":\"#ffffff\",\"soft\":\"#ede8f5\",\"text\":{\"strong\":\""
			+ strong + "\",\"subtle\":\"#555555\",\"date\":\"#777777\"},\"radius\":\"8px\"}}";
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class ConfiguracaoDeterministica {
		@Bean
		@Primary
		RelogioCenario relogioMarketplace() {
			return new RelogioCenario(new AtomicLong(Instant.parse("2035-06-12T15:00:00Z").toEpochMilli()), ZoneOffset.UTC);
		}
	}

	/** Avança um milissegundo por leitura, sem empatar os lançamentos do ledger ou cruzar meia-noite. */
	static final class RelogioCenario extends Clock {
		private final AtomicLong millis;
		private final ZoneId zone;

		RelogioCenario(AtomicLong millis, ZoneId zone) {
			this.millis = millis;
			this.zone = zone;
		}

		void iniciar(Instant inicio) {
			millis.set(inicio.toEpochMilli());
		}

		@Override public ZoneId getZone() { return zone; }
		@Override public Clock withZone(ZoneId zona) { return new RelogioCenario(millis, zona); }
		@Override public Instant instant() { return Instant.ofEpochMilli(millis.getAndIncrement()); }
	}
}
