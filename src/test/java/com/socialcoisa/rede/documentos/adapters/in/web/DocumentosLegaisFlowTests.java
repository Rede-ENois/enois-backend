package com.socialcoisa.rede.documentos.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.socialcoisa.rede.identity.application.SignupCommand;
import com.socialcoisa.rede.identity.application.port.in.SignupUseCase;
import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestDatabase;

/**
 * Documentos legais de ponta a ponta. O que este teste protege:
 *
 * <ul>
 * <li>rascunho existe mas NÃO entra em vigor — e não pode ser aceito;</li>
 * <li>publicar é o único ato que põe texto em vigor, e a versão anterior sai de vigor sem sair do
 * histórico;</li>
 * <li>aceite só vale para a versão em vigor, exige sessão e é idempotente;</li>
 * <li>versão nova publicada faz a pessoa voltar a ter pendência;</li>
 * <li>a caixa dos Termos no cadastro grava o aceite da versão em vigor no instante em que a conta
 * nasce — e, sem versão em vigor, não inventa aceite.</li>
 * </ul>
 *
 * <p>O banco de teste é compartilhado entre as classes e publicar é global: cada cenário publica
 * as próprias versões e conta a partir da versão em vigor que encontrar.</p>
 */
@SpringBootTest(properties = "rede.moderation.moderator-min-reputation=-1")
@AutoConfigureMockMvc
class DocumentosLegaisFlowTests {

	private static final String DIRETRIZES = "DIRETRIZES";
	private static final String TERMOS = "TERMOS_DE_USO";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;


	@Autowired
	private SignupUseCase signup;

	private TestAuthHelper authHelper() {
		return new TestAuthHelper(mockMvc, objectMapper);
	}

	@BeforeEach
	void adminDaEquipe() throws Exception {
		authHelper().authenticate("group-admin");
		TestDatabase.setAdmin(TestDatabase.userIdByEmail("group-admin@test.com").orElseThrow());
	}

	@Test
	void rascunhoNaoVigoraEPublicarTrocaAVigencia() throws Exception {
		String admin = adminToken();
		String primeira = criarRascunho(admin, TERMOS, "termos");
		String segunda = criarRascunho(admin, TERMOS, "termos revisados");

		// Rascunho: aprovado=false, não em vigor — e o aceite é recusado.
		mockMvc.perform(get("/documentos/" + TERMOS + "?versao=" + versao(primeira)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.documento.aprovado").value(false));
		mockMvc.perform(post("/documentos/" + primeira + "/aceite").header("X-Session-Token", admin))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DOCUMENTO_NAO_VIGENTE"));

		publicar(admin, primeira);

		mockMvc.perform(get("/documentos/" + TERMOS))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.documento.id").value(primeira))
			.andExpect(jsonPath("$.documento.aprovado").value(true))
			.andExpect(jsonPath("$.documento.vigente").value(true));

		publicar(admin, segunda);

		// A anterior continua no histórico como aprovada, mas sem vigência.
		mockMvc.perform(get("/documentos"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.tipo == '" + TERMOS + "')].id").value(segunda));
		JsonNode anterior = versaoNoHistorico(TERMOS, primeira);
		assertThat(anterior.get("aprovado").asBoolean()).isTrue();
		assertThat(anterior.get("vigente").asBoolean()).isFalse();

		// Republicar a mesma versão não é possível: publicar é um ato único.
		mockMvc.perform(post("/admin/documentos/" + segunda + "/publicar").header("X-Session-Token", admin))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DOCUMENTO_JA_APROVADO"));
	}

	@Test
	void aceiteExigeSessaoEVersaoEmVigor() throws Exception {
		String admin = adminToken();
		String pessoa = authHelper().authenticate("doc_aceitante").token();

		String versao = criarRascunho(admin, DIRETRIZES, "diretrizes");
		publicar(admin, versao);
		assertThat(pendentes(situacao(pessoa), DIRETRIZES)).containsExactly(versao);

		// Sem sessão, o aceite nem chega ao serviço.
		mockMvc.perform(post("/documentos/" + versao + "/aceite"))
			.andExpect(status().isUnauthorized());

		MvcResult aceite = mockMvc.perform(post("/documentos/" + versao + "/aceite")
				.header("X-Session-Token", pessoa)
				.header("User-Agent", "teste-documentos/1.0"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.documentoId").value(versao))
			.andExpect(jsonPath("$.tipo").value(DIRETRIZES))
			.andExpect(jsonPath("$.origem").value("AceiteExplicito"))
			.andReturn();
		String aceiteId = json(aceite).get("id").asText();

		// Repetir o aceite devolve o mesmo registro; não duplica.
		mockMvc.perform(post("/documentos/" + versao + "/aceite").header("X-Session-Token", pessoa))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(aceiteId));

		JsonNode depois = situacao(pessoa);
		assertThat(pendentes(depois, DIRETRIZES)).isEmpty();
		JsonNode emVigor = null;
		for (JsonNode documento : depois.get("documentos")) {
			if (DIRETRIZES.equals(documento.get("documento").get("tipo").asText())) {
				emVigor = documento;
			}
		}
		assertThat(emVigor).isNotNull();
		assertThat(emVigor.get("aceito").asBoolean()).isTrue();
		assertThat(emVigor.get("conteudo").asText()).startsWith("Parágrafo");

		// O histórico de aceites guarda o que foi aceito.
		JsonNode aceites = json(mockMvc.perform(get("/documentos/me/aceites").header("X-Session-Token", pessoa))
			.andExpect(status().isOk()).andReturn());
		assertThat(aceites).anySatisfy(linha -> {
			assertThat(linha.get("documentoId").asText()).isEqualTo(versao);
			assertThat(linha.get("versao").asInt()).isEqualTo(versao(versao));
		});

		// Versão nova em vigor: pendência de novo, e aceitar a antiga não vale.
		String nova = criarRascunho(admin, DIRETRIZES, "diretrizes revisadas");
		publicar(admin, nova);
		assertThat(pendentes(situacao(pessoa), DIRETRIZES)).containsExactly(nova);
		mockMvc.perform(post("/documentos/" + versao + "/aceite").header("X-Session-Token", pessoa))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DOCUMENTO_NAO_VIGENTE"));
	}

	@Test
	void publicacaoEAdminERascunhoExigeTexto() throws Exception {
		String pessoa = authHelper().authenticate("doc_comum").token();

		mockMvc.perform(post("/admin/documentos").header("X-Session-Token", pessoa)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(rascunho("DIRETRIZES", "x"))))
			.andExpect(status().isForbidden());
		mockMvc.perform(get("/admin/documentos").header("X-Session-Token", pessoa))
			.andExpect(status().isForbidden());

		String admin = adminToken();
		mockMvc.perform(post("/admin/documentos").header("X-Session-Token", admin)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(rascunho("DIRETRIZES", "curto"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("DOCUMENTO_CONTEUDO_CURTO"));
		mockMvc.perform(post("/admin/documentos").header("X-Session-Token", admin)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(rascunho("OUTRO", texto("x")))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("DOCUMENTO_TIPO_INVALIDO"));
		mockMvc.perform(get("/documentos/qualquer")).andExpect(status().isNotFound());
	}

	@Test
	void cadastroGravaAceiteDosTermosQueEstavamEmVigor() throws Exception {
		String email = "termos_cadastro_" + System.nanoTime() + "@test.com";
		// A lista pública só traz o que está em vigor: se o tipo não está lá, nada estava em vigor.
		String encontrado = null;
		for (JsonNode linha : json(mockMvc.perform(get("/documentos")).andReturn())) {
			if (TERMOS.equals(linha.get("tipo").asText())) {
				encontrado = linha.get("id").asText();
			}
		}
		String emVigorNoCadastro = encontrado;

		// Mesmo caminho do formulário: a caixa dos Termos marcada, e a conta nasce na confirmação.
		String apelido = email.substring(0, email.indexOf('@'));
		signup.signup(new SignupCommand("Termos Cadastro", "USER", email, "Test1234!", true, "1990-05-10"));
		var nascida = authHelper().authenticate(apelido).token();

		JsonNode aceites = json(mockMvc.perform(get("/documentos/me/aceites").header("X-Session-Token", nascida))
			.andReturn());
		List<JsonNode> dosTermos = new ArrayList<>();
		for (JsonNode aceite : aceites) {
			if (TERMOS.equals(aceite.get("tipo").asText())) {
				dosTermos.add(aceite);
			}
		}
		if (emVigorNoCadastro == null) {
			// Nada em vigor: o cadastro não fabrica aceite de texto que não existe.
			assertThat(dosTermos).isEmpty();
		}
		else {
			assertThat(dosTermos).anySatisfy(aceite -> {
				assertThat(aceite.get("documentoId").asText()).isEqualTo(emVigorNoCadastro);
				assertThat(aceite.get("origem").asText()).isEqualTo("Cadastro");
			});
			assertThat(pendentes(situacao(nascida), TERMOS)).isEmpty();
		}
		assertThat(TestDatabase.userIdByEmail(email)).isPresent();
	}

	// ---------------------------------------------------------------- auxiliares

	private String adminToken() throws Exception {
		return authHelper().authenticate("group-admin").token();
	}

	private String criarRascunho(String token, String tipo, String assunto) throws Exception {
		MvcResult criado = mockMvc.perform(post("/admin/documentos").header("X-Session-Token", token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("tipo", tipo,
					"titulo", tipo + " — teste " + System.nanoTime(), "resumo", "Rascunho de teste",
					"conteudo", texto(assunto)))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.aprovado").value(false))
			.andExpect(jsonPath("$.tipo").value(tipo))
			.andReturn();
		return json(criado).get("id").asText();
	}

	private void publicar(String token, String id) throws Exception {
		mockMvc.perform(post("/admin/documentos/" + id + "/publicar").header("X-Session-Token", token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(id))
			.andExpect(jsonPath("$.aprovado").value(true))
			.andExpect(jsonPath("$.vigente").value(true));
	}

	private JsonNode situacao(String token) throws Exception {
		return json(mockMvc.perform(get("/documentos/me").header("X-Session-Token", token)).andReturn());
	}

	private JsonNode versaoNoHistorico(String tipo, String id) throws Exception {
		JsonNode lista = json(mockMvc.perform(get("/documentos/" + tipo + "/historico")).andReturn());
		for (JsonNode linha : lista) {
			if (id.equals(linha.get("id").asText())) {
				return linha;
			}
		}
		throw new AssertionError("versão " + id + " ausente do histórico de " + tipo);
	}

	private int versao(String id) throws Exception {
		for (String tipo : List.of(DIRETRIZES, TERMOS)) {
			JsonNode lista = json(mockMvc.perform(get("/documentos/" + tipo + "/historico")).andReturn());
			for (JsonNode linha : lista) {
				if (id.equals(linha.get("id").asText())) {
					return linha.get("versao").asInt();
				}
			}
		}
		throw new AssertionError("documento " + id + " não está no histórico");
	}

	private static List<String> pendentes(JsonNode situacao, String tipo) {
		List<String> ids = new ArrayList<>();
		for (JsonNode pendente : situacao.get("pendentes")) {
			if (tipo.equals(pendente.get("tipo").asText())) {
				ids.add(pendente.get("id").asText());
			}
		}
		return ids;
	}

	private JsonNode json(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}

	private static Map<String, Object> rascunho(String tipo, String conteudo) {
		return Map.of("tipo", tipo, "titulo", tipo + " de teste", "resumo", "Rascunho", "conteudo", conteudo);
	}

	private static String texto(String assunto) {
		StringBuilder texto = new StringBuilder("Parágrafo de teste — " + assunto + ". ");
		while (texto.length() < 400) {
			texto.append("Este texto existe só para passar do tamanho mínimo e exercitar a publicação. ");
		}
		return texto.toString();
	}
}
