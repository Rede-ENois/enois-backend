package com.socialcoisa.rede.coin.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.socialcoisa.rede.coin.application.CoinService;
import com.socialcoisa.rede.coin.application.port.out.CoinLedgerRepository;
import com.socialcoisa.rede.coin.domain.CoinEntry;
import com.socialcoisa.rede.shared.web.TestAuthHelper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CoinExtractTests {

	@Autowired private MockMvc mockMvc;
	@Autowired private ObjectMapper mapper;
	@Autowired private CoinLedgerRepository ledger;
	@Autowired private CoinService coins;
	private TestAuthHelper.AuthSession owner;

	@BeforeAll
	void prepareLedger() throws Exception {
		owner = auth("wallet-extract-owner");
		coins.wallet(owner.userId(), "wallet-extract-owner");
		add("before", "2020-01-15T02:59:59Z");
		add("start", "2020-01-15T03:00:00Z");
		add("tie-a", "2020-01-15T15:00:00Z");
		add("tie-b", "2020-01-15T15:00:00Z");
		add("end", "2020-01-16T02:59:59.999Z");
		add("after", "2020-01-16T03:00:00Z");
	}

	@Test
	void consecutivePagesNeverSkipOrRepeatAndFullLastPageHasNoNext() throws Exception {
		var refs = new ArrayList<String>();
		for (int page = 0; page < 3; page++) {
			JsonNode result = read(extract(owner).param("page", String.valueOf(page)).param("size", "2")
					.param("to", "2020-01-16"));
			assertThat(result.get("page").asInt()).isEqualTo(page);
			assertThat(result.get("size").asInt()).isEqualTo(2);
			assertThat(result.get("entries").size()).isEqualTo(2);
			assertThat(result.get("hasNext").asBoolean()).isEqualTo(page < 2);
			result.get("entries").forEach(entry -> refs.add(entry.get("refId").asText()));
		}
		assertThat(refs).containsExactly("after", "end", "tie-b", "tie-a", "start", "before");
	}

	@Test
	void inclusiveDatesFollowTheSameSaoPauloDayDisplayedInTheApp() throws Exception {
		assertThat(refs(read(extract(owner).param("from", "2020-01-15").param("to", "2020-01-15"))))
				.containsExactly("end", "tie-b", "tie-a", "start");
	}

	@Test
	void initialDateAloneIsApplied() throws Exception {
		assertThat(refs(read(extract(owner).param("from", "2020-01-16"))))
				.contains("after").doesNotContain("before", "start", "tie-a", "tie-b", "end");
	}

	@Test
	void finalDateAloneIsApplied() throws Exception {
		assertThat(refs(read(extract(owner).param("to", "2020-01-14")))).containsExactly("before");
	}

	@Test
	void emptyAndPastTheLastPageReturnNoNext() throws Exception {
		mockMvc.perform(extract(owner).param("to", "2020-01-16").param("size", "2").param("page", "3"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.entries").isEmpty())
				.andExpect(jsonPath("$.hasNext").value(false));
		mockMvc.perform(extract(owner).param("from", "2019-01-01").param("to", "2019-01-31"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.entries").isEmpty())
				.andExpect(jsonPath("$.hasNext").value(false));
	}

	@Test
	void extractDoesNotCreateAccountOrCreditGenesisAndNeverReadsAnotherAccount() throws Exception {
		var reader = auth("wallet-extract-reader");
		assertThat(ledger.findAccount(reader.userId())).isEmpty();
		mockMvc.perform(extract(reader)).andExpect(status().isOk())
				.andExpect(jsonPath("$.entries").isEmpty());
		assertThat(ledger.findAccount(reader.userId())).isEmpty();
	}

	@Test
	void readingTheExtractPreservesTheExistingWalletContractAndBalance() throws Exception {
		JsonNode before = read(get("/carteira").header("X-Session-Token", owner.token()));
		read(extract(owner));
		JsonNode after = read(get("/carteira").header("X-Session-Token", owner.token()));
		for (String field : List.of("balance", "chave", "escala", "cotacaoBrlCents")) {
			assertThat(after.get(field)).isEqualTo(before.get(field));
		}
		assertThat(after.get("entries").isArray()).isTrue();
	}

	@Test
	void transferMessageAndReferenceRemainAvailableForContestation() throws Exception {
		var sender = auth("wallet-extract-sender");
		var receiver = auth("wallet-extract-receiver");
		coins.wallet(sender.userId(), "wallet-extract-sender");
		coins.wallet(receiver.userId(), "wallet-extract-receiver");
		var transfer = coins.transfer(sender.userId(), "wallet-extract-sender", 1_000,
				"Transferência de demonstração", receiver.userId(), "wallet-extract-receiver");
		JsonNode entries = read(extract(sender)).get("entries");
		JsonNode sent = null;
		for (JsonNode entry : entries) if (entry.get("kind").asText().equals("TRANSFER_OUT")) sent = entry;
		assertThat(sent).isNotNull();
		assertThat(sent.get("message").asText()).isEqualTo("Transferência de demonstração");
		assertThat(sent.get("refId").asText()).isEqualTo(transfer.refId());
	}

	@ParameterizedTest
	@ValueSource(strings = {"invalid", "2020-02-30", "2020-13-01", "0000-01-01", "2020-1-1", ""})
	void invalidDateIsAnExplicitError(String date) throws Exception {
		mockMvc.perform(extract(owner).param("from", date)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_EXTRACT_DATE"));
	}

	@Test
	void invertedIntervalIsAnExplicitError() throws Exception {
		mockMvc.perform(extract(owner).param("from", "2020-01-16").param("to", "2020-01-15"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_EXTRACT_DATE"));
	}

	@ParameterizedTest
	@CsvSource({"-1,20", "10001,20", "0,0", "0,-1", "0,101"})
	void invalidPageIsRejectedInsteadOfChangingTheRequestedPage(int page, int size) throws Exception {
		mockMvc.perform(extract(owner).param("page", String.valueOf(page)).param("size", String.valueOf(size)))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_EXTRACT_PAGE"));
	}

	@Test
	void authenticationIsRequired() throws Exception {
		mockMvc.perform(get("/carteira/extrato")).andExpect(status().isUnauthorized());
	}

	private TestAuthHelper.AuthSession auth(String name) throws Exception {
		return new TestAuthHelper(mockMvc, mapper).authenticate(name);
	}

	private MockHttpServletRequestBuilder extract(TestAuthHelper.AuthSession user) {
		return get("/carteira/extrato").header("X-Session-Token", user.token());
	}

	private JsonNode read(MockHttpServletRequestBuilder request) throws Exception {
		return mapper.readTree(mockMvc.perform(request).andExpect(status().isOk()).andReturn()
				.getResponse().getContentAsString());
	}

	private List<String> refs(JsonNode page) {
		var refs = new ArrayList<String>();
		page.get("entries").forEach(entry -> refs.add(entry.get("refId").asText()));
		return refs;
	}

	private void add(String ref, String createdAt) {
		ledger.insertEntry(CoinEntry.create("extract-fixture-" + ref, owner.userId(), 0,
				CoinEntry.Kind.ADJUSTMENT, ref, null, "extract-fixture-" + ref,
				"0".repeat(64), "0".repeat(64), Instant.parse(createdAt)));
	}
}
