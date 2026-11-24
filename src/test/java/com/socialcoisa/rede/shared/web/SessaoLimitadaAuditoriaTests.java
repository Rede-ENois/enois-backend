package com.socialcoisa.rede.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.socialcoisa.rede.identity.application.port.out.AuthSessionRepository;
import com.socialcoisa.rede.moderation.application.AdminAuthorityAudit;
import com.socialcoisa.rede.shared.sanctions.SanctionGateReader;
import com.socialcoisa.rede.shared.security.ClientIpResolver;
import com.socialcoisa.rede.shared.security.RateLimitSessions;

import org.junit.jupiter.api.Tag;
import jakarta.servlet.FilterChain;


@Tag("critico")
class SessaoLimitadaAuditoriaTests {

	private static final Instant AGORA = Instant.parse("2026-11-16T12:00:00Z");
	private final AuthSessionRepository sessoes = mock(AuthSessionRepository.class);
	private final SanctionGateReader sancoes = mock(SanctionGateReader.class);
	private final AdminAuthorityAudit auditoria = mock(AdminAuthorityAudit.class);
	private final RateLimitSessions orcamento = mock(RateLimitSessions.class);
	private final ClientIpResolver origens = mock(ClientIpResolver.class);
	private final FilterChain cadeia = mock(FilterChain.class);
	private final SessaoModeracaoFilter filtro = new SessaoModeracaoFilter(
		provedor(AuthSessionRepository.class, sessoes), provedor(SanctionGateReader.class, sancoes),
		Clock.fixed(AGORA, ZoneOffset.UTC), orcamento, origens, provedor(AdminAuthorityAudit.class, auditoria));

	@ParameterizedTest
	@ValueSource(strings = {"/admin", "/admin/users", "/admin//users", "/admin;versao=1/users",
		"/%61dmin/users", "/moderation", "/moderation/reports", "/moderation/me",
		"/moderation/applications", "/moderation;versao=1/reports"})
	void auditaUmaRecusaComTitularValidadoSemNovaConsulta(String rota) throws Exception {
		var pedido = pedidoLimitado(rota);
		recusar(pedido);
		verificarAuditoria(RequestPath.applicationPath(pedido));
		verificarConsultas();
	}

	@Test
	void auditaCaminhoNormalizadoSemPrefixoDoContexto() throws Exception {
		var pedido = pedidoLimitado("/api/%61dmin/users");
		pedido.setContextPath("/api");
		recusar(pedido);
		verificarAuditoria("/admin/users");
		verificarConsultas();
	}

	@ParameterizedTest
	@ValueSource(strings = {"/posts", "/users", "/administrator", "/moderation-public"})
	void rotasComunsRecusamSemAuditoriaAdministrativa(String rota) throws Exception {
		recusar(pedidoLimitado(rota));
		verifyNoInteractions(auditoria);
		verificarConsultas();
	}

	@Test
	void falhaDaAuditoriaPreservaRecusaSemExecutarCadeia() throws Exception {
		doThrow(new IllegalStateException("Auditoria indisponivel"))
			.when(auditoria).denied("titular-sintetico", "GET", "/admin/users", 403,
				"SESSION_RESTRICTED", "origem-sintetica");
		recusar(pedidoLimitado("/admin/users"));
		verificarAuditoria("/admin/users");
		verificarConsultas();
	}

	@Test
	void contaSuspensaMantemCodigoDeRespostaEMotivoDaAuditoria() throws Exception {
		when(sancoes.suspended("titular-sintetico")).thenReturn(true);
		var resposta = recusar(pedidoLimitado("/admin/users"));
		assertThat(resposta.getContentAsString()).contains("ACCOUNT_SUSPENDED");
		verificarAuditoria("/admin/users");
	}

	@Test
	void titularNaoValidadoNaoAlimentaContadorIndividual() throws Exception {
		var pedido = pedidoLimitado("/admin/users");
		when(sessoes.findActiveSanctionsUserId("sessao-sintetica", AGORA)).thenReturn(Optional.empty());
		recusar(pedido);
		verifyNoInteractions(auditoria, sancoes);
	}

	@Test
	void sessaoNormalSegueParaGuardaAdministrativa() throws Exception {
		var pedido = pedidoLimitado("/admin/users");
		when(sessoes.isSanctionsOnly("sessao-sintetica", AGORA)).thenReturn(false);
		var resposta = new MockHttpServletResponse();
		filtro.doFilter(pedido, resposta, cadeia);
		verify(cadeia).doFilter(pedido, resposta);
		verifyNoInteractions(auditoria, sancoes);
	}

	private MockHttpServletRequest pedidoLimitado(String rota) {
		var pedido = new MockHttpServletRequest("GET", rota);
		pedido.addHeader("X-Session-Token", "sessao-sintetica");
		when(origens.resolve(any())).thenReturn("origem-sintetica");
		when(orcamento.prepareScopeLookup("sessao-sintetica", "origem-sintetica")).thenReturn(true);
		when(sessoes.isSanctionsOnly("sessao-sintetica", AGORA)).thenReturn(true);
		when(sessoes.findActiveSanctionsUserId("sessao-sintetica", AGORA))
			.thenReturn(Optional.of("titular-sintetico"));
		return pedido;
	}

	private MockHttpServletResponse recusar(MockHttpServletRequest pedido) throws Exception {
		var resposta = new MockHttpServletResponse();
		filtro.doFilter(pedido, resposta, cadeia);
		assertThat(resposta.getStatus()).isEqualTo(403);
		verifyNoInteractions(cadeia);
		return resposta;
	}

	private void verificarAuditoria(String rota) {
		verify(auditoria).denied("titular-sintetico", "GET", rota, 403,
			"SESSION_RESTRICTED", "origem-sintetica");
		verifyNoMoreInteractions(auditoria);
	}

	private void verificarConsultas() {
		verify(sessoes).isSanctionsOnly("sessao-sintetica", AGORA);
		verify(sessoes).findActiveSanctionsUserId("sessao-sintetica", AGORA);
		verifyNoMoreInteractions(sessoes);
	}

	private static <T> ObjectProvider<T> provedor(Class<T> tipo, T componente) {
		return new StaticListableBeanFactory(Map.of("componente", componente)).getBeanProvider(tipo);
	}
}
