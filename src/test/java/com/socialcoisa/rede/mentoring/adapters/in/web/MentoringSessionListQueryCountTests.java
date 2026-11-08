package com.socialcoisa.rede.mentoring.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * As sessões que EU marquei (GET /mentors/sessions/mine) não podem consultar por item.
 *
 * <p>Antes: cada sessão da lista disparava até três consultas próprias — o mentor de cada linha, o
 * horário de cada linha e, dentro do endereço da sala, o mentor da linha outra vez. Para quem já
 * tinha dez sessões marcadas isso virava ~30 SELECTs numa tela que abre toda hora. A prova aqui é a
 * CONTAGEM de consultas reais: o número não pode crescer com o número de sessões.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MentoringSessionListQueryCountTests.InspectorConfig.class)
class MentoringSessionListQueryCountTests {

	@TestConfiguration
	static class InspectorConfig {

		@Bean
		CountingInspector countingInspector() {
			return new CountingInspector();
		}

		@Bean
		org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer statementInspector(
				CountingInspector countingInspector) {
			return properties -> properties.put(
				org.hibernate.cfg.AvailableSettings.STATEMENT_INSPECTOR, countingInspector);
		}
	}

	/** Conta as instruções SQL da thread do teste (o MockMvc roda na própria thread). */
	static class CountingInspector implements StatementInspector {

		private final AtomicReference<List<String>> captured =
			new AtomicReference<>(Collections.synchronizedList(new ArrayList<>()));

		@Override
		public String inspect(String sql) {
			captured.get().add(sql);
			return sql;
		}

		void reset() {
			captured.set(Collections.synchronizedList(new ArrayList<>()));
		}

		long selects() {
			return captured.get().stream()
				.filter(sql -> sql.strip().toUpperCase().startsWith("SELECT"))
				.count();
		}

		List<String> sql() {
			return List.copyOf(captured.get());
		}
	}

	@Autowired private MockMvc mockMvc;
	@Autowired private ObjectMapper objectMapper;
	@Autowired private CountingInspector inspector;

	private final String sufixo = UUID.randomUUID().toString().substring(0, 8);

	@Test
	void minhasSessoesNaoCrescemComONumeroDeSessoes() throws Exception {
		var mentor = new TestAuthHelper(mockMvc, objectMapper).authenticate("mqs_mentor_" + sufixo);
		String mentorId = createMentor(mentor);

		// Um horário só: a lista aqui é de sessões (o leitor paga por cada uma), não de slots.
		putAvailability(mentor, mentorId,
			List.of(Map.of("dayOfWeek", 1, "times", List.of("19:00", "20:00", "21:00"))));
		List<String> slots = slotIds(mentorId);

		var mentee = new TestAuthHelper(mockMvc, objectMapper).authenticate("mqs_mentee_" + sufixo);
		assertThat(slots.size()).as("o teste precisa de pelo menos três horários").isGreaterThanOrEqualTo(3);

		// Duas sessões, mede; uma terceira, mede de novo. A prova é a IGUALDADE: se houver consulta
		// por item, o segundo número é maior que o primeiro.
		book(mentee, mentorId, slots.get(0)).andExpect(status().isCreated());
		book(mentee, mentorId, slots.get(1)).andExpect(status().isCreated());
		long comDuas = selectsOfMySessions(mentee);

		book(mentee, mentorId, slots.get(2)).andExpect(status().isCreated());
		long comTres = selectsOfMySessions(mentee);

		assertThat(comTres)
			.as("SELECTs com 2 sessões: " + comDuas + "; com 3: " + comTres + " — " + inspector.sql())
			.isEqualTo(comDuas);
	}

	/**
	 * A rota irmã (agenda do mentor) tinha o mesmo defeito por outro caminho: o mentor já estava
	 * carregado, mas o endereço da sala consultava o mesmo mentor de novo, uma vez por sessão.
	 */
	@Test
	void agendaDoMentorNaoCresceComONumeroDeSessoes() throws Exception {
		var mentor = new TestAuthHelper(mockMvc, objectMapper).authenticate("mqs_agenda_mentor_" + sufixo);
		String mentorId = createMentor(mentor);
		putAvailability(mentor, mentorId,
			List.of(Map.of("dayOfWeek", 1, "times", List.of("19:00", "20:00", "21:00"))));
		List<String> slots = slotIds(mentorId);
		assertThat(slots.size()).as("o teste precisa de pelo menos três horários").isGreaterThanOrEqualTo(3);

		var mentee = new TestAuthHelper(mockMvc, objectMapper).authenticate("mqs_agenda_mentee_" + sufixo);
		book(mentee, mentorId, slots.get(0)).andExpect(status().isCreated());
		book(mentee, mentorId, slots.get(1)).andExpect(status().isCreated());
		long comDuas = selectsOfMentorAgenda(mentor, mentorId);

		book(mentee, mentorId, slots.get(2)).andExpect(status().isCreated());
		long comTres = selectsOfMentorAgenda(mentor, mentorId);

		assertThat(comTres)
			.as("SELECTs com 2 sessões: " + comDuas + "; com 3: " + comTres + " — " + inspector.sql())
			.isEqualTo(comDuas);
	}

	private long selectsOfMentorAgenda(TestAuthHelper.AuthSession mentor, String mentorId) throws Exception {
		inspector.reset();
		mockMvc.perform(get("/mentors/" + mentorId + "/sessions").header("X-Session-Token", mentor.token()))
			.andExpect(status().isOk());
		return inspector.selects();
	}

	private long selectsOfMySessions(TestAuthHelper.AuthSession mentee) throws Exception {
		inspector.reset();
		mockMvc.perform(get("/mentors/sessions/mine").header("X-Session-Token", mentee.token()))
			.andExpect(status().isOk());
		return inspector.selects();
	}

	private String createMentor(TestAuthHelper.AuthSession owner) throws Exception {
		MvcResult created = mockMvc.perform(post("/mentors")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "mqs-create-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("expertise", List.of("Java"), "price", "Gratuito"))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
	}

	private void putAvailability(TestAuthHelper.AuthSession owner, String mentorId,
			List<Map<String, Object>> items) throws Exception {
		mockMvc.perform(put("/mentors/" + mentorId + "/availability")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "mqs-avail-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("items", items))))
			.andExpect(status().isOk());
	}

	private List<String> slotIds(String mentorId) throws Exception {
		MvcResult result = mockMvc.perform(get("/mentors/" + mentorId + "/slots"))
			.andExpect(status().isOk())
			.andReturn();
		List<String> ids = new ArrayList<>();
		for (var slot : objectMapper.readTree(result.getResponse().getContentAsString()).get("items")) {
			ids.add(slot.get("id").asText());
		}
		return ids;
	}

	private org.springframework.test.web.servlet.ResultActions book(TestAuthHelper.AuthSession mentee, String mentorId,
			String slotId) throws Exception {
		return mockMvc.perform(post("/mentors/" + mentorId + "/sessions")
			.header("X-Session-Token", mentee.token())
			.header("Idempotency-Key", "mqs-book-" + UUID.randomUUID())
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("slotId", slotId, "message", "quero aprender"))));
	}

}
