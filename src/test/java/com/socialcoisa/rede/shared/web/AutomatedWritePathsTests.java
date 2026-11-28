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
		"/webhooks/didit",
		"/blogs/descadastrar",
		"/notifications/digest/unsubscribe",
		"/calls/*/gateway/entry/*",
		"/mentoring/*/gateway/entry/*",
		"/internal/novidades-deploy",
		// Nó da rede cooperativa não tem sessão: o enroll usa o token de provisionamento e o
		// heartbeat a API key do nó, ambos conferidos no controller (fase 1 da rede distribuída).
		"/network/nodes/enroll",
		"/network/nodes/heartbeat",
		// Renovação do certificado mTLS do nó (brief §10): a credencial é a API key (X-Node-Id +
		// X-Node-Key), e o certificado que ele já tem não é exigido — quem renova pode estar com o
		// certificado a um dia de expirar.
		"/network/nodes/me/certificate",
		// Declaração de capacidade do executor (§8.2, "Health checks", V390): o nó diz o que esta
		// máquina consegue rodar, na mesma credencial de nó — não há sessão para conferir.
		"/network/nodes/me/executor-capabilities",
		// Reserva (V364): o nó confirma a própria cópia, com a mesma credencial de nó. Era o único
		// fluxo de escrita nascido do interceptor sem entrada aqui — o teste varre a lista real e
		// acusou a divergência (antes: só em PUBLIC_WRITES → agora: nos dois lugares).
		"/network/standby/complete",
		// #953: falha da cópia contada pelo nó (gravação ou restauração), mesma credencial de nó.
		"/network/standby/failed",
		// Fase 2: resultado de tarefa devolvido pelo nó, com a mesma credencial de nó.
		"/network/tasks/*/result",
		// Fase 3: nó de borda confirmando o que sincronizou e pedindo reconciliação.
		"/network/content/*/*/synced",
		"/network/content/reconcile",
		// Fase 3: entrega do arquivo transcodificado pelo nó de mídia.
		"/network/media/*/result",
		// Storage (fase 1): o nó grava o corpo de um objeto. Mesma credencial de nó e perfil
		// NODE_STORAGE conferido no controller — sem sessão de navegador, como as demais.
		"/network/storage/objects/*",
		// Storage (fase 3): a réplica confirma a cópia designada. Mesma credencial, e a confirmação
		// é conferida contra a colocação antes de virar durabilidade.
		"/network/storage/objects/*/replica",
		// Storage (fase 3, §5.6): o nó poda do próprio disco os corpos que o catálogo não atribui
		// mais a ele. Mesma credencial de nó, e o serviço só aceita o dono do disco — outro nó
		// responde 403 sem tocar em arquivo nenhum.
		"/network/storage/inventory/purge",
		// Storage (fase 3, §5.8): o nó devolve o que mediu no próprio disco -- o Control Plane só
		// pode conferir o corpo da instalação em que roda; a cópia de outro nó só o dono relê.
		"/network/storage/scrub",
		// Inscrição de nó: o formulário é público (quem oferece a máquina pode não ter conta) e o
		// link é autenticado pelo token de uso único do e-mail. Ambos limitados no controller.
		"/network/join/applications",
		"/network/join/reveal");

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
			// O gateway S3 (brief §11) não usa sessão de navegador: o SDK não tem uma. Ele
			// autentica por AWS SigV4 e, SEM assinatura, tem de recusar — e recusar com o
			// documento de erro do S3. Aceitar 403 de qualquer jeito seria passar a régua:
			// qualquer rota que devolvesse 403 "por outro motivo" entraria sem credencial.
			if (codigo != 401 && !ehGatewayS3Recusando(rota, resultado.getResponse().getContentAsString())) {
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

	/**
	 * A recusa do gateway S3 é legítima quando: o caminho é do gateway, o status é 403 (sem
	 * assinatura ou assinatura inválida) e o corpo é o XML de erro do S3. A terceira condição é a
	 * que impede a exceção de virar porta: um 403 sem o documento do gateway não é dele.
	 */
	private static boolean ehGatewayS3Recusando(Rota rota, String corpo) {
		return rota.path().startsWith("/s3") && corpo != null && corpo.contains("<Code>")
			&& (corpo.contains("AccessDenied") || corpo.contains("InvalidAccessKeyId")
				|| corpo.contains("SignatureDoesNotMatch"));
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
