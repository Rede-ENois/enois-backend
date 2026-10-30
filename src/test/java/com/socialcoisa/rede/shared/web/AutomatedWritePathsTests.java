package com.socialcoisa.rede.shared.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * "Automacao social por caminho alternativo" — o ultimo cenario adversarial que faltava no
 * plano (BACKLOG, item de CI de seguranca).
 *
 * A proibicao e "proibido automatizar a criacao/publicacao de posts (IA/bots/integracoes/
 * endpoints alternativos/tarefas agendadas/webhooks)". Isso so e garantido se NAO existir
 * caminho de escrita sem sessao: nenhum bot, webhook ou integracao consegue publicar, comentar
 * ou reagir. A garantia esta no {@link WriteRouteInterceptor} (fail-closed: toda escrita exige
 * sessao, menos os fluxos publicos de entrada).
 *
 * Este teste varre a lista REAL de rotas de escrita do backend (extraida do proprio codigo
 * fonte, nao de uma lista escrita a mao que envelhece) e exige 401 em todas, exceto os fluxos
 * publicos. Alem disso, compara a lista de fluxos publicos do interceptor com o catalogo
 * esperado: se alguem abrir mais um fluxo publico sem querer, o teste quebra.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AutomatedWritePathsTests {

	/** Os fluxos que existem justamente para quem ainda nao tem sessao. */
	private static final List<String> FLUXOS_PUBLICOS_ESPERADOS = List.of(
		"/auth/signup",
		"/auth/login",
		"/auth/refresh",
		"/auth/logout",
		"/auth/email/verify",
		"/auth/email/resend",
		"/auth/password/forgot",
		"/auth/password/reset",
		"/auth/2fa/verify",
		"/auth/2fa/resend",
		"/auth/2fa/email/request",
		"/auth/2fa/email/confirm",
		"/contributors/applications",
		"/auth/oauth/*/callback",
		"/auth/oauth/*/token",
		"/games/tokens/validate",
		"/webhooks/github",
		"/blogs/descadastrar",
		"/notifications/digest/unsubscribe");

	private static final Pattern MAPPING = Pattern
		.compile("@(Get|Post|Put|Patch|Delete)Mapping(?:\\(\\s*(?:value\\s*=\\s*)?\"([^\"]*)\"[^)]*\\))?");
	private static final Pattern BASE = Pattern.compile("@RequestMapping\\(\"([^\"]*)\"\\)");

	@Autowired
	private MockMvc mockMvc;

	@Test
	void nenhumaRotaDeEscritaAceitaSessaoAusente() throws Exception {
		List<Rota> rotas = rotasDeEscrita();
		org.assertj.core.api.Assertions.assertThat(rotas).as("rotas de escrita extraidas do codigo").isNotEmpty();

		List<String> atravessadas = new ArrayList<>();
		for (Rota rota : rotas) {
			if (ehFluxoPublico(rota.path())) {
				continue;
			}
			RequestBuilder requisicao = requisicao(rota);
			var resultado = mockMvc.perform(requisicao).andReturn();
			int codigo = resultado.getResponse().getStatus();
			if (codigo != 401) {
				atravessadas.add(rota.verb() + " " + rota.path() + " -> " + codigo);
			}
		}
		org.assertj.core.api.Assertions.assertThat(atravessadas)
			.as("rotas de escrita que aceitaram chamada sem sessao")
			.isEmpty();
	}

	@Test
	void osFluxosPublicosDeEscritaSaoOsConhecidos() {
		// Se a lista de fluxos publicos do interceptor crescer, alguem abriu um caminho de
		// automacao sem querer. Este teste e a trava.
		org.assertj.core.api.Assertions.assertThat(WriteRouteInterceptor.PUBLIC_WRITES)
			.as("fluxos publicos de escrita")
			.containsExactlyInAnyOrderElementsOf(FLUXOS_PUBLICOS_ESPERADOS);
	}

	private boolean ehFluxoPublico(String path) {
		for (String padrao : WriteRouteInterceptor.PUBLIC_WRITES) {
			if (casa(padrao, path)) {
				return true;
			}
		}
		return false;
	}

	/** Casa padrao com um unico "*" (um segmento inteiro). */
	private boolean casa(String padrao, String path) {
		String[] partes = padrao.split("/");
		String[] alvo = path.split("/");
		if (partes.length != alvo.length) {
			return false;
		}
		for (int i = 0; i < partes.length; i++) {
			if (!"*".equals(partes[i]) && !partes[i].equals(alvo[i])) {
				return false;
			}
		}
		return true;
	}

	private RequestBuilder requisicao(Rota rota) {
		String caminho = rota.path().replace("{", "").replace("}", "");
		if (rota.multipart()) {
			// The flag is per file: a PUT in a controller that also has an upload must keep its verb.
			return multipart(HttpMethod.valueOf(rota.verb()), caminho)
				.file(new MockMultipartFile("file", "perfil.zip", "application/zip", new byte[] { 1, 2, 3 }));
		}
		MockHttpServletRequestBuilder requisicao = switch (rota.verb()) {
			case "POST" -> post(caminho);
			case "PUT" -> put(caminho);
			case "PATCH" -> patch(caminho);
			case "DELETE" -> delete(caminho);
			default -> post(caminho);
		};
		return requisicao.contentType(MediaType.APPLICATION_JSON).content("{}");
	}

	/**
	 * Extrai as rotas de escrita do proprio codigo fonte: se uma rota nova nascer, ela entra na
	 * varredura sozinha (uma lista escrita a mao envelhecia e a prova ficava pela metade).
	 */
	private List<Rota> rotasDeEscrita() throws Exception {
		Path raiz = Path.of("src", "main", "java");
		org.assertj.core.api.Assertions.assertThat(Files.isDirectory(raiz))
			.as("codigo fonte disponivel para a varredura (CWD do teste)")
			.isTrue();

		List<Rota> rotas = new ArrayList<>();
		try (var caminhos = Files.walk(raiz)) {
			for (Path arquivo : caminhos.filter(p -> p.toString().endsWith(".java")).toList()) {
				String texto = Files.readString(arquivo);
				Matcher base = BASE.matcher(texto);
				String prefixo = base.find() ? base.group(1) : "";
				Matcher mapping = MAPPING.matcher(texto);
				while (mapping.find()) {
					String verb = mapping.group(1).toUpperCase(Locale.ROOT);
					if (verb.equals("GET")) {
						continue;
					}
					String sub = mapping.group(2) == null ? "" : mapping.group(2);
					String caminho = ((prefixo + "/" + sub).replace("//", "/")).replaceAll("/+$", "");
					if (caminho.isEmpty()) {
						caminho = "/";
					}
					rotas.add(new Rota(verb, caminho, texto.contains("MULTIPART_FORM_DATA_VALUE")));
				}
			}
		}
		return rotas.stream().distinct().toList();
	}

	/**
	 * @param multipart o endpoint consome multipart: o corpo precisa ser montado nesse formato,
	 * porque o parse do upload acontece ANTES do interceptor (mandar JSON faria o container
	 * estourar 500 e a prova de autorizacao nem chegaria a ser testada).
	 */
	private record Rota(String verb, String path, boolean multipart) {
	}
}
