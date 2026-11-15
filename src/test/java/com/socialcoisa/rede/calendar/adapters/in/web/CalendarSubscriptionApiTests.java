package com.socialcoisa.rede.calendar.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.calendar.application.port.out.CalendarEntryRepository;
import com.socialcoisa.rede.calendar.domain.CalendarEntry;
import com.socialcoisa.rede.calendar.domain.CalendarEntrySource;
import com.socialcoisa.rede.calendar.domain.CalendarKind;
import com.socialcoisa.rede.calendar.domain.CalendarScope;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * Contrato HTTP da assinatura privada `.ics` (fase 9, D7).
 *
 * <p>Duas superfícies com regras opostas: a gestão exige sessão e nunca devolve o token de novo; a
 * leitura pelo link não aceita sessão, e o token do caminho é a credencial inteira. Regenerar e
 * revogar têm de cortar o link antigo na mesma resposta.
 */
@SpringBootTest(properties = "spring.task.scheduling.enabled=false")
@AutoConfigureMockMvc
class CalendarSubscriptionApiTests {

	private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

	@Autowired MockMvc mockMvc;
	@Autowired ObjectMapper objectMapper;
	@Autowired CalendarEntryRepository entries;

	private TestAuthHelper.AuthSession account() throws Exception {
		return new TestAuthHelper(mockMvc, objectMapper).authenticate("cal_sub_" + UUID.randomUUID());
	}

	private void saveEntry(String ownerId, String title) {
		Instant start = Instant.now().plus(Duration.ofDays(2));
		entries.save(CalendarEntry.create("cal_" + UUID.randomUUID().toString().replace("-", ""), ownerId,
			CalendarScope.SOCIAL, CalendarKind.PERSONAL, new CalendarEntrySource(null, null), title, null,
			start, start.plus(Duration.ofHours(1)), ZONE, false, Instant.now()));
	}

	private String issueToken(TestAuthHelper.AuthSession user) throws Exception {
		return mockMvc.perform(post("/calendar/subscription").header("X-Session-Token", user.token()))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)
			.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
	}

	private String bodyOf(MvcResult result) throws Exception {
		return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
	}

	@Test
	void managementRoutesRequireSession() throws Exception {
		mockMvc.perform(get("/calendar/subscription")).andExpect(status().isUnauthorized());
		mockMvc.perform(post("/calendar/subscription")).andExpect(status().isUnauthorized());
		mockMvc.perform(post("/calendar/subscription/regenerate")).andExpect(status().isUnauthorized());
		mockMvc.perform(delete("/calendar/subscription")).andExpect(status().isUnauthorized());
	}

	@Test
	void noSubscriptionYetIs204() throws Exception {
		TestAuthHelper.AuthSession user = account();

		mockMvc.perform(get("/calendar/subscription").header("X-Session-Token", user.token()))
			.andExpect(status().isNoContent());
	}

	@Test
	void issuingReturnsTheTokenOnlyOnce() throws Exception {
		TestAuthHelper.AuthSession user = account();

		MvcResult first = mockMvc.perform(post("/calendar/subscription").header("X-Session-Token", user.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.scope").doesNotExist())
			.andExpect(jsonPath("$.revokedAt").doesNotExist())
			.andReturn();
		assertThat(bodyOf(first)).contains("\"token\":\"");

		// A segunda chamada devolve a mesma assinatura, sem token: o link já emitido continua valendo.
		mockMvc.perform(post("/calendar/subscription").header("X-Session-Token", user.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.token").doesNotExist());

		// E a leitura da gestão também não devolve o token.
		mockMvc.perform(get("/calendar/subscription").header("X-Session-Token", user.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.token").doesNotExist());
	}

	@Test
	void subscriptionServesTheAgendaWithoutAnySession() throws Exception {
		TestAuthHelper.AuthSession user = account();
		saveEntry(user.userId(), "Compromisso assinado");
		String token = issueToken(user);

		mockMvc.perform(get("/calendar/subscribe/" + token + ".ics"))
			.andExpect(status().isOk())
			.andExpect(header().string("Content-Type", "text/calendar;charset=UTF-8"))
			.andExpect(header().string("Content-Disposition", "attachment; filename=\"agenda-enois.ics\""))
			.andExpect(header().exists("ETag"))
			.andExpect(result -> assertThat(bodyOf(result))
				.contains("BEGIN:VCALENDAR")
				.contains("SUMMARY:Compromisso assinado"));
	}

	@Test
	void sameFileAnswersNotModifiedForTheSameEtag() throws Exception {
		TestAuthHelper.AuthSession user = account();
		saveEntry(user.userId(), "Compromisso");
		String token = issueToken(user);

		String etag = mockMvc.perform(get("/calendar/subscribe/" + token + ".ics"))
			.andExpect(status().isOk())
			.andReturn().getResponse().getHeader("ETag");
		assertThat(etag).isNotBlank();

		mockMvc.perform(get("/calendar/subscribe/" + token + ".ics").header("If-None-Match", etag))
			.andExpect(status().isNotModified());
	}

	@Test
	void unknownOrRevokedTokenIs404WithoutDistinguishingTheCases() throws Exception {
		mockMvc.perform(get("/calendar/subscribe/token-que-nunca-existiu.ics"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("CALENDAR_SUBSCRIPTION_NOT_FOUND"));

		TestAuthHelper.AuthSession user = account();
		String token = issueToken(user);
		mockMvc.perform(delete("/calendar/subscription").header("X-Session-Token", user.token()))
			.andExpect(status().isNoContent());

		mockMvc.perform(get("/calendar/subscribe/" + token + ".ics"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("CALENDAR_SUBSCRIPTION_NOT_FOUND"));
	}

	@Test
	void regeneratingKillsThePreviousLinkAndKeepsOneSubscription() throws Exception {
		TestAuthHelper.AuthSession user = account();
		String first = issueToken(user);

		String second = mockMvc.perform(post("/calendar/subscription/regenerate")
			.header("X-Session-Token", user.token()))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)
			.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");

		assertThat(second).isNotEqualTo(first);
		mockMvc.perform(get("/calendar/subscribe/" + first + ".ics")).andExpect(status().isNotFound());
		mockMvc.perform(get("/calendar/subscribe/" + second + ".ics")).andExpect(status().isOk());

		// A regeneração gira a mesma assinatura: a gestão continua vendo uma ativa.
		mockMvc.perform(get("/calendar/subscription").header("X-Session-Token", user.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.revokedAt").doesNotExist());
	}

	@Test
	void revokingTwiceIs404TheSecondTimeAndIssuingAgainCreatesANewLink() throws Exception {
		TestAuthHelper.AuthSession user = account();
		issueToken(user);

		mockMvc.perform(delete("/calendar/subscription").header("X-Session-Token", user.token()))
			.andExpect(status().isNoContent());
		mockMvc.perform(delete("/calendar/subscription").header("X-Session-Token", user.token()))
			.andExpect(status().isNotFound());

		// Revogada não conta como ativa: criar de novo emite um token novo.
		String again = issueToken(user);
		assertThat(again).isNotBlank();
		mockMvc.perform(get("/calendar/subscribe/" + again + ".ics")).andExpect(status().isOk());
	}

	@Test
	void scopeIsFrozenInTheTokenAndInvalidScopeIs400() throws Exception {
		TestAuthHelper.AuthSession user = account();
		saveEntry(user.userId(), "Social");

		String token = mockMvc.perform(post("/calendar/subscription")
			.header("X-Session-Token", user.token())
			.param("scope", "PROFESSIONAL"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.scope").value("PROFESSIONAL"))
			.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)
			.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");

		// A entrada foi criada na agenda SOCIAL, então a assinatura PROFISSIONAL não a enxerga.
		mockMvc.perform(get("/calendar/subscribe/" + token + ".ics"))
			.andExpect(status().isOk())
			.andExpect(result -> assertThat(bodyOf(result)).doesNotContain("SUMMARY:Social"));

		TestAuthHelper.AuthSession other = account();
		mockMvc.perform(post("/calendar/subscription").header("X-Session-Token", other.token())
			.param("scope", "PESSOAL"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_SCOPE"));
	}

	@Test
	void askingForAnotherScopeOnAnActiveSubscriptionIsConflictInsteadOfSilentIgnoring() throws Exception {
		TestAuthHelper.AuthSession user = account();
		String token = mockMvc.perform(post("/calendar/subscription")
			.header("X-Session-Token", user.token())
			.param("scope", "SOCIAL"))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)
			.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");

		// O recorte é congelado no token: pedir outro não pode devolver a assinatura antiga como se
		// tivesse sido reconfigurada, nem emitir um segundo link vivo.
		mockMvc.perform(post("/calendar/subscription").header("X-Session-Token", user.token())
			.param("scope", "PROFESSIONAL"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("CALENDAR_SUBSCRIPTION_SCOPE_CONFLICT"));

		// A assinatura e o token antigos seguem exatamente como estavam.
		mockMvc.perform(get("/calendar/subscription").header("X-Session-Token", user.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.scope").value("SOCIAL"))
			.andExpect(jsonPath("$.token").doesNotExist());
		mockMvc.perform(get("/calendar/subscribe/" + token + ".ics")).andExpect(status().isOk());
	}

	@Test
	void repeatingTheSameScopeStillReturnsTheExistingSubscription() throws Exception {
		TestAuthHelper.AuthSession user = account();
		mockMvc.perform(post("/calendar/subscription").header("X-Session-Token", user.token())
			.param("scope", "PROFESSIONAL"))
			.andExpect(status().isOk());

		mockMvc.perform(post("/calendar/subscription").header("X-Session-Token", user.token())
			.param("scope", "PROFESSIONAL"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.scope").value("PROFESSIONAL"))
			.andExpect(jsonPath("$.token").doesNotExist());
	}
}