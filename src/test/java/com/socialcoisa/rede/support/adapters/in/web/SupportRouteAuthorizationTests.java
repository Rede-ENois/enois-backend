package com.socialcoisa.rede.support.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;

import com.socialcoisa.rede.shared.web.AdminGuard;
import com.socialcoisa.rede.shared.web.ModeratorGuard;
import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestDatabase;
import org.junit.jupiter.api.Tag;
import tools.jackson.databind.ObjectMapper;

@Tag("critico")
@SpringBootTest
@AutoConfigureMockMvc
class SupportRouteAuthorizationTests {
	@Autowired MockMvc mockMvc;
	@Autowired ObjectMapper objectMapper;
	@MockitoSpyBean SupportController controller;
	@MockitoSpyBean AdminGuard adminGuard;
	@MockitoSpyBean ModeratorGuard moderatorGuard;
	@MockitoSpyBean(name = "adminRouteInterceptor") HandlerInterceptor adminInterceptor;

	@BeforeEach
	void resetObservation() {
		clearInvocations(controller, adminGuard, moderatorGuard, adminInterceptor);
	}

	@Test
	void semSessaoNaoExecutaControllerComParametrosDeMatriz() throws Exception {
		mockMvc.perform(get(URI.create("/admin;v=1/support/github")))
			.andExpect(status().isUnauthorized());
		verifyNoInteractions(controller);
	}

	@Test
	void usuarioComumNaoExecutaLeituraOuEscritaProtegida() throws Exception {
		var user = new TestAuthHelper(mockMvc, objectMapper).authenticate("route_policy_plain");
		clearInvocations(controller, adminGuard, moderatorGuard, adminInterceptor);
		for (String path : List.of("/admin/support/github", "/%61dmin/support/github",
				"/admin;v=1/support/github", "/admin/support/github;v=1")) {
			mockMvc.perform(get(URI.create(path)).header("X-Session-Token", user.token()))
				.andExpect(status().isForbidden());
			mockMvc.perform(put(URI.create(path)).header("X-Session-Token", user.token())
					.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isForbidden());
		}
		verifyNoInteractions(controller);
	}

	@Test
	void handlerExclusivoDeAdminUsaGuardaDeAdmin() throws Exception {
		var user = new TestAuthHelper(mockMvc, objectMapper).authenticate("route_policy_role");
		clearInvocations(controller, adminGuard, moderatorGuard, adminInterceptor);
		mockMvc.perform(get(URI.create("/admin/support/github;v=1"))
				.header("X-Session-Token", user.token()))
			.andExpect(status().isForbidden());
		verify(adminGuard).requireAdmin(any());
		verify(moderatorGuard, never()).requireModerator(any());
		verifyNoInteractions(controller);
	}

	@Test
	void cadeiaRealBloqueiaAntesDoControllerInclusiveComContexto() throws Exception {
		var user = new TestAuthHelper(mockMvc, objectMapper).authenticate("route_policy_chain");
		for (String context : List.of("", "/app")) {
			for (String route : List.of("/admin/support/github", "/%61dmin/support/github",
					"/admin/%73upport/github", "/admin;v=1/support/github", "/admin/support/github;v=1")) {
				for (String session : List.of("", user.token())) {
					for (boolean write : List.of(false, true)) {
						clearInvocations(controller, adminGuard, moderatorGuard, adminInterceptor);
						String receivedPath = context + route;
						var request = write ? put(URI.create(receivedPath)) : get(URI.create(receivedPath));
						request.contextPath(context);
						if (!session.isEmpty()) request.header("X-Session-Token", session);
						if (write) request.contentType(MediaType.APPLICATION_JSON).content("{}");
						var result = mockMvc.perform(request)
							.andExpect(session.isEmpty() ? status().isUnauthorized() : status().isForbidden())
							.andReturn();
						assertThat(result.getRequest().getRequestURI()).isEqualTo(receivedPath);
						assertThat(result.getRequest().getContextPath()).isEqualTo(context);
						assertThat(result.getHandler()).isInstanceOf(HandlerMethod.class);
						var handler = (HandlerMethod) result.getHandler();
						assertThat(handler.getBeanType()).isEqualTo(SupportController.class);
						assertThat(handler.getMethod().getName())
							.isEqualTo(write ? "updateGithubSettings" : "githubSettings");
						verify(adminInterceptor).preHandle(any(), any(), any());
						if (session.isEmpty()) verifyNoInteractions(adminGuard, moderatorGuard);
						else verify(adminGuard).requireAdmin(any());
						verify(moderatorGuard, never()).requireModerator(any());
						verifyNoInteractions(controller);
					}
				}
			}
		}
	}

	@Test
	void dadosCodificadosESegmentosDePontoNaoExecutamOperacaoAdministrativa() throws Exception {
		var user = new TestAuthHelper(mockMvc, objectMapper).authenticate("route_policy_unmapped");
		for (String path : List.of("/admin%2Fsupport/github", "/admin%252Fsupport/github",
				"/%2561dmin/support/github", "/admin/x/%2e%2e/support/github")) {
			for (String session : List.of("", user.token())) {
				clearInvocations(controller, adminGuard, moderatorGuard, adminInterceptor);
				var request = put(URI.create(path)).contentType(MediaType.APPLICATION_JSON).content("{}");
				if (!session.isEmpty()) request.header("X-Session-Token", session);
				var result = mockMvc.perform(request).andReturn();
				assertThat(result.getRequest().getRequestURI()).isEqualTo(path);
				// MockMvc não aplica a recusa de barras codificadas do Tomcat.
				if (path.equals("/admin%2Fsupport/github") || path.equals("/%2561dmin/support/github")) {
					assertThat(result.getHandler()).as(path).isInstanceOf(ResourceHttpRequestHandler.class);
					assertThat(result.getResponse().getStatus()).as(path).isEqualTo(session.isEmpty() ? 401 : 404);
					if (!session.isEmpty()) {
						assertThat(result.getResolvedException()).as(path).isInstanceOf(NoResourceFoundException.class);
					}
				}
				else {
					// O 200 vem do filtro de fallback, sem selecionar um handler MVC.
					assertThat(result.getHandler()).as(path).isNull();
					assertThat(result.getResponse().getStatus()).as(path).isEqualTo(200);
					assertThat(result.getResponse().getContentType()).as(path).startsWith("text/html");
					assertThat(result.getResponse().getContentAsString()).as(path)
						.contains("<title>Control Panel</title>");
				}
				verify(adminInterceptor, never()).preHandle(any(), any(), any());
				verifyNoInteractions(adminGuard, moderatorGuard);
				verifyNoInteractions(controller);
			}
		}
	}

	@Test
	void administradorDaFixtureExecutaLeituraProtegidaComMesmoRoteamento() throws Exception {
		var admin = new TestAuthHelper(mockMvc, objectMapper).authenticate("route_policy_positive");
		TestDatabase.setAdmin(admin.userId());
		for (String context : List.of("", "/app")) {
			for (String route : List.of("/admin/support/github", "/%61dmin/support/github",
					"/admin;v=1/support/github", "/admin/support/github;v=1")) {
				clearInvocations(controller, adminGuard, moderatorGuard, adminInterceptor);
				var result = mockMvc.perform(get(URI.create(context + route)).contextPath(context)
						.header("X-Session-Token", admin.token()))
					.andExpect(status().isOk()).andReturn();
				assertThat(result.getRequest().getRequestURI()).isEqualTo(context + route);
				assertThat(((HandlerMethod) result.getHandler()).getMethod().getName()).isEqualTo("githubSettings");
				var authorization = inOrder(adminInterceptor, adminGuard, controller);
				authorization.verify(adminInterceptor).preHandle(any(), any(), any());
				authorization.verify(adminGuard).requireAdmin(argThat(user -> user.id().value().equals(admin.userId())));
				authorization.verify(controller).githubSettings(any());
				authorization.verify(adminGuard).requireAdmin(argThat(user -> user.id().value().equals(admin.userId())));
				verify(moderatorGuard, never()).requireModerator(any());
			}
		}
	}
}
