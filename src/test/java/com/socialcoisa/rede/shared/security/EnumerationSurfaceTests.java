package com.socialcoisa.rede.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * A superfície monitorada só aponta rotas que existem: linha para rota removida vira falso sinal de
 * varredura no painel de enumeração.
 */
class EnumerationSurfaceTests {

	private String labelOf(String path) {
		EnumerationSurface.Route route = EnumerationSurface.match(path);
		return route == null ? null : route.label();
	}

	@Test
	void rotasRemovidasDeDisponibilidadeDeContaNaoSaemMaisDaSuperficie() {
		assertThat(labelOf("/auth/username-available")).isNull();
		assertThat(labelOf("/auth/email-available")).isNull();
	}

	@Test
	void rotasDeAutenticacaoQueExistemContinuamMonitoradas() {
		assertThat(labelOf("/auth/signup")).isEqualTo("auth.signup");
		assertThat(labelOf("/auth/login")).isEqualTo("auth.login");
		assertThat(labelOf("/auth/password/forgot")).isEqualTo("auth.forgot");
	}
}