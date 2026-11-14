package com.socialcoisa.rede.mentoring.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.coin.application.CoinService;
import com.socialcoisa.rede.mentoring.application.port.out.MentorRepository;
import com.socialcoisa.rede.mentoring.domain.MentorSlot;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * Corrida pela mesma vaga: o `available` que a leitura trouxe não é árbitro — entre a leitura e a
 * escrita, outra inscrição pode ter reservado o horário. Quem decide é o UPDATE condicional, e quem
 * perde a corrida sai da fila sem pagar nada.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MentorSlotRaceTests {

	private static final long PRICE = CoinService.FAIS(120);

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private CoinService coins;

	@Autowired
	private JdbcTemplate jdbc;

	@MockitoSpyBean
	private MentorRepository repository;

	@Test
	void aBookingWhoseSlotWasTakenBetweenReadAndWriteIsRefusedAndCostsNothing() throws Exception {
		var auth = new TestAuthHelper(mockMvc, objectMapper);
		var owner = auth.authenticate("mentor_race_owner");
		var mentee = auth.authenticate("mentor_race_mentee");
		String mentorId = createMentor(owner, PRICE);
		String slotId = createSlot(owner, mentorId, Instant.now().plusSeconds(86_400));

		fund(mentee, CoinService.FAIS(300), "test-mentoria-fundo-race");
		long menteeBefore = coins.balance(mentee.userId());

		// O concorrente reserva a linha entre a leitura e a escrita desta inscrição: a leitura ainda
		// devolve o snapshot "disponível", mas o UPDATE já não alcança mais ninguém.
		AtomicReference<String> raced = new AtomicReference<>(slotId);
		doAnswer(invocation -> {
			@SuppressWarnings("unchecked")
			Optional<MentorSlot> found = (Optional<MentorSlot>) invocation.callRealMethod();
			String target = raced.get();
			if (target != null && found.isPresent() && found.get().id().equals(target) && found.get().available()) {
				jdbc.update("update mentor_slots set available = false where id = ?", target);
			}
			return found;
		}).when(repository).findSlotById(anyString());

		mockMvc.perform(post("/mentors/" + mentorId + "/sessions")
				.header("X-Session-Token", mentee.token())
				.header("Idempotency-Key", "sparks-race-book-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("slotId", slotId, "message", "oi"))))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SLOT_UNAVAILABLE"));

		// Perder a corrida não custa nada: nenhuma faísca retida e nenhuma sessão criada.
		assertThat(coins.balance(mentee.userId())).isEqualTo(menteeBefore);
		mockMvc.perform(get("/mentors/sessions/mine").header("X-Session-Token", mentee.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$").isEmpty());
	}

	private void fund(TestAuthHelper.AuthSession user, long amount, String prefix) {
		coins.reward(user.userId(), amount, prefix + "-" + user.userId(), "Saldo de teste");
		// O airdrop entra na primeira passagem pela moeda: aqui ele acontece ANTES de medir, para o
		// saldo do teste ser só o que o teste movimentou.
		coins.ensureGenesisFor(user.userId());
	}

	private String createMentor(TestAuthHelper.AuthSession owner, long sparksPrice) throws Exception {
		MvcResult created = mockMvc
			.perform(post("/mentors")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "race-mentor-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("expertise", List.of("Java"), "price", "Gratuito",
					"sparksPrice", sparksPrice))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
	}

	private String createSlot(TestAuthHelper.AuthSession owner, String mentorId, Instant startsAt) throws Exception {
		MvcResult slot = mockMvc.perform(post("/mentors/" + mentorId + "/slots")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "race-slot-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("startsAt", startsAt.toString(),
					"endsAt", startsAt.plusSeconds(3_600).toString()))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(slot.getResponse().getContentAsString()).get("id").asText();
	}

}
