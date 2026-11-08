package com.socialcoisa.rede.calendar.adapters.in.web;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.calendar.application.port.out.CalendarEntryRepository;
import com.socialcoisa.rede.calendar.application.port.out.CalendarParticipantRepository;
import com.socialcoisa.rede.calendar.domain.CalendarEntry;
import com.socialcoisa.rede.calendar.domain.CalendarEntrySource;
import com.socialcoisa.rede.calendar.domain.CalendarKind;
import com.socialcoisa.rede.calendar.domain.CalendarScope;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "spring.task.scheduling.enabled=false")
@AutoConfigureMockMvc
class CalendarInvitationApiTests {

	private static final Instant FROM = Instant.parse("2026-12-10T00:00:00Z");
	private static final Instant TO = Instant.parse("2026-12-17T00:00:00Z");
	private static final Instant STARTS_AT = Instant.parse("2026-12-11T22:00:00Z");
	private static final Instant ENDS_AT = Instant.parse("2026-12-11T23:00:00Z");
	private static final Instant CREATED_AT = Instant.parse("2026-11-08T00:00:00Z");
	private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

	@Autowired MockMvc mockMvc;
	@Autowired ObjectMapper objectMapper;
	@Autowired CalendarEntryRepository entries;
	@Autowired CalendarParticipantRepository participants;

	private TestAuthHelper.AuthSession account(String userId) throws Exception {
		return new TestAuthHelper(mockMvc, objectMapper).authenticate(userId);
	}

	/** O @ em que a tela convida é o do perfil social, que o cadastro de teste já cria. */
	private String usernameOf(TestAuthHelper.AuthSession session) throws Exception {
		String body = mockMvc.perform(get("/me").header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return objectMapper.readTree(body).get("username").asString();
	}

	private String saveEntry(String ownerId, String title) {
		String id = UUID.randomUUID().toString();
		entries.save(CalendarEntry.create(id, ownerId, CalendarScope.SOCIAL, CalendarKind.PERSONAL,
			new CalendarEntrySource(null, null), title, null, STARTS_AT, ENDS_AT, ZONE, false, CREATED_AT));
		return id;
	}

	private String invite(TestAuthHelper.AuthSession owner, String entryId, String username, String key)
			throws Exception {
		return mockMvc
			.perform(post("/calendar/entries/{entryId}/participants", entryId)
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("username", username))))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
	}

	private String agendaAs(TestAuthHelper.AuthSession session) throws Exception {
		return mockMvc
			.perform(get("/calendar/entries").header("X-Session-Token", session.token())
				.param("from", FROM.toString())
				.param("to", TO.toString()))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
	}

	@Test
	void semSessaoAsRotasDeConviteRespondem401() throws Exception {
		String entryId = UUID.randomUUID().toString();
		mockMvc
			.perform(post("/calendar/entries/{entryId}/participants", entryId)
				.header("Idempotency-Key", "no-session")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"alguem\"}"))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/calendar/entries/{entryId}/participants", entryId))
			.andExpect(status().isUnauthorized());
		mockMvc
			.perform(patch("/calendar/entries/{entryId}/participants/me", entryId)
				.header("Idempotency-Key", "no-session")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"state\":\"ACCEPTED\"}"))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(delete("/calendar/entries/{entryId}/participants/{participantId}", entryId,
				UUID.randomUUID().toString())).andExpect(status().isUnauthorized());
	}

	@Test
	void organizadorConvidaPeloArroba() throws Exception {
		var owner = account("cal_inv_owner");
		var guest = account("cal_inv_guest");
		String entryId = saveEntry(owner.userId(), "Jantar");
		String guestHandle = usernameOf(guest);

		String created = invite(owner, entryId, guestHandle, "invite-1");
		// O perfil guarda o @ como digitado; a resposta devolve o mesmo @ que a busca achou.
		assertEquals(guestHandle, objectMapper.readTree(created).get("username").asString());
		assertEquals("INVITED", objectMapper.readTree(created).get("state").asString());

		mockMvc.perform(get("/calendar/entries/{entryId}/participants", entryId)
			.header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(1)))
			.andExpect(jsonPath("$[0].username").value(guestHandle))
			.andExpect(jsonPath("$[0].state").value("INVITED"))
			.andExpect(jsonPath("$[0].respondedAt").value(nullValue()))
			.andExpect(jsonPath("$[0].userId").doesNotExist());
	}

	@Test
	void conviteRepetidoResponde409() throws Exception {
		var owner = account("cal_inv_repeat_owner");
		var guest = account("cal_inv_repeat_guest");
		String entryId = saveEntry(owner.userId(), "Churrasco");
		String handle = usernameOf(guest);

		invite(owner, entryId, handle, "repeat-1");
		mockMvc
			.perform(post("/calendar/entries/{entryId}/participants", entryId)
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "repeat-2")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("username", handle))))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("INVITE_ALREADY_EXISTS"));
	}

	@Test
	void convidarASiMesmoOuPerfilInexistenteResponde400ou404() throws Exception {
		var owner = account("cal_inv_self");
		String entryId = saveEntry(owner.userId(), "Sozinho");

		mockMvc
			.perform(post("/calendar/entries/{entryId}/participants", entryId)
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "self-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("username", usernameOf(owner)))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVITE_SELF"));

		mockMvc
			.perform(post("/calendar/entries/{entryId}/participants", entryId)
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "ghost-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("username", "ninguem_com_esse_arroba"))))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("INVITE_USER_NOT_FOUND"));
	}

	@Test
	void conviteParaEntradaAlheiaResponde404() throws Exception {
		var owner = account("cal_inv_foreign_owner");
		var stranger = account("cal_inv_foreign_stranger");
		var guest = account("cal_inv_foreign_guest");
		String entryId = saveEntry(owner.userId(), "Particular");

		mockMvc
			.perform(post("/calendar/entries/{entryId}/participants", entryId)
				.header("X-Session-Token", stranger.token())
				.header("Idempotency-Key", "foreign-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("username", usernameOf(guest)))))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("CALENDAR_ENTRY_NOT_FOUND"));

		mockMvc.perform(get("/calendar/entries/{entryId}/participants", entryId)
			.header("X-Session-Token", stranger.token()))
			.andExpect(status().isNotFound());
	}

	@Test
	void conviteApareceNaAgendaDoConvidadoComOEstado() throws Exception {
		var owner = account("cal_inv_agenda_owner");
		var guest = account("cal_inv_agenda_guest");
		String ownEntry = saveEntry(guest.userId(), "Meu compromisso");
		String invited = saveEntry(owner.userId(), "Aniversario dela");

		invite(owner, invited, usernameOf(guest), "agenda-1");

		var json = objectMapper.readTree(agendaAs(guest));
		assertEquals(2, json.size());
		Map<String, JsonNode> byId = new java.util.HashMap<>();
		for (JsonNode item : json) {
			byId.put(item.get("entry").get("id").asString(), item);
		}
		assertTrue(byId.get(ownEntry).get("owner").asBoolean());
		assertTrue(byId.get(ownEntry).get("participation").isNull());
		assertFalse(byId.get(invited).get("owner").asBoolean());
		assertEquals("INVITED", byId.get(invited).get("participation").asString());

		mockMvc
			.perform(patch("/calendar/entries/{entryId}/participants/me", invited)
				.header("X-Session-Token", guest.token())
				.header("Idempotency-Key", "answer-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("state", "ACCEPTED"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("ACCEPTED"))
			.andExpect(jsonPath("$.respondedAt").value(notNullValue()));

		mockMvc.perform(get("/calendar/entries").header("X-Session-Token", guest.token())
			.param("from", FROM.toString())
			.param("to", TO.toString()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.entry.id == '" + invited + "')].participation").value("ACCEPTED"));
	}

	@Test
	void convidadoNaoEditaNemCancelaAEntradaDoOrganizador() throws Exception {
		var owner = account("cal_inv_noedit_owner");
		var guest = account("cal_inv_noedit_guest");
		String entryId = saveEntry(owner.userId(), "Do organizador");
		invite(owner, entryId, usernameOf(guest), "noedit-1");

		mockMvc
			.perform(patch("/calendar/entries/{entryId}", entryId)
				.header("X-Session-Token", guest.token())
				.header("Idempotency-Key", "noedit-2")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"title\":\"Invadida\"}"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("CALENDAR_ENTRY_NOT_FOUND"));

		mockMvc.perform(delete("/calendar/entries/{entryId}", entryId)
			.header("X-Session-Token", guest.token())
			.header("Idempotency-Key", "noedit-3")).andExpect(status().isNotFound());

		assertEquals("Do organizador", entries.findById(entryId).orElseThrow().title());
	}

	@Test
	void responderConviteDeOutraPessoaResponde404() throws Exception {
		var owner = account("cal_inv_third_owner");
		var guest = account("cal_inv_third_guest");
		var other = account("cal_inv_third_other");
		String entryId = saveEntry(owner.userId(), "Com convidada");
		invite(owner, entryId, usernameOf(guest), "third-1");

		mockMvc
			.perform(patch("/calendar/entries/{entryId}/participants/me", entryId)
				.header("X-Session-Token", other.token())
				.header("Idempotency-Key", "third-2")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"state\":\"ACCEPTED\"}"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("CALENDAR_INVITE_NOT_FOUND"));

		// O organizador também não responde no lugar de quem foi convidado.
		mockMvc
			.perform(patch("/calendar/entries/{entryId}/participants/me", entryId)
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "third-3")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"state\":\"ACCEPTED\"}"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("CALENDAR_INVITE_NOT_FOUND"));
	}

	@Test
	void respostaInvalidaResponde400() throws Exception {
		var owner = account("cal_inv_badstate_owner");
		var guest = account("cal_inv_badstate_guest");
		String entryId = saveEntry(owner.userId(), "Com resposta ruim");
		invite(owner, entryId, usernameOf(guest), "badstate-1");

		for (String state : new String[] { "INVITED", "TALVEZ", "" }) {
			mockMvc
				.perform(patch("/calendar/entries/{entryId}/participants/me", entryId)
					.header("X-Session-Token", guest.token())
					.header("Idempotency-Key", "badstate-" + state)
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(Map.of("state", state))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INVITE_RESPONSE"));
		}

		// TENTATIVE é resposta válida: "talvez" não é "aceito".
		mockMvc
			.perform(patch("/calendar/entries/{entryId}/participants/me", entryId)
				.header("X-Session-Token", guest.token())
				.header("Idempotency-Key", "badstate-ok")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"state\":\"TENTATIVE\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("TENTATIVE"));
	}

	@Test
	void conviteNaEntradaCanceladaEhRecusado() throws Exception {
		var owner = account("cal_inv_cancel_owner");
		var guest = account("cal_inv_cancel_guest");
		String entryId = saveEntry(owner.userId(), "Vai cancelar");
		mockMvc.perform(delete("/calendar/entries/{entryId}", entryId)
			.header("X-Session-Token", owner.token())
			.header("Idempotency-Key", "cancel-1")).andExpect(status().isOk());

		mockMvc
			.perform(post("/calendar/entries/{entryId}/participants", entryId)
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "cancel-2")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("username", usernameOf(guest)))))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("CALENDAR_ENTRY_CANCELLED"));
	}

	@Test
	void organizadorRemoveOConvite() throws Exception {
		var owner = account("cal_inv_remove_owner");
		var guest = account("cal_inv_remove_guest");
		String entryId = saveEntry(owner.userId(), "Com convite removido");
		String created = invite(owner, entryId, usernameOf(guest), "remove-1");
		String participantId = objectMapper.readTree(created).get("id").asString();

		mockMvc.perform(delete("/calendar/entries/{entryId}/participants/{participantId}", entryId, participantId)
			.header("X-Session-Token", owner.token())).andExpect(status().isNoContent());

		assertTrue(participants.findByEntry(entryId).isEmpty());
		mockMvc.perform(get("/calendar/entries").header("X-Session-Token", guest.token())
			.param("from", FROM.toString())
			.param("to", TO.toString())).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
	}
}
