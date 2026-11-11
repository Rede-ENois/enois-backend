package com.socialcoisa.rede.calendar.adapters.in.web;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.socialcoisa.rede.calendar.domain.CalendarEntry;
import com.socialcoisa.rede.calendar.domain.CalendarEntrySource;
import com.socialcoisa.rede.calendar.domain.CalendarKind;
import com.socialcoisa.rede.calendar.domain.CalendarScope;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "spring.task.scheduling.enabled=false")
@AutoConfigureMockMvc
class CalendarEntryWriteApiTests {

	private static final String STARTS_AT = "2026-12-02T22:00:00Z";
	private static final String ENDS_AT = "2026-12-02T23:00:00Z";
	private static final Instant CREATED_AT = Instant.parse("2026-11-08T00:00:00Z");
	private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

	@Autowired MockMvc mockMvc;
	@Autowired ObjectMapper objectMapper;
	@Autowired CalendarEntryRepository entries;

	private TestAuthHelper.AuthSession account(String userId) throws Exception {
		return new TestAuthHelper(mockMvc, objectMapper).authenticate(userId);
	}

	private String saveEntry(String ownerId, CalendarScope scope, String title) {
		String id = UUID.randomUUID().toString();
		entries.save(CalendarEntry.create(id, ownerId, scope, CalendarKind.PERSONAL,
			new CalendarEntrySource(null, null), title, null, Instant.parse(STARTS_AT), Instant.parse(ENDS_AT), ZONE,
			false, CREATED_AT));
		return id;
	}

	/** Entrada como o módulo dono grava: origem preenchida, dono sendo quem vai comparecer. */
	private String saveSourcedEntry(String ownerId, String sourceType, String sourceId) {
		String id = UUID.randomUUID().toString();
		entries.save(CalendarEntry.create(id, ownerId, CalendarScope.PROFESSIONAL, CalendarKind.MENTORING,
			new CalendarEntrySource(sourceType, sourceId), "Mentoria: Java", null, Instant.parse(STARTS_AT),
			Instant.parse(ENDS_AT), ZONE, false, CREATED_AT));
		return id;
	}

	private String body(Map<String, Object> fields) {
		return objectMapper.writeValueAsString(fields);
	}

	private Map<String, Object> validBody(String title) {
		return Map.of("scope", "PROFESSIONAL", "kind", "MEETING", "title", title, "description", "Combinado",
			"startsAt", STARTS_AT, "endsAt", ENDS_AT, "timeZoneId", "America/Sao_Paulo", "allDay", false);
	}

	private String createAs(TestAuthHelper.AuthSession session, String key, Map<String, Object> fields)
			throws Exception {
		return mockMvc
			.perform(post("/calendar/entries").header("X-Session-Token", session.token())
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(fields)))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
	}

	@Test
	void semSessaoAsTresEscritasRespondem401() throws Exception {
		mockMvc
			.perform(post("/calendar/entries").header("Idempotency-Key", "no-session")
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(validBody("Sem sessao"))))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

		mockMvc.perform(patch("/calendar/entries/{entryId}", UUID.randomUUID().toString())
			.header("Idempotency-Key", "no-session")
			.contentType(MediaType.APPLICATION_JSON)
			.content(body(Map.of("title", "Sem sessao")))).andExpect(status().isUnauthorized());

		mockMvc.perform(delete("/calendar/entries/{entryId}", UUID.randomUUID().toString())
			.header("Idempotency-Key", "no-session")).andExpect(status().isUnauthorized());
	}

	@Test
	void criacaoResponde201ComOContratoDaLeitura() throws Exception {
		var owner = account("cal_write_create");
		String created = createAs(owner, "create-1", validBody("Retrospectiva"));
		String id = objectMapper.readTree(created).get("id").asString();

		mockMvc.perform(get("/calendar/entries/{entryId}", id).header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.entry.id").value(id))
			.andExpect(jsonPath("$.entry.title").value("Retrospectiva"))
			.andExpect(jsonPath("$.entry.scope").value("PROFESSIONAL"))
			.andExpect(jsonPath("$.entry.kind").value("MEETING"))
			.andExpect(jsonPath("$.entry.state").value("ACTIVE"))
			.andExpect(jsonPath("$.entry.startsAt").value(STARTS_AT))
			.andExpect(jsonPath("$.entry.endsAt").value(ENDS_AT))
			.andExpect(jsonPath("$.entry.timeZoneId").value("America/Sao_Paulo"))
			.andExpect(jsonPath("$.entry.sourceType").value(nullValue()))
			.andExpect(jsonPath("$.entry.ownerId").doesNotExist())
			.andExpect(jsonPath("$.owner").value(true));

		assertEquals("Retrospectiva", entries.findById(id).orElseThrow().title());
	}

	@Test
	void criacaoExigeIdempotencyKey() throws Exception {
		var owner = account("cal_write_key_required");
		mockMvc
			.perform(post("/calendar/entries").header("X-Session-Token", owner.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(validBody("Sem chave"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
	}

	@Test
	void mesmaChaveComMesmoCorpoNaoDuplica() throws Exception {
		var owner = account("cal_write_replay");
		String first = createAs(owner, "replay-1", validBody("Reuniao repetida"));
		String second = createAs(owner, "replay-1", validBody("Reuniao repetida"));
		assertEquals(objectMapper.readTree(first).get("id").asString(), objectMapper.readTree(second).get("id").asString());

		mockMvc
			.perform(get("/calendar/entries").header("X-Session-Token", owner.token())
				.param("from", "2026-12-01T00:00:00Z")
				.param("to", "2026-12-08T00:00:00Z"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(1)));

		mockMvc
			.perform(post("/calendar/entries").header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "replay-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(validBody("Outro titulo"))))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("IDEMPOTENCY_PAYLOAD_CONFLICT"));
	}

	@Test
	void corpoInvalidoResponde400ComCodigo() throws Exception {
		var owner = account("cal_write_invalid");

		mockMvc
			.perform(post("/calendar/entries").header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "invalid-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(Map.of("title", "  ", "startsAt", STARTS_AT, "endsAt", ENDS_AT, "timeZoneId",
						"America/Sao_Paulo"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_CALENDAR_ENTRY"));

		mockMvc
			.perform(post("/calendar/entries").header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "invalid-2")
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(Map.of("title", "Invertido", "startsAt", ENDS_AT, "endsAt", STARTS_AT, "timeZoneId",
						"America/Sao_Paulo"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("depois do início")));

		mockMvc
			.perform(post("/calendar/entries").header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "invalid-3")
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(Map.of("title", "Fuso inventado", "startsAt", STARTS_AT, "endsAt", ENDS_AT,
						"timeZoneId", "America/Atlantida"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("fuso")));

		mockMvc
			.perform(post("/calendar/entries").header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "invalid-4")
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(Map.of("title", "Data colada", "startsAt", "02/12/2026 19:00", "endsAt", ENDS_AT,
						"timeZoneId", "America/Sao_Paulo"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_CALENDAR_ENTRY"));

		mockMvc
			.perform(post("/calendar/entries").header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "invalid-5")
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(Map.of("title", "Agenda errada", "scope", "PESSOAL", "startsAt", STARTS_AT, "endsAt",
						ENDS_AT, "timeZoneId", "America/Sao_Paulo"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_SCOPE"));
	}

	@Test
	void edicaoParcialTrocaSoOQueVeio() throws Exception {
		var owner = account("cal_write_patch");
		String id = objectMapper.readTree(createAs(owner, "patch-1", validBody("Antes"))).get("id").asString();

		mockMvc
			.perform(patch("/calendar/entries/{entryId}", id).header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "patch-2")
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(Map.of("title", "Depois", "startsAt", "2026-12-03T22:00:00Z", "endsAt",
						"2026-12-03T23:30:00Z", "timeZoneId", "America/Sao_Paulo"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(id))
			.andExpect(jsonPath("$.title").value("Depois"))
			.andExpect(jsonPath("$.description").value("Combinado"))
			.andExpect(jsonPath("$.startsAt").value("2026-12-03T22:00:00Z"))
			.andExpect(jsonPath("$.endsAt").value("2026-12-03T23:30:00Z"))
			.andExpect(jsonPath("$.state").value("ACTIVE"));

		mockMvc
			.perform(patch("/calendar/entries/{entryId}", id).header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "patch-3")
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(Map.of("description", " "))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.description").value(nullValue()));

		mockMvc
			.perform(patch("/calendar/entries/{entryId}", id).header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "patch-4")
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(Map.of("startsAt", "2026-12-04T22:00:00Z"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_CALENDAR_ENTRY"));
	}

	@Test
	void entradaAlheiaRespondeO4o4DeUmaQueNaoExiste() throws Exception {
		var owner = account("cal_write_owner");
		var stranger = account("cal_write_stranger");
		String id = saveEntry(owner.userId(), CalendarScope.SOCIAL, "Minha");

		mockMvc
			.perform(patch("/calendar/entries/{entryId}", id).header("X-Session-Token", stranger.token())
				.header("Idempotency-Key", "foreign-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(Map.of("title", "Invadida"))))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("CALENDAR_ENTRY_NOT_FOUND"));

		mockMvc
			.perform(delete("/calendar/entries/{entryId}", id).header("X-Session-Token", stranger.token())
				.header("Idempotency-Key", "foreign-2"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("CALENDAR_ENTRY_NOT_FOUND"));

		assertEquals("Minha", entries.findById(id).orElseThrow().title());
	}

	@Test
	void cancelamentoFicaNaLinhaEContinuaAparecendoNaLeitura() throws Exception {
		var owner = account("cal_write_cancel");
		String id = saveEntry(owner.userId(), CalendarScope.PROFESSIONAL, "Vai cancelar");

		mockMvc.perform(delete("/calendar/entries/{entryId}", id).header("X-Session-Token", owner.token())
			.header("Idempotency-Key", "cancel-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("CANCELLED"))
			.andExpect(jsonPath("$.cancelledAt").value(notNullValue()));

		mockMvc
			.perform(get("/calendar/entries").header("X-Session-Token", owner.token())
				.param("from", "2026-12-01T00:00:00Z")
				.param("to", "2026-12-08T00:00:00Z"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[*].entry.id", containsInAnyOrder(id)))
			.andExpect(jsonPath("$[0].entry.state").value("CANCELLED"));

		Instant cancelledAt = entries.findById(id).orElseThrow().cancelledAt();
		mockMvc.perform(delete("/calendar/entries/{entryId}", id).header("X-Session-Token", owner.token())
			.header("Idempotency-Key", "cancel-2"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("CANCELLED"));
		assertEquals(cancelledAt, entries.findById(id).orElseThrow().cancelledAt());

		mockMvc
			.perform(patch("/calendar/entries/{entryId}", id).header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "cancel-3")
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(Map.of("title", "Reabrir"))))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("CALENDAR_ENTRY_CANCELLED"));
	}

	@Test
	void entradaComOrigemNaoPodeSerEditadaNemCanceladaPelaAgenda() throws Exception {
		var owner = account("cal_write_sourced");
		String id = saveSourcedEntry(owner.userId(), "MENTORING", "mentor_session_1");

		mockMvc
			.perform(patch("/calendar/entries/{entryId}", id).header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "sourced-patch")
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(Map.of("title", "Outro titulo"))))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("CALENDAR_ENTRY_SOURCE_OWNED"));

		mockMvc
			.perform(delete("/calendar/entries/{entryId}", id).header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "sourced-cancel"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("CALENDAR_ENTRY_SOURCE_OWNED"));

		// Nada mudou: o módulo dono continua sendo a única fonte da verdade.
		CalendarEntry entry = entries.findById(id).orElseThrow();
		assertEquals("Mentoria: Java", entry.title());
		assertEquals(true, entry.active());
	}

	@Test
	void escritaFuncionaPeloAliasV1() throws Exception {
		var owner = account("cal_write_v1");
		mockMvc
			.perform(post("/v1/calendar/entries").header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "v1-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(validBody("Pela v1"))))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.title").value("Pela v1"));
	}
}
