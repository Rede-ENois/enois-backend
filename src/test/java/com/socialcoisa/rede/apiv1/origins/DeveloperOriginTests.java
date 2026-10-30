package com.socialcoisa.rede.apiv1.origins;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * Gate de origem em TODAS as rotas: navegador manda Origin, servidor manda X-Client-Origin.
 * A origem precisa estar na allowlist do app ou cadastrada em /developers/origins.
 *
 * O resto da suíte roda com `rede.origins.enforce=false` (ver src/test/resources/application.properties),
 * porque o MockMvc não simula um navegador; aqui a enforcement é ligada explicitamente.
 */
@SpringBootTest(properties = {
		"rede.origins.enforce=true",
		"rede.origins.suspicious.max-failures=3",
		"rede.origins.suspicious.window-seconds=60"
})
@AutoConfigureMockMvc
class DeveloperOriginTests {

	private static final String APP_ORIGIN = "https://enois.ia.br";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void everyNonExemptRequestNeedsARegisteredOrigin() throws Exception {
		var user = new TestAuthHelper(mockMvc, objectMapper).authenticate("origin_user");

		// Sem Origin e sem X-Client-Origin: recusado antes de chegar ao controller.
		mockMvc.perform(get("/developers/origins").header("X-Session-Token", user.token()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ORIGIN_REQUIRED"));

		// Servidor (SSR/curl) declara a origem do próprio app.
		mockMvc.perform(get("/developers/origins")
				.header("X-Session-Token", user.token())
				.header("X-Client-Origin", APP_ORIGIN))
			.andExpect(status().isOk());

		// Origem não cadastrada, em qualquer rota, é recusada.
		mockMvc.perform(get("/developers/origins")
				.header("X-Session-Token", user.token())
				.header("Origin", "https://evil.example"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ORIGIN_NOT_ALLOWED"));

		// Um X-Client-Origin forjado NÃO substitui um Origin presente (o navegador é a autoridade).
		mockMvc.perform(get("/developers/origins")
				.header("X-Session-Token", user.token())
				.header("Origin", "https://evil.example")
				.header("X-Client-Origin", APP_ORIGIN))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ORIGIN_NOT_ALLOWED"));

		// Fluxos de infraestrutura/terceiros seguem sem origem.
		mockMvc.perform(get("/version")).andExpect(status().isOk());
		mockMvc.perform(get("/health")).andExpect(status().isOk());
		mockMvc.perform(post("/webhooks/github")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
			.andExpect(status().isServiceUnavailable());
		// Token inválido responde 400 pelo próprio controller — o que importa é não ser o 403 do gate.
		mockMvc.perform(get("/blogs/descadastrar").param("token", "invalido"))
			.andExpect(status().isBadRequest());
		mockMvc.perform(get("/media/nao-existe")).andExpect(status().isNotFound());
		mockMvc.perform(get("/pages/nao-existe")).andExpect(status().isNotFound());
		// Figurinha: o `<img>` do texto e da grade não manda Origin (provedor inválido = 404, sem rede).
		mockMvc.perform(get("/emotes/image/XYZ/1")).andExpect(status().isNotFound());
		// ...mas as rotas de DADOS do catálogo continuam exigindo origem.
		mockMvc.perform(get("/emotes").param("page", "1"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ORIGIN_REQUIRED"));
		mockMvc.perform(get("/emotes/index"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ORIGIN_REQUIRED"));
	}

	@Test
	void registeredOriginGetsCorsAndInvalidOriginsAreRejected() throws Exception {
		var user = new TestAuthHelper(mockMvc, objectMapper).authenticate("origin_cors_user");
		String session = user.token();

		mockMvc.perform(post("/developers/origins")
				.header("X-Session-Token", session)
				.header("X-Client-Origin", APP_ORIGIN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"origin\":\"http://app.example\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_ORIGIN"));

		MvcResult created = mockMvc.perform(post("/developers/origins")
				.header("X-Session-Token", session)
				.header("X-Client-Origin", APP_ORIGIN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"origin\":\"https://app.example/\"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.origin").value("https://app.example"))
			.andReturn();
		String id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asString();

		mockMvc.perform(options("/v1")
				.header("Origin", "https://app.example")
				.header("Access-Control-Request-Method", "GET")
				.header("Access-Control-Request-Headers", "X-Session-Token"))
			.andExpect(status().isNoContent())
			.andExpect(header().string("Access-Control-Allow-Origin", "https://app.example"));

		// Preflight é sempre respondido, mesmo de origem não cadastrada: ele não executa nada nem
		// devolve dado, e recusá-lo só vira falha de CORS no navegador. Quem continua barrado é a
		// operação real (o GET logo abaixo).
		mockMvc.perform(options("/v1")
				.header("Origin", "https://evil.example")
				.header("Access-Control-Request-Method", "GET"))
			.andExpect(status().isNoContent());

		mockMvc.perform(get("/v1").header("Origin", "https://evil.example"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ORIGIN_NOT_ALLOWED"));

		// GET de navegador da origem cadastrada: liberado e com CORS na resposta.
		mockMvc.perform(get("/v1").header("Origin", "https://app.example"))
			.andExpect(status().isOk())
			.andExpect(header().string("Access-Control-Allow-Origin", "https://app.example"));

		// Cliente de servidor da origem cadastrada: liberado sem cabeçalho de CORS.
		mockMvc.perform(get("/v1").header("X-Client-Origin", "https://app.example"))
			.andExpect(status().isOk());

		// Sem nenhuma origem, nem a API pública passa.
		mockMvc.perform(get("/v1"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ORIGIN_REQUIRED"));

		mockMvc.perform(delete("/developers/origins/" + id)
				.header("X-Session-Token", session)
				.header("X-Client-Origin", APP_ORIGIN))
			.andExpect(status().isOk());
	}

	@Test
	void adminAndModerationOnlyAcceptTheFrontendOrigin() throws Exception {
		var user = new TestAuthHelper(mockMvc, objectMapper).authenticate("origin_admin_user");
		MvcResult created = mockMvc.perform(post("/developers/origins")
				.header("X-Session-Token", user.token())
				.header("X-Client-Origin", APP_ORIGIN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"origin\":\"https://terceiro.example\"}"))
			.andExpect(status().isCreated())
			.andReturn();
		String id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asString();

		// Origem de terceiro, mesmo cadastrada e ativa, não entra em admin nem moderação.
		mockMvc.perform(get("/admin/support").header("Origin", "https://terceiro.example"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ORIGIN_NOT_TRUSTED"));

		mockMvc.perform(get("/moderation/me").header("X-Client-Origin", "https://terceiro.example"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ORIGIN_NOT_TRUSTED"));

		// Caminho percent-encoded não pode escapar da regra de admin (URI crua, sem o template
		// do MockMvc reencodar o "%").
		// Cada caminho usa uma origem propria para o limite de abuso nao mascarar o guard.
		for (String path : java.util.List.of("/%61dmin/support", "/admin/%73upport",
				"/%6doderation/me", "/api/%61dmin/support",
				"/admin;v=1/support/github", "/admin/support/github;v=1")) {
			String origin = "https://encoded-" + Integer.toHexString(path.hashCode()) + ".example";
			MvcResult registered = mockMvc.perform(post("/developers/origins")
					.header("X-Session-Token", user.token())
					.header("X-Client-Origin", APP_ORIGIN)
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(java.util.Map.of("origin", origin))))
				.andExpect(status().isCreated()).andReturn();
			String registeredId = objectMapper.readTree(registered.getResponse().getContentAsString())
				.get("id").asString();
			var request = get(java.net.URI.create(path));
			if (path.startsWith("/api/")) {
				request.contextPath("/api");
			}
			mockMvc.perform(request.header("Origin", origin))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ORIGIN_NOT_TRUSTED"));
			mockMvc.perform(delete("/developers/origins/" + registeredId)
					.header("X-Session-Token", user.token())
					.header("X-Client-Origin", APP_ORIGIN))
				.andExpect(status().isOk());
		}

		// Origem do frontend passa o gate (a recusa seguinte é a falta de sessão, não a origem).
		mockMvc.perform(get("/admin/support").header("X-Client-Origin", APP_ORIGIN))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(get(java.net.URI.create("/%61dmin/support"))
				.header("X-Client-Origin", APP_ORIGIN))
			.andExpect(status().isUnauthorized());

		// Curinga confiável de subdomínio.
		mockMvc.perform(get("/admin/support").header("X-Client-Origin", "https://painel.b7cloud.com.br"))
			.andExpect(status().isUnauthorized());

		// O domínio "raiz", sem subdomínio, não casa com https://*.b7cloud.com.br.
		mockMvc.perform(get("/admin/support").header("X-Client-Origin", "https://b7cloud.com.br"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ORIGIN_NOT_ALLOWED"));

		mockMvc.perform(delete("/developers/origins/" + id)
				.header("X-Session-Token", user.token())
				.header("X-Client-Origin", APP_ORIGIN))
			.andExpect(status().isOk());
	}

	@Test
	void adminCanBlockAndUnblockAnOrigin() throws Exception {
		var owner = new TestAuthHelper(mockMvc, objectMapper).authenticate("origin_admin_target");
		var admin = new TestAuthHelper(mockMvc, objectMapper).authenticate("group-admin");
		MvcResult created = mockMvc.perform(post("/developers/origins")
				.header("X-Session-Token", owner.token())
				.header("X-Client-Origin", APP_ORIGIN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"origin\":\"https://manual.example\"}"))
			.andExpect(status().isCreated())
			.andReturn();
		String id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asString();

		mockMvc.perform(post("/admin/developers/origins/{id}/block", id)
				.header("X-Session-Token", admin.token())
				.header("X-Client-Origin", APP_ORIGIN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"reason\":\"teste de bloqueio manual\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.blocked").value(true));

		mockMvc.perform(get("/v1").header("Origin", "https://manual.example"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ORIGIN_BLOCKED"));

		mockMvc.perform(post("/admin/developers/origins/{id}/unblock", id)
				.header("X-Session-Token", admin.token())
				.header("X-Client-Origin", APP_ORIGIN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.blocked").value(false));

		mockMvc.perform(get("/v1").header("Origin", "https://manual.example"))
			.andExpect(status().isOk());

		mockMvc.perform(delete("/developers/origins/" + id)
				.header("X-Session-Token", owner.token())
				.header("X-Client-Origin", APP_ORIGIN))
			.andExpect(status().isOk());
	}

	@Test
	void suspiciousOriginIsBlockedAutomatically() throws Exception {
		var user = new TestAuthHelper(mockMvc, objectMapper).authenticate("origin_abuse_user");
		MvcResult created = mockMvc.perform(post("/developers/origins")
				.header("X-Session-Token", user.token())
				.header("X-Client-Origin", APP_ORIGIN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"origin\":\"https://abuso.example\"}"))
			.andExpect(status().isCreated())
			.andReturn();
		String id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asString();

		// Sem sessão: cada tentativa responde 401/403 e conta para a reputação da origem.
		for (int i = 0; i < 3; i++) {
			mockMvc.perform(get("/developers/origins").header("Origin", "https://abuso.example"))
				.andExpect(status().is4xxClientError());
		}

		// Limite de 3 recusas (configurado no @SpringBootTest): a origem cai.
		mockMvc.perform(get("/v1").header("Origin", "https://abuso.example"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ORIGIN_BLOCKED"));

		mockMvc.perform(get("/developers/origins")
				.header("X-Session-Token", user.token())
				.header("X-Client-Origin", APP_ORIGIN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].blocked").value(true));

		mockMvc.perform(delete("/developers/origins/" + id)
				.header("X-Session-Token", user.token())
				.header("X-Client-Origin", APP_ORIGIN))
			.andExpect(status().isOk());
	}
}
