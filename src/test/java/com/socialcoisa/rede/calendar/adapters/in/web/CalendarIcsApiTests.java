package com.socialcoisa.rede.calendar.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import com.socialcoisa.rede.calendar.application.port.out.CalendarEntryRepository;
import com.socialcoisa.rede.calendar.domain.CalendarEntry;
import com.socialcoisa.rede.calendar.domain.CalendarEntrySource;
import com.socialcoisa.rede.calendar.domain.CalendarKind;
import com.socialcoisa.rede.calendar.domain.CalendarScope;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * Contrato HTTP do `.ics` (fase 8): sem sessão é 401 nos dois sentidos, o export sai
 * `text/calendar` com `Content-Disposition` de anexo, a janela é obrigatória, e o import é
 * escrita — exige `Idempotency-Key` e não duplica no retry (D11/D3).
 */
@SpringBootTest(properties = "spring.task.scheduling.enabled=false")
@AutoConfigureMockMvc
class CalendarIcsApiTests {

	private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
	private static final Instant FROM = Instant.parse("2027-02-01T12:00:00Z");
	private static final Instant TO = FROM.plus(Duration.ofDays(1));

	@Autowired MockMvc mockMvc;
	@Autowired ObjectMapper objectMapper;
	@Autowired CalendarEntryRepository entries;

	private TestAuthHelper.AuthSession account() throws Exception {
		return new TestAuthHelper(mockMvc, objectMapper).authenticate("ics_api_" + UUID.randomUUID());
	}

	private void saveEntry(String ownerId, String title) {
		entries.save(CalendarEntry.create("cal_" + UUID.randomUUID().toString().replace("-", ""), ownerId,
			CalendarScope.SOCIAL, CalendarKind.PERSONAL, new CalendarEntrySource(null, null), title, null,
			FROM.plus(Duration.ofHours(3)), FROM.plus(Duration.ofHours(4)), ZONE, false, Instant.now()));
	}

	@Test
	void exportWithoutSessionIs401() throws Exception {
		mockMvc.perform(get("/calendar/entries/export.ics").param("from", FROM.toString())
			.param("to", TO.toString())).andExpect(status().isUnauthorized());
	}

	@Test
	void exportAnswersTextCalendarWithAttachmentDisposition() throws Exception {
		TestAuthHelper.AuthSession user = account();
		saveEntry(user.userId(), "Compromisso");

		mockMvc.perform(get("/calendar/entries/export.ics")
			.header("X-Session-Token", user.token())
			.param("from", FROM.toString()).param("to", TO.toString()))
			.andExpect(status().isOk())
			.andExpect(header().string("Content-Type", "text/calendar;charset=UTF-8"))
			.andExpect(header().string("Content-Disposition", "attachment; filename=\"agenda-enois.ics\""))
			// O corpo é o arquivo, não JSON: o marcador do formato aparece na resposta crua.
			.andExpect(result -> org.assertj.core.api.Assertions
				.assertThat(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
				.contains("BEGIN:VCALENDAR")
				.contains("SUMMARY:Compromisso"));
	}

	@Test
	void exportWithoutWindowIs400() throws Exception {
		TestAuthHelper.AuthSession user = account();
		mockMvc.perform(get("/calendar/entries/export.ics")
			.header("X-Session-Token", user.token()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_WINDOW"));
	}

	@Test
	void exportWithAWindowLongerThanAYearIs400() throws Exception {
		TestAuthHelper.AuthSession user = account();
		mockMvc.perform(get("/calendar/entries/export.ics").header("X-Session-Token", user.token())
				.param("from", FROM.minus(Duration.ofDays(400)).toString()).param("to", TO.toString()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("WINDOW_TOO_LONG"));
	}

	@Test
	void importingAFileOverTheByteCeilingIs400() throws Exception {
		TestAuthHelper.AuthSession user = account();
		StringBuilder huge = new StringBuilder("BEGIN:VCALENDAR\r\n");
		while (huge.length() < 600 * 1024) {
			huge.append("X-PREENCHIMENTO:").append("a".repeat(200)).append("\r\n");
		}
		huge.append("END:VCALENDAR\r\n");

		mockMvc.perform(withKey(multipart("/calendar/entries/import")
				.file(icsFile(huge.toString()))
				.param("scope", "SOCIAL")
				.header("X-Session-Token", user.token())))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_ICS"));
	}

	@Test
	void importWithoutSessionIs401() throws Exception {
		mockMvc.perform(icsImport("BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void importWithoutIdempotencyKeyIs400() throws Exception {
		TestAuthHelper.AuthSession user = account();
		String ics = exportOf(user);
		mockMvc.perform(multipart("/calendar/entries/import")
			.header("X-Session-Token", user.token())
			.file(icsFile(ics)))
			.andExpect(status().isBadRequest());
	}

	@Test
	void importWithoutFileIs400() throws Exception {
		TestAuthHelper.AuthSession user = account();
		mockMvc.perform(withKey(multipart("/calendar/entries/import")
			.header("X-Session-Token", user.token())))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_ICS"));
	}

	@Test
	void importedFileAnswersCountsAndRepeatingItIsIdempotent() throws Exception {
		TestAuthHelper.AuthSession source = account();
		saveEntry(source.userId(), "Para importar");
		String ics = exportOf(source);

		TestAuthHelper.AuthSession target = account();
		String key = UUID.randomUUID().toString();
		mockMvc.perform(withKey(multipart("/calendar/entries/import")
			.header("X-Session-Token", target.token())
			.file(icsFile(ics)), key))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.received").value(1))
			.andExpect(jsonPath("$.created").value(1))
			.andExpect(jsonPath("$.skipped").value(0));

		// A mesma chave com o mesmo arquivo devolve a resposta guardada em vez de importar de novo.
		mockMvc.perform(withKey(multipart("/calendar/entries/import")
			.header("X-Session-Token", target.token())
			.file(icsFile(ics)), key))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.created").value(1));
	}

	@Test
	void importingACancellationAnswersTheCancelledCount() throws Exception {
		TestAuthHelper.AuthSession user = account();
		String active = """
			BEGIN:VCALENDAR\r
			BEGIN:VEVENT\r
			UID:api-desmarcado\r
			DTSTART;TZID=America/Sao_Paulo:20270201T190000\r
			DTEND;TZID=America/Sao_Paulo:20270201T200000\r
			SUMMARY:Consulta\r
			STATUS:CONFIRMED\r
			END:VEVENT\r
			END:VCALENDAR\r
			""";
		mockMvc.perform(withKey(multipart("/calendar/entries/import")
			.header("X-Session-Token", user.token())
			.file(icsFile(active))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.created").value(1));

		mockMvc.perform(withKey(multipart("/calendar/entries/import")
			.header("X-Session-Token", user.token())
			.file(icsFile(active.replace("STATUS:CONFIRMED", "STATUS:CANCELLED")))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.created").value(0))
			.andExpect(jsonPath("$.cancelled").value(1))
			.andExpect(jsonPath("$.skipped").value(0));
	}

	@Test
	void aDifferentFileWithTheSameKeyIsAPayloadConflict() throws Exception {
		TestAuthHelper.AuthSession user = account();
		String key = UUID.randomUUID().toString();
		String first = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:um\r\n"
			+ "DTSTART;TZID=America/Sao_Paulo:20270202T190000\r\nSUMMARY:Um\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";

		mockMvc.perform(withKey(multipart("/calendar/entries/import").file(icsFile(first))
			.header("X-Session-Token", user.token()), key))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.created").value(1));

		// O hash da chave sai do arquivo: trocar um byte reconhece a chave como reutilizada, em vez
		// de devolver a resposta antiga como se o arquivo novo tivesse sido importado.
		mockMvc.perform(withKey(multipart("/calendar/entries/import")
			.file(icsFile(first.replace("SUMMARY:Um", "SUMMARY:Outro")))
			.header("X-Session-Token", user.token()), key))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("IDEMPOTENCY_PAYLOAD_CONFLICT"));
	}

	private String exportOf(TestAuthHelper.AuthSession user) throws Exception {
		return mockMvc.perform(get("/calendar/entries/export.ics")
			.header("X-Session-Token", user.token())
			.param("from", FROM.toString()).param("to", TO.toString()))
			.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
	}

	private static MockMultipartHttpServletRequestBuilder icsImport(String body) {
		return withKey(multipart("/calendar/entries/import").file(icsFile(body)));
	}

	private static MockMultipartHttpServletRequestBuilder withKey(MockMultipartHttpServletRequestBuilder builder) {
		return withKey(builder, UUID.randomUUID().toString());
	}

	private static MockMultipartHttpServletRequestBuilder withKey(MockMultipartHttpServletRequestBuilder builder,
			String key) {
		builder.header("Idempotency-Key", key);
		return builder;
	}

	private static MockMultipartFile icsFile(String body) {
		return new MockMultipartFile("file", "agenda.ics", "text/calendar",
			body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}
}