package com.socialcoisa.rede.mentoring.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.HashMap;
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
 * Sessão cujo horário terminou não fica "agendada": a leitura da agenda e o worker a concluem, e
 * ela sai da lista de próximas dos dois lados. O dinheiro do escrow segue a regra de sempre — só
 * é resolvido na confirmação do mentor, no cancelamento ou depois da janela de contestação.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MentorSessionCompletionTests {

	private static final long PRICE = CoinService.FAIS(100);

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private MentorService mentorService;

	@Autowired
	private CoinService coins;

	@Test
	void aSessionWhoseTimeEndedIsCompletedAndLeavesTheUpcomingList() throws Exception {
		var auth = new TestAuthHelper(mockMvc, objectMapper);
		var owner = auth.authenticate("mentor_clock_owner");
		var mentee = auth.authenticate("mentor_clock_mentee");
		String mentorId = createMentor(owner, null);

		// Horário de uma hora que já terminou (começou duas horas atrás) e um futuro de controle.
		String endedSlot = createSlot(owner, mentorId, Instant.now().minusSeconds(7_200));
		String futureSlot = createSlot(owner, mentorId, Instant.now().plusSeconds(86_400));
		String ended = book(mentee, mentorId, endedSlot);
		String upcoming = book(mentee, mentorId, futureSlot);

		// A agenda do mentor: a que terminou vira concluída; a futura continua agendada.
		mockMvc.perform(get("/mentors/" + mentorId + "/sessions").header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.id == '" + ended + "')].status").value("COMPLETED"))
			.andExpect(jsonPath("$[?(@.id == '" + ended + "')].startsAt").exists())
			.andExpect(jsonPath("$[?(@.id == '" + upcoming + "')].status").value("SCHEDULED"));

		// O lado de quem agendou vê o mesmo (é o "próximas × histórico" da tela).
		mockMvc.perform(get("/mentors/sessions/mine").header("X-Session-Token", mentee.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.id == '" + ended + "')].status").value("COMPLETED"))
			.andExpect(jsonPath("$[?(@.id == '" + upcoming + "')].status").value("SCHEDULED"));

		// Concluída de verdade também para o catálogo: o contador de sessões do mentor subiu.
		mockMvc.perform(get("/mentors/" + mentorId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.sessions").value(1));
	}

	@Test
	void theWorkerCompletesFinishedSessionsWithoutMovingMoney() throws Exception {
		var auth = new TestAuthHelper(mockMvc, objectMapper);
		var owner = auth.authenticate("mentor_clock_worker_owner");
		var mentee = auth.authenticate("mentor_clock_worker_mentee");
		String mentorId = createMentor(owner, PRICE);
		// Terminou um minuto atrás: passou do horário, mas ainda dentro da janela de contestação.
		String slotId = createSlot(owner, mentorId, Instant.now().minusSeconds(3_660));

		fund(mentee, CoinService.FAIS(200), "test-mentoria-relogio-worker");
		String sessionId = book(mentee, mentorId, slotId);
		long ownerBefore = coins.balance(owner.userId());
		long menteeBefore = coins.balance(mentee.userId());

		// Ninguém abriu a agenda: o worker sozinho conclui a sessão vencida.
		mentorService.completeFinishedSessions();

		mockMvc.perform(get("/mentors/" + mentorId + "/sessions").header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.id == '" + sessionId + "')].status").value("COMPLETED"));

		// Concluir pelo relógio não paga ninguém: o escrow espera a janela de contestação.
		assertThat(coins.balance(owner.userId())).isEqualTo(ownerBefore);
		assertThat(coins.balance(mentee.userId())).isEqualTo(menteeBefore);
	}

	@Test
	void aPaidSessionCompletedByTheClockStillSettlesOnceAfterTheGraceWindow() throws Exception {
		var auth = new TestAuthHelper(mockMvc, objectMapper);
		var owner = auth.authenticate("mentor_clock_settle_owner");
		var mentee = auth.authenticate("mentor_clock_settle_mentee");
		String mentorId = createMentor(owner, PRICE);
		// Oito dias atrás: já passou a janela de contestação de sete dias contada do fim do horário.
		String slotId = createSlot(owner, mentorId, Instant.now().minusSeconds(8 * 86_400L));

		fund(mentee, CoinService.FAIS(200), "test-mentoria-relogio-settle");
		long ownerBefore = coins.balance(owner.userId());
		long menteeBefore = coins.balance(mentee.userId());
		String sessionId = book(mentee, mentorId, slotId);

		// O relógio conclui primeiro (sem pagar) e o worker de liquidação resolve o valor depois.
		mentorService.completeFinishedSessions();
		assertThat(coins.balance(owner.userId())).isEqualTo(ownerBefore);

		mentorService.settleExpiredSessions();
		assertThat(coins.balance(owner.userId())).isEqualTo(ownerBefore + CoinService.FAIS(90));
		assertThat(coins.balance(mentee.userId())).isEqualTo(menteeBefore - PRICE);

		// Uma segunda rodada não paga o mesmo valor de novo.
		mentorService.settleExpiredSessions();
		assertThat(coins.balance(owner.userId())).isEqualTo(ownerBefore + CoinService.FAIS(90));

		mockMvc.perform(get("/mentors/" + mentorId + "/sessions").header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.id == '" + sessionId + "')].status").value("COMPLETED"));
	}

	@Test
	void aSessionCompletedByTheMentorIsNotSettledAgainByTheWorker() throws Exception {
		var auth = new TestAuthHelper(mockMvc, objectMapper);
		var owner = auth.authenticate("mentor_clock_manual_owner");
		var mentee = auth.authenticate("mentor_clock_manual_mentee");
		String mentorId = createMentor(owner, PRICE);
		String slotId = createSlot(owner, mentorId, Instant.now().minusSeconds(8 * 86_400L));

		fund(mentee, CoinService.FAIS(200), "test-mentoria-relogio-manual");
		long ownerBefore = coins.balance(owner.userId());
		String sessionId = book(mentee, mentorId, slotId);

		mockMvc.perform(post("/mentors/" + mentorId + "/sessions/" + sessionId + "/complete")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "clock-manual-complete-" + UUID.randomUUID()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("COMPLETED"));
		assertThat(coins.balance(owner.userId())).isEqualTo(ownerBefore + CoinService.FAIS(90));

		// O valor já saiu do escrow na confirmação: a liberação automática não paga de novo.
		mentorService.settleExpiredSessions();
		assertThat(coins.balance(owner.userId())).isEqualTo(ownerBefore + CoinService.FAIS(90));
	}

	private void fund(TestAuthHelper.AuthSession user, long amount, String prefix) {
		coins.reward(user.userId(), amount, prefix + "-" + user.userId(), "Saldo de teste");
		// O airdrop entra na primeira passagem pela moeda: fazer antes de medir deixa no saldo
		// apenas o que o teste movimentou.
		coins.ensureGenesisFor(user.userId());
	}

	private String createMentor(TestAuthHelper.AuthSession owner, Long sparksPrice) throws Exception {
		Map<String, Object> body = new HashMap<>();
		body.put("expertise", List.of("Java"));
		body.put("price", "Gratuito");
		body.put("sparksPrice", sparksPrice);
		MvcResult created = mockMvc.perform(post("/mentors")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "clock-mentor-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(body)))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
	}

	private String createSlot(TestAuthHelper.AuthSession owner, String mentorId, Instant startsAt) throws Exception {
		MvcResult slot = mockMvc.perform(post("/mentors/" + mentorId + "/slots")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "clock-slot-" + UUID.randomUUID())
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
				.header("Idempotency-Key", "clock-book-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("slotId", slotId, "message", "quero aprender"))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

}