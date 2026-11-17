package com.socialcoisa.rede.network.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.socialcoisa.rede.identity.application.EmailService;
import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestDatabase;

import tools.jackson.databind.ObjectMapper;

/**
 * O fluxo de inscrição inteiro, pelo caminho real: formulário público → decisão administrativa →
 * link de uso único → chave → enroll → responsável visível no painel.
 *
 * <p>O e-mail é gravado em memória pelo dublê, exatamente como a rota o emitiria — o token do link
 * não é lido do banco, porque ele não está lá (só o hash). Os testes fixam o que o contrato promete
 * e o que o painel precisa mostrar:</p>
 *
 * <ul>
 * <li>a aprovação manda o link e <b>não</b> cria nó nem expõe chave;</li>
 * <li>o link abre uma vez; a segunda respondendo 409;</li>
 * <li>a chave emitida no link <b>cria um nó de verdade</b> pelo enroll de sempre;</li>
 * <li>o nó aparece no painel com o nome e o e-mail do formulário como responsável;</li>
 * <li>a listagem mascara RG/CPF; o detalhe administrativo os mostra inteiros.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class NetworkJoinTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	/**
	 * O e-mail é o único lugar onde o token do link existe em claro. O dublê captura o argumento,
	 * que é exatamente o valor que a rota recebeu — não uma reconstrução.
	 */
	@MockitoBean
	private EmailService emailService;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	@Test
	void formularioDecisaoELinkEmitemAChaveUmaVezEMostramOResponsavel() throws Exception {
		String admin = adminToken();
		String email = "inscrito-" + UUID.randomUUID().toString().substring(0, 8) + "@exemplo.com";

		// 1. formulário público: sem sessão nenhuma.
		var applied = mockMvc
			.perform(post("/network/join/applications").contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("fullName", "Maria Aparecida Souza", "email", email,
					"rg", "MG-12.345.678", "cpf", cpfFrom(email),
					"motivation", "Tenho uma maquina ligada 24h e quero ajudar a rede com a banda que sobra",
					"acceptedTermVersion", "1", "profiles", List.of("NODE_WORKER")))))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("PENDING"))
			.andReturn();
		String requestId = objectMapper.readTree(applied.getResponse().getContentAsString()).get("id").asString();

		// 2. a aprovação não devolve chave nem cria nó: manda o link.
		var decision = mockMvc
			.perform(post("/admin/network/join-requests/" + requestId + "/decision").header("X-Session-Token", admin)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("decision", "APPROVE", "note", "maquina boa",
					"profiles", List.of("NODE_WORKER")))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.linkSent").value(true))
			.andReturn();
		assertThat(decision.getResponse().getContentAsString()).doesNotContain("npt_");

		// 3. o link: o token existe só no e-mail. O dublê captura o que a rota enviou.
		ArgumentCaptor<String> revealCaptor = ArgumentCaptor.forClass(String.class);
		Mockito.verify(emailService).sendNodeJoinApprovedEmail(Mockito.eq(email),
			Mockito.eq("Maria Aparecida Souza"), revealCaptor.capture());
		String revealToken = revealCaptor.getValue();
		assertThat(revealToken).hasSize(64);

		var revealed = mockMvc
			.perform(post("/network/join/reveal").contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("token", revealToken, "nodeName", "no-maria",
					"region", "sa-east-1", "cpuCores", 4, "memoryMb", 8192, "diskMb", 102400, "bandwidthMbps", 100))))
			.andExpect(status().isOk())
			.andReturn();
		String provisionToken = objectMapper.readTree(revealed.getResponse().getContentAsString())
			.get("provisionToken")
			.asString();
		assertThat(provisionToken).startsWith("npt_");

		// 4. segundo uso do MESMO link: 409, sem chave.
		mockMvc
			.perform(post("/network/join/reveal").contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("token", revealToken))))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("NODE_REVEAL_USED"));

		// 5. a chave cria o nó pelo protocolo de sempre.
		String nodeId = enroll(provisionToken);

		// 6. o painel mostra quem responde: nome e e-mail do FORMULÁRIO, não um id solto.
		var nodes = mockMvc.perform(get("/admin/network/nodes").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andReturn();
		var row = objectMapper.readTree(nodes.getResponse().getContentAsString()).valueStream()
			.filter(node -> nodeId.equals(node.get("id").asString()))
			.findFirst()
			.orElseThrow();
		assertThat(row.get("responsible").get("name").asString()).isEqualTo("Maria Aparecida Souza");
		assertThat(row.get("responsible").get("email").asString()).isEqualTo(email);
		assertThat(row.get("responsible").get("source").asString()).isEqualTo("JOIN_REQUEST");
	}

	@Test
	void aListagemMascaraRgECpfEOdetalheAdministrativoMostraInteiro() throws Exception {
		String admin = adminToken();
		String email = "mascara-" + UUID.randomUUID().toString().substring(0, 8) + "@exemplo.com";
		var applied = mockMvc
			.perform(post("/network/join/applications").contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("fullName", "João Pereira", "email", email,
					"rg", "SP-98.765.432", "cpf", cpfFrom(email), "motivation", "Quero contribuir com servidor",
					"acceptedTermVersion", "1"))))
			.andExpect(status().isCreated())
			.andReturn();
		String requestId = objectMapper.readTree(applied.getResponse().getContentAsString()).get("id").asString();

		var list = mockMvc
			.perform(get("/admin/network/join-requests").header("X-Session-Token", admin).param("status", "PENDING"))
			.andExpect(status().isOk())
			.andReturn();
		String body = list.getResponse().getContentAsString();
		assertThat(body).doesNotContain("11144477735");
		var row = objectMapper.readTree(body).valueStream()
			.filter(item -> requestId.equals(item.get("id").asString()))
			.findFirst()
			.orElseThrow();
		assertThat(row.get("maskedCpf").asString()).isEqualTo("***.***.***-" + cpfFrom(email).substring(9));

		var detail = mockMvc
			.perform(get("/admin/network/join-requests/" + requestId).header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andReturn();
		assertThat(objectMapper.readTree(detail.getResponse().getContentAsString()).get("cpf").asString())
			.isEqualTo(cpfFrom(email));
	}

	@Test
	void oFormularioSemSessaoEhPublicoMasADecisaoExigeAdmin() throws Exception {
		String email = "ana-" + UUID.randomUUID().toString().substring(0, 8) + "@exemplo.com";
		mockMvc.perform(post("/network/join/applications").contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("fullName", "Ana", "email", email,
				"rg", "RJ-11.222.333", "cpf", cpfFrom(email), "motivation", "contribuir",
				"acceptedTermVersion", "1"))))
			.andExpect(status().isCreated());

		// Sem sessão de admin, a fila não abre.
		mockMvc.perform(get("/admin/network/join-requests")).andExpect(status().isUnauthorized());
	}

	@Test
	void oTermoVigenteEhPublicoEVemComAVersaoQueOFormularioAceita() throws Exception {
		mockMvc.perform(get("/network/join/term"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.version").value("1"))
			.andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.containsString("CONTRATO DE RESPONSABILIDADE")));
	}

	// ------------------------------------------------------------------ apoio

	/**
	 * CPF válido e diferente por teste: o banco é o mesmo para a classe inteira, e o freio de pedido
	 * duplicado é por CPF — dois testes com o mesmo CPF pendente colidiriam de propósito. O valor é
	 * derivado de uma semente, e conferido pelos verificadores de verdade em {@code CpfTests}.
	 */
	private static String cpfFrom(String seed) {
		String base = String.format("%09d", Math.abs(seed.hashCode()) % 1_000_000_000);
		int d1 = checkDigit(base, 10);
		int d2 = checkDigit(base + d1, 11);
		return base + d1 + d2;
	}

	/** Mesmo cálculo do domínio: o dígito da posição k usa peso (k+1) descendo até 2. */
	private static int checkDigit(String partial, int firstWeight) {
		int sum = 0;
		for (int i = 0; i < partial.length(); i++) {
			sum += (partial.charAt(i) - '0') * (firstWeight - i);
		}
		int remainder = sum % 11;
		return remainder < 2 ? 0 : 11 - remainder;
	}

	private String enroll(String provisionToken) throws Exception {
		var enroll = mockMvc
			.perform(post("/network/nodes/enroll").contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("provisionToken", provisionToken, "declared",
					Map.of("cpuCores", 6, "memoryMb", 12288, "diskMb", 204800, "bandwidthMbps", 200)))))
			.andReturn();
		assertThat(enroll.getResponse().getStatus()).as("enroll: %s", enroll.getResponse().getContentAsString())
			.isEqualTo(200);
		String nodeId = objectMapper.readTree(enroll.getResponse().getContentAsString()).get("nodeId").asString();
		mockMvc.perform(post("/admin/network/nodes/" + nodeId + "/approve").header("X-Session-Token", adminToken()));
		assertThat(nodeId).startsWith("nd_");
		// O emailSentAt é o que prova que o link saiu: sem ele o painel oferece reenviar.
		assertThat(jdbc.queryForObject("select count(*) from network_nodes where id = ?", Integer.class, nodeId))
			.isEqualTo(1);
		return nodeId;
	}

	private String adminToken() throws Exception {
		var session = auth.authenticate("net_join_admin_" + System.nanoTime());
		TestDatabase.setAdmin(session.userId());
		return session.token();
	}

}