package com.socialcoisa.rede.suggestions.adapters.in.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.suggestions.application.port.out.SuggestionIssueGateway;
import com.socialcoisa.rede.suggestions.application.port.out.SuggestionIssueGateway.Availability;
import com.socialcoisa.rede.suggestions.domain.SuggestionTarget;

import tools.jackson.databind.ObjectMapper;

/**
 * Sugestão: entra pendente, só um moderador aprova, e a issue pública sai sem nada de quem sugeriu.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SuggestionFlowTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@MockitoBean
	private SuggestionIssueGateway gateway;

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("rede.admin.emails", () -> "sgg_admin@test.com");
	}

	@Test
	void sugestaoNasceePendenteESemSessaoERecusada() throws Exception {
		mockMvc.perform(post("/suggestions").contentType(MediaType.APPLICATION_JSON).content(body("BACKEND")))
			.andExpect(status().isUnauthorized());

		var user = new TestAuthHelper(mockMvc, objectMapper).authenticate("sgg_user1");
		mockMvc.perform(post("/suggestions").header("X-Session-Token", user.token())
			.contentType(MediaType.APPLICATION_JSON).content(body("BACKEND")))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("PENDING"))
			.andExpect(jsonPath("$.githubIssueUrl").doesNotExist());
		verify(gateway, never()).publish(any(), anyString(), anyString(), anyString(), any());
	}

	@Test
	void textoCurtoOuDestinoAusenteSaoRecusados() throws Exception {
		var user = new TestAuthHelper(mockMvc, objectMapper).authenticate("sgg_user2");
		mockMvc.perform(post("/suggestions").header("X-Session-Token", user.token())
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("target", "FRONTEND", "title", "oi", "body", "curto"))))
			.andExpect(status().isBadRequest());
		mockMvc.perform(post("/suggestions").header("X-Session-Token", user.token())
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("title", "Título válido", "body",
				"Descrição longa o bastante para passar da validação."))))
			.andExpect(status().isBadRequest());
	}

	@Test
	void aprovarAbreIssueSemDadosDoAutorEQuemSugeriuVeOLink() throws Exception {
		var user = new TestAuthHelper(mockMvc, objectMapper).authenticate("sgg_user3");
		var admin = new TestAuthHelper(mockMvc, objectMapper).authenticate("sgg_admin");
		when(gateway.availability(SuggestionTarget.DESIGN_SYSTEM)).thenReturn(Availability.READY);
		when(gateway.publish(eq(SuggestionTarget.DESIGN_SYSTEM), anyString(), anyString(), anyString(), any()))
			.thenReturn(Optional.of(new SuggestionIssueGateway.IssueRef(77, "https://github.com/org/ds/issues/77")));

		String id = submit(user.token(), "DESIGN_SYSTEM");

		mockMvc.perform(post("/admin/suggestions/" + id + "/approve").header("X-Session-Token", admin.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("PUBLISHED"))
			.andExpect(jsonPath("$.githubIssueNumber").value("77"));

		ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<String> issueBody = ArgumentCaptor.forClass(String.class);
		verify(gateway).publish(eq(SuggestionTarget.DESIGN_SYSTEM), eq(id), title.capture(), issueBody.capture(), isNull());
		String published = title.getValue() + "\n" + issueBody.getValue();
		assertFalse(published.contains("sgg_user3"), "a issue pública não pode citar o autor");
		assertFalse(published.contains(user.userId()), "a issue pública não pode levar o id do autor");
		assertFalse(published.contains("@test.com"), "a issue pública não pode levar e-mail");
		assertFalse(published.contains("@pessoa"), "menções são quebradas para não notificar ninguém");
		assertTrue(published.contains("@​pessoa"));

		mockMvc.perform(get("/suggestions/mine").header("X-Session-Token", user.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.id == '" + id + "')].githubIssueUrl").value("https://github.com/org/ds/issues/77"));
	}

	@Test
	void aprovarDuasVezesNaoAbreDuasIssues() throws Exception {
		var user = new TestAuthHelper(mockMvc, objectMapper).authenticate("sgg_user4");
		var admin = new TestAuthHelper(mockMvc, objectMapper).authenticate("sgg_admin");
		when(gateway.availability(SuggestionTarget.FRONTEND)).thenReturn(Availability.READY);
		when(gateway.publish(eq(SuggestionTarget.FRONTEND), anyString(), anyString(), anyString(), any()))
			.thenReturn(Optional.of(new SuggestionIssueGateway.IssueRef(5, "https://github.com/org/fe/issues/5")));
		String id = submit(user.token(), "FRONTEND");

		mockMvc.perform(post("/admin/suggestions/" + id + "/approve").header("X-Session-Token", admin.token()))
			.andExpect(status().isOk());
		mockMvc.perform(post("/admin/suggestions/" + id + "/approve").header("X-Session-Token", admin.token()))
			.andExpect(status().isConflict());
		verify(gateway).publish(eq(SuggestionTarget.FRONTEND), anyString(), anyString(), anyString(), any());
	}

	@Test
	void falhaDoGithubDevolveASugestaoParaAFila() throws Exception {
		var user = new TestAuthHelper(mockMvc, objectMapper).authenticate("sgg_user5");
		var admin = new TestAuthHelper(mockMvc, objectMapper).authenticate("sgg_admin");
		when(gateway.availability(SuggestionTarget.BACKEND)).thenReturn(Availability.READY);
		when(gateway.publish(eq(SuggestionTarget.BACKEND), anyString(), anyString(), anyString(), any())).thenReturn(Optional.empty());
		String id = submit(user.token(), "BACKEND");

		mockMvc.perform(post("/admin/suggestions/" + id + "/approve").header("X-Session-Token", admin.token()))
			.andExpect(status().isBadGateway());
		mockMvc.perform(get("/suggestions/mine").header("X-Session-Token", user.token()))
			.andExpect(jsonPath("$[?(@.id == '" + id + "')].status").value("PENDING"));
	}

	@Test
	void destinoSemRepositorioNaoPublicaENaoMexeNaSugestao() throws Exception {
		var user = new TestAuthHelper(mockMvc, objectMapper).authenticate("sgg_user6");
		var admin = new TestAuthHelper(mockMvc, objectMapper).authenticate("sgg_admin");
		when(gateway.availability(SuggestionTarget.BACKEND)).thenReturn(Availability.REPOSITORY_MISSING);
		String id = submit(user.token(), "BACKEND");

		mockMvc.perform(post("/admin/suggestions/" + id + "/approve").header("X-Session-Token", admin.token()))
			.andExpect(status().isServiceUnavailable());
		mockMvc.perform(get("/suggestions/mine").header("X-Session-Token", user.token()))
			.andExpect(jsonPath("$[?(@.id == '" + id + "')].status").value("PENDING"));
	}

	@Test
	void rejeitarNaoPublicaEPessoaComumNaoModera() throws Exception {
		var user = new TestAuthHelper(mockMvc, objectMapper).authenticate("sgg_user7");
		var admin = new TestAuthHelper(mockMvc, objectMapper).authenticate("sgg_admin");
		String id = submit(user.token(), "FRONTEND");

		mockMvc.perform(post("/admin/suggestions/" + id + "/approve").header("X-Session-Token", user.token()))
			.andExpect(status().isForbidden());
		mockMvc.perform(get("/admin/suggestions").header("X-Session-Token", user.token()))
			.andExpect(status().isForbidden());

		mockMvc.perform(post("/admin/suggestions/" + id + "/reject").header("X-Session-Token", admin.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("REJECTED"));
		verify(gateway, never()).publish(eq(SuggestionTarget.FRONTEND), anyString(), anyString(), anyString(), any());
	}

	@Test
	void missingSuggestionsTokenFailsWithItsOwnCodeAndKeepsTheSuggestionPending() throws Exception {
		var user = new TestAuthHelper(mockMvc, objectMapper).authenticate("sgg_user8");
		var admin = new TestAuthHelper(mockMvc, objectMapper).authenticate("sgg_admin");
		when(gateway.availability(SuggestionTarget.BACKEND)).thenReturn(Availability.TOKEN_MISSING);
		String id = submit(user.token(), "BACKEND");

		mockMvc.perform(post("/admin/suggestions/" + id + "/approve").header("X-Session-Token", admin.token()))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("SUGGESTIONS_GITHUB_TOKEN_MISSING"));
		mockMvc.perform(get("/suggestions/mine").header("X-Session-Token", user.token()))
			.andExpect(jsonPath("$[?(@.id == '" + id + "')].status").value("PENDING"));
		verify(gateway, never()).publish(any(), anyString(), anyString(), anyString(), any());
	}

	@Test
	void approvalAfterAFailedAttemptPassesTheFirstAttemptSoTheIssueIsNotDuplicated() throws Exception {
		var user = new TestAuthHelper(mockMvc, objectMapper).authenticate("sgg_user9");
		var admin = new TestAuthHelper(mockMvc, objectMapper).authenticate("sgg_admin");
		when(gateway.availability(SuggestionTarget.BACKEND)).thenReturn(Availability.READY);
		when(gateway.publish(eq(SuggestionTarget.BACKEND), anyString(), anyString(), anyString(), isNull()))
			.thenReturn(Optional.empty());
		when(gateway.publish(eq(SuggestionTarget.BACKEND), anyString(), anyString(), anyString(), notNull()))
			.thenReturn(Optional.of(new SuggestionIssueGateway.IssueRef(11, "https://github.com/org/be/issues/11")));
		String id = submit(user.token(), "BACKEND");

		mockMvc.perform(post("/admin/suggestions/" + id + "/approve").header("X-Session-Token", admin.token()))
			.andExpect(status().isBadGateway());
		mockMvc.perform(post("/admin/suggestions/" + id + "/approve").header("X-Session-Token", admin.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.githubIssueNumber").value("11"));

		verify(gateway).publish(eq(SuggestionTarget.BACKEND), eq(id), anyString(), anyString(), isNull());
		verify(gateway).publish(eq(SuggestionTarget.BACKEND), eq(id), anyString(), anyString(), notNull());
	}

	@Test
	void dailyLimitHoldsUnderConcurrentSubmissions() throws Exception {
		var user = new TestAuthHelper(mockMvc, objectMapper).authenticate("sgg_user10");
		int attempts = 12;
		var pool = Executors.newFixedThreadPool(attempts);
		var start = new CountDownLatch(1);
		try {
			List<Future<Integer>> results = new ArrayList<>();
			for (int i = 0; i < attempts; i++) {
				// Distinct bodies: identical in-flight POSTs are collapsed by the duplicate-post filter.
				String distinct = objectMapper.writeValueAsString(Map.of("target", "BACKEND", "title",
					"Sugestão concorrente " + i, "body", "Texto distinto da sugestão concorrente número " + i));
				results.add(pool.submit(() -> {
					start.await();
					return mockMvc.perform(post("/suggestions").header("X-Session-Token", user.token())
						.contentType(MediaType.APPLICATION_JSON).content(distinct))
						.andReturn().getResponse().getStatus();
				}));
			}
			start.countDown();
			int created = 0;
			for (Future<Integer> result : results) {
				int code = result.get(30, TimeUnit.SECONDS);
				assertTrue(code == 201 || code == 429, "unexpected status " + code);
				created += code == 201 ? 1 : 0;
			}
			assertEquals(5, created);
		}
		finally {
			pool.shutdownNow();
		}
	}

	private String submit(String token, String target) throws Exception {
		var result = mockMvc.perform(post("/suggestions").header("X-Session-Token", token)
			.contentType(MediaType.APPLICATION_JSON).content(body(target)))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asString();
	}

	private String body(String target) throws Exception {
		return objectMapper.writeValueAsString(Map.of("target", target, "title", "Modo escuro nas conversas",
			"body", "Seria ótimo ter o modo escuro nas conversas. Ideia de @pessoa para a equipe avaliar."));
	}
}
