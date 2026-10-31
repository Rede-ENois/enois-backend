package com.socialcoisa.rede.suggestions.adapters.out.github;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.suggestions.domain.SuggestionTarget;
import com.socialcoisa.rede.support.application.SupportGithubConfig;

import tools.jackson.databind.json.JsonMapper;

/**
 * O token das sugestões é o mesmo das issues do suporte e é lido a cada chamada: a troca pelo
 * painel vale sem reiniciar. Cada destino só liga com repositório válido.
 */
class SuggestionIssueClientTests {

	private final SupportGithubConfig supportGithub = mock(SupportGithubConfig.class);

	private SuggestionIssueClient client(String backend, String frontend, String designSystem) {
		return new SuggestionIssueClient(JsonMapper.builder().build(), supportGithub, "https://api.github.com",
			backend, frontend, designSystem);
	}

	@Test
	void ligaSoOsDestinosComRepositorioQuandoHaTokenDoSuporte() {
		when(supportGithub.token()).thenReturn("token-do-suporte");
		var client = client("org/backend", "", "org/design-system");

		assertTrue(client.enabled(SuggestionTarget.BACKEND));
		assertFalse(client.enabled(SuggestionTarget.FRONTEND));
		assertTrue(client.enabled(SuggestionTarget.DESIGN_SYSTEM));
	}

	@Test
	void semTokenDoSuporteTodosOsDestinosFicamDesligados() {
		when(supportGithub.token()).thenReturn("");
		var client = client("org/backend", "org/frontend", "org/design-system");

		for (SuggestionTarget target : SuggestionTarget.values()) {
			assertFalse(client.enabled(target));
		}
	}

	@Test
	void tokenNuloTambemDesliga() {
		when(supportGithub.token()).thenReturn(null);

		assertFalse(client("org/backend", "org/frontend", "org/design-system").enabled(SuggestionTarget.BACKEND));
	}

	@Test
	void repositorioForaDoFormatoDesligaODestino() {
		when(supportGithub.token()).thenReturn("token-do-suporte");
		var client = client("https://github.com/org/backend", "org", "org/design system");

		for (SuggestionTarget target : SuggestionTarget.values()) {
			assertFalse(client.enabled(target));
		}
	}

	@Test
	void trocarOTokenNoPainelValeNaProximaChamadaSemReiniciar() {
		var client = client("org/backend", "", "");

		when(supportGithub.token()).thenReturn("");
		assertFalse(client.enabled(SuggestionTarget.BACKEND));

		when(supportGithub.token()).thenReturn("token-novo");
		assertTrue(client.enabled(SuggestionTarget.BACKEND));
	}
}
