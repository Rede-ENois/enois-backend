package com.socialcoisa.rede.mentoring.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.coin.application.CoinService;
import com.socialcoisa.rede.mentoring.application.MentorService;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * Mentoria paga em faíscas: a inscrição retém o valor no escrow, a conclusão paga o mentor menos a
 * taxa da plataforma e o cancelamento devolve integral para quem pagou.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MentorSparksPaymentTests {

	private static final long PRICE = CoinService.FAIS(200);

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private MentorService mentorService;

	@Autowired
	private CoinService coins;

	@Test
	void paidBookingGoesToEscrowAndCompletionPaysTheMentorMinusTheFee() throws Exception {
		var auth = new TestAuthHelper(mockMvc, objectMapper);
		var owner = auth.authenticate("mentor_sparks_owner");
		var mentee = auth.authenticate("mentor_sparks_mentee");
		String mentorId = createMentor(owner, PRICE);
		String slotId = createSlot(owner, mentorId, Instant.now().plusSeconds(86_400));

		fund(mentee, CoinService.FAIS(500), "test-mentoria-fundo-owner");
		long menteeBefore = coins.balance(mentee.userId());
		long ownerBefore = coins.balance(owner.userId());

		String sessionId = book(mentee, mentorId, slotId);

		// No ato da inscrição as faíscas saem do mentorado e ficam retidas; o mentor ainda não vê nada.
		assertThat(coins.balance(mentee.userId())).isEqualTo(menteeBefore - PRICE);
		assertThat(coins.balance(owner.userId())).isEqualTo(ownerBefore);

		mockMvc.perform(get("/mentors/" + mentorId + "/sessions").header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.id == '" + sessionId + "')].priceSparks").value((int) PRICE));

		mockMvc.perform(get("/mentors/sessions/mine").header("X-Session-Token", mentee.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].mentorUsername").value("mentor-sparks-owner"))
			.andExpect(jsonPath("$[0].priceSparks").value((int) PRICE));

		complete(owner, mentorId, sessionId, "sparks-complete-1")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("COMPLETED"));

		// O mentor recebe o preço menos os 10% da plataforma.
		long net = PRICE - PRICE / 10;
		assertThat(coins.balance(owner.userId())).isEqualTo(ownerBefore + net);
		assertThat(coins.balance(mentee.userId())).isEqualTo(menteeBefore - PRICE);

		// Confirmar de novo não paga duas vezes.
		complete(owner, mentorId, sessionId, "sparks-complete-2")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SESSION_NOT_SCHEDULED"));
		assertThat(coins.balance(owner.userId())).isEqualTo(ownerBefore + net);
	}

	@Test
	void bookingWithoutSparksFailsAndLeavesTheSlotFree() throws Exception {
		var auth = new TestAuthHelper(mockMvc, objectMapper);
		var owner = auth.authenticate("mentor_no_funds_owner");
		var mentee = auth.authenticate("mentor_no_funds_mentee");
		String mentorId = createMentor(owner, CoinService.FAIS(200));
		String slotId = createSlot(owner, mentorId, Instant.now().plusSeconds(90_000));

		// O airdrop cobre 100 faíscas: a sessão de 200 não cabe no saldo de quem nunca recebeu mais.
		mockMvc.perform(post("/mentors/" + mentorId + "/sessions")
				.header("X-Session-Token", mentee.token())
				.header("Idempotency-Key", "sparks-book-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("slotId", slotId, "message", "oi"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INSUFFICIENT_SPARKS"));

		// Nada de sessão órfã e o horário continua livre para outra pessoa.
		mockMvc.perform(get("/mentors/" + mentorId + "/slots"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.id == '" + slotId + "')].available").value(true));
		mockMvc.perform(get("/mentors/sessions/mine").header("X-Session-Token", mentee.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$").isEmpty());
	}

	@Test
	void cancellingBeforeTheSessionRefundsAndFreesTheSlot() throws Exception {
		var auth = new TestAuthHelper(mockMvc, objectMapper);
		var owner = auth.authenticate("mentor_cancel_owner");
		var mentee = auth.authenticate("mentor_cancel_mentee");
		String mentorId = createMentor(owner, CoinService.FAIS(80));
		String slotId = createSlot(owner, mentorId, Instant.now().plusSeconds(86_400));

		fund(mentee, CoinService.FAIS(200), "test-mentoria-fundo-cancel");
		long menteeBefore = coins.balance(mentee.userId());
		String sessionId = book(mentee, mentorId, slotId);
		assertThat(coins.balance(mentee.userId())).isEqualTo(menteeBefore - CoinService.FAIS(80));

		mockMvc.perform(post("/mentors/" + mentorId + "/sessions/" + sessionId + "/cancel")
				.header("X-Session-Token", mentee.token())
				.header("Idempotency-Key", "sparks-cancel-" + UUID.randomUUID()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CANCELLED"));

		// Reembolso INTEGRAL: o cancelamento não cobra nada de ninguém.
		assertThat(coins.balance(mentee.userId())).isEqualTo(menteeBefore);
		mockMvc.perform(get("/mentors/" + mentorId + "/slots"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.id == '" + slotId + "')].available").value(true));
		// Sessão encerrada não paga mais ninguém.
		complete(owner, mentorId, sessionId, "sparks-complete-cancel")
			.andExpect(status().isConflict());
	}

	@Test
	void onlyParticipantsActAndTheMenteeCannotCancelAfterTheStart() throws Exception {
		var auth = new TestAuthHelper(mockMvc, objectMapper);
		var owner = auth.authenticate("mentor_rules_owner");
		var mentee = auth.authenticate("mentor_rules_mentee");
		var stranger = auth.authenticate("mentor_rules_stranger");
		String mentorId = createMentor(owner, CoinService.FAIS(60));
		// Horário que já começou: quem manda no cancelamento passa a ser quem atende.
		String slotId = createSlot(owner, mentorId, Instant.now().minusSeconds(7_200));

		fund(mentee, CoinService.FAIS(200), "test-mentoria-fundo-rules");
		String sessionId = book(mentee, mentorId, slotId);

		complete(stranger, mentorId, sessionId, "sparks-stranger")
			.andExpect(status().isForbidden());
		complete(mentee, mentorId, sessionId, "sparks-mentee-complete")
			.andExpect(status().isForbidden());

		mockMvc.perform(post("/mentors/" + mentorId + "/sessions/" + sessionId + "/cancel")
				.header("X-Session-Token", mentee.token())
				.header("Idempotency-Key", "sparks-late-cancel-" + UUID.randomUUID()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SESSION_ALREADY_STARTED"));

		// O mentor cancela e devolve o valor: ele é quem decide depois do horário.
		long menteeBefore = coins.balance(mentee.userId());
		mockMvc.perform(post("/mentors/" + mentorId + "/sessions/" + sessionId + "/cancel")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "sparks-owner-cancel-" + UUID.randomUUID()))
			.andExpect(status().isOk());
		assertThat(coins.balance(mentee.userId())).isEqualTo(menteeBefore + CoinService.FAIS(60));
	}

	@Test
	void sparksPriceRespectsFloorCeilingAndCanBeRemoved() throws Exception {
		var owner = new TestAuthHelper(mockMvc, objectMapper).authenticate("mentor_price_owner");

		createMentorRequest(owner, CoinService.FAIS(10))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("SPARKS_PRICE_TOO_LOW"));
		createMentorRequest(owner, CoinService.FAIS(20_000))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("SPARKS_PRICE_TOO_HIGH"));

		MvcResult created = createMentorRequest(owner, CoinService.FAIS(150))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.sparksPrice").value((int) CoinService.FAIS(150)))
			.andReturn();
		String mentorId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

		// Voltar atrás é permitido: sem preço, a mentoria volta a ser de graça.
		mockMvc.perform(patch("/mentors/" + mentorId)
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "sparks-clear-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("expertise", List.of("Java"), "price", "Gratuito"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.sparksPrice").doesNotExist());
	}

	@Test
	void theWorkerReleasesTheEscrowAfterTheGraceWindow() throws Exception {
		var auth = new TestAuthHelper(mockMvc, objectMapper);
		var owner = auth.authenticate("mentor_settle_owner");
		var mentee = auth.authenticate("mentor_settle_mentee");
		String mentorId = createMentor(owner, CoinService.FAIS(100));
		// Sessão de oito dias atrás: passada a janela de contestação, o dinheiro não pode ficar preso.
		String slotId = createSlot(owner, mentorId, Instant.now().minusSeconds(8 * 86_400L));

		fund(mentee, CoinService.FAIS(200), "test-mentoria-fundo-settle");
		long ownerBefore = coins.balance(owner.userId());
		long menteeBefore = coins.balance(mentee.userId());
		book(mentee, mentorId, slotId);

		mentorService.settleExpiredSessions();

		assertThat(coins.balance(owner.userId())).isEqualTo(ownerBefore + CoinService.FAIS(90));
		assertThat(coins.balance(mentee.userId())).isEqualTo(menteeBefore - CoinService.FAIS(100));
		mockMvc.perform(get("/mentors/" + mentorId + "/sessions").header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].status").value("COMPLETED"));
	}

	private void fund(TestAuthHelper.AuthSession user, long amount, String prefix) {
		coins.reward(user.userId(), amount, prefix + "-" + user.userId(), "Saldo de teste");
		// O airdrop entra na primeira passagem pela moeda: aqui ele acontece ANTES de medir, para o
		// saldo do teste ser só o que o teste movimentou.
		coins.ensureGenesisFor(user.userId());
	}

	private org.springframework.test.web.servlet.ResultActions createMentorRequest(TestAuthHelper.AuthSession owner,
			Long sparksPrice) throws Exception {
		Map<String, Object> body = new java.util.HashMap<>();
		body.put("expertise", List.of("Java"));
		body.put("price", "Gratuito");
		body.put("sparksPrice", sparksPrice);
		return mockMvc.perform(post("/mentors")
			.header("X-Session-Token", owner.token())
			.header("Idempotency-Key", "sparks-mentor-" + UUID.randomUUID())
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(body)));
	}

	private String createMentor(TestAuthHelper.AuthSession owner, long sparksPrice) throws Exception {
		MvcResult created = createMentorRequest(owner, sparksPrice).andExpect(status().isCreated()).andReturn();
		return objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
	}

	private String createSlot(TestAuthHelper.AuthSession owner, String mentorId, Instant startsAt) throws Exception {
		MvcResult slot = mockMvc.perform(post("/mentors/" + mentorId + "/slots")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "sparks-slot-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("startsAt", startsAt.toString(),
					"endsAt", startsAt.plusSeconds(3_600).toString()))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(slot.getResponse().getContentAsString()).get("id").asText();
	}

	private String book(TestAuthHelper.AuthSession mentee, String mentorId, String slotId) throws Exception {
		MvcResult result = mockMvc.perform(post("/mentors/" + mentorId + "/sessions")
				.header("X-Session-Token", mentee.token())
				.header("Idempotency-Key", "sparks-book-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("slotId", slotId, "message", "quero aprender"))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private org.springframework.test.web.servlet.ResultActions complete(TestAuthHelper.AuthSession user,
			String mentorId, String sessionId, String key) throws Exception {
		return mockMvc.perform(post("/mentors/" + mentorId + "/sessions/" + sessionId + "/complete")
			.header("X-Session-Token", user.token())
			.header("Idempotency-Key", key));
	}
}