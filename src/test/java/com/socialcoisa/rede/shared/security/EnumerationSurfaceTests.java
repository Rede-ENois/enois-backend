package com.socialcoisa.rede.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * A superfície de enumeração: quais rotas contam, qual identificador cada uma expõe e quais são
 * quentes da tela (só volume, nunca enumeração).
 *
 * <p>As rotas aqui são as que a produção mais chama — a lista veio do tráfego real do QA/produção
 * (`/conversations/unread-count`, `/conversations`, `/conversations/stream`, `/calls/{callId}`,
 * `/events/{eventId}/calls/open`, `/users/{username}/presence`, `/me/presence/heartbeat`,
 * `/actuator/prometheus`, `/feed`). As quentes precisam ficar fora da conta de enumeração, senão
 * abrir o app uma vez já pontua.</p>
 */
class EnumerationSurfaceTests {

	@Test
	void rotasDeDescobertaDePessoaExpoemOHandle() {
		var route = EnumerationSurface.match("/users/joao-silva");
		assertThat(route).isNotNull();
		assertThat(route.kind()).isEqualTo(EnumerationSurface.Kind.USERNAME);
		assertThat(EnumerationSurface.identifier(route, "/users/joao-silva")).isEqualTo("joao-silva");

		var presence = EnumerationSurface.match("/users/joao-silva/presence");
		assertThat(presence.kind()).isEqualTo(EnumerationSurface.Kind.USERNAME);
		assertThat(EnumerationSurface.identifier(presence, "/users/joao-silva/presence")).isEqualTo("joao-silva");
	}

	@Test
	void rotasDeRecursoExpoemOId() {
		assertThat(EnumerationSurface.identifier(EnumerationSurface.match("/calls/call_123"), "/calls/call_123"))
			.isEqualTo("call_123");
		assertThat(EnumerationSurface.identifier(
			EnumerationSurface.match("/events/evt_9/calls/open"), "/events/evt_9/calls/open")).isEqualTo("evt_9");
		assertThat(EnumerationSurface.identifier(
			EnumerationSurface.match("/conversations/conv_7"), "/conversations/conv_7")).isEqualTo("conv_7");
	}

	/** As rotas que a tela chama o tempo todo: contam volume, nunca enumeração. */
	@Test
	void rotasQuentesDaTelaSaoShared() {
		for (String path : new String[] { "/conversations", "/conversations/unread-count",
				"/conversations/stream", "/me/presence/heartbeat", "/feed", "/actuator/prometheus" }) {
			var route = EnumerationSurface.match(path);
			assertThat(route).as(path).isNotNull();
			assertThat(route.kind()).as(path).isEqualTo(EnumerationSurface.Kind.SHARED);
		}
	}

	@Test
	void rotaForaDaSuperficieNaoCasa() {
		assertThat(EnumerationSurface.match("/posts")).isNull();
		assertThat(EnumerationSurface.match("/health")).isNull();
		assertThat(EnumerationSurface.match("/storage/uploads/x")).isNull();
	}

	@Test
	void autenticacaoEAdministracaoEntramNaSuperficie() {
		assertThat(EnumerationSurface.match("/auth/login")).isNotNull();
		assertThat(EnumerationSurface.match("/auth/password/forgot")).isNotNull();
		assertThat(EnumerationSurface.match("/admin/reports")).isNotNull();
	}
}
