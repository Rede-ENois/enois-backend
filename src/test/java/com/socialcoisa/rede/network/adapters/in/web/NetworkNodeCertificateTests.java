package com.socialcoisa.rede.network.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.KeyPair;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Map;

import javax.security.auth.x500.X500Principal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.ObjectMapper;
import com.socialcoisa.rede.network.application.NodeCertificateIssuer;
import com.socialcoisa.rede.network.application.NodeCertificateService;
import com.socialcoisa.rede.network.application.NodeCertificateTestCsr;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

/**
 * O certificado mTLS chega ao nó pela rota real (brief §10): CSR no enroll, e renovação com a chave.
 *
 * <p>O que só um teste de integração prova: a peça está ligada — o controller recebe o CSR, o serviço
 * emite, e o nó (que não tem sessão de navegador) passa pelo {@code WriteRouteInterceptor} e pelo
 * filtro de origem. Unitário verde com rota 403 é a falha que isto existe para pegar.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class NetworkNodeCertificateTests {

	/**
	 * CA efêmera, gerada uma vez no carregamento da classe — nunca em disco, nunca no banco.
	 *
	 * <p>Tem de ser {@code static} e entrar por {@code DynamicPropertySource}: a propriedade é lida
	 * quando o contexto Spring sobe, antes de qualquer {@code @BeforeEach}.</p>
	 */
	private static final KeyPair CA = NodeCertificateService.newCaKeyPair();

	@DynamicPropertySource
	static void ca(DynamicPropertyRegistry registry) {
		registry.add("rede.network.ca-key", () -> Base64.getEncoder().encodeToString(CA.getPrivate().getEncoded()));
		registry.add("rede.network.ca-public", () -> Base64.getEncoder().encodeToString(CA.getPublic().getEncoded()));
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
		jdbc.update("delete from network_node_certificates");
		jdbc.update("delete from network_node_keys");
		jdbc.update("delete from network_provision_tokens");
		jdbc.update("delete from network_nodes");
	}

	@Test
	void enroll_com_csr_devolve_certificado_assinado_pela_ca_e_o_no_consegue_renovar() throws Exception {
		KeyPair par = NodeCertificateService.newCaKeyPair();
		String token = provisionToken("no-cert");

		var result = mockMvc
			.perform(post("/network/nodes/enroll").contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("provisionToken", token, "csr", csr(par)))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.certificate").isNotEmpty())
			.andExpect(jsonPath("$.caPublicKey").isNotEmpty())
			.andReturn();
		var body = objectMapper.readTree(result.getResponse().getContentAsString());
		String nodeId = body.get("nodeId").asString();

		X509Certificate cert = parse(body.get("certificate").asString());
		assertThat(cert.getSubjectX500Principal()).isEqualTo(new X500Principal("CN=ENOIS-NODE-" + nodeId));
		assertThat(cert.getIssuerX500Principal()).isEqualTo(new X500Principal(NodeCertificateService.CA_SUBJECT));
		// A chave pública do certificado é a do par que o "nó" gerou: o Control Plane nunca viu a privada.
		assertThat(cert.getPublicKey().getEncoded()).isEqualTo(par.getPublic().getEncoded());

		// O nó é aprovado e recebe a chave; então renova com a chave — sem sessão de navegador.
		mockMvc.perform(post("/admin/network/nodes/" + nodeId + "/approve").header("X-Session-Token", admin()))
			.andExpect(status().isOk());
		String key = apiKey(nodeId);

		KeyPair renovado = NodeCertificateService.newCaKeyPair();
		mockMvc
			.perform(post("/network/nodes/me/certificate").header("X-Node-Id", nodeId).header("X-Node-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("csr", csr(renovado)))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.certificatePem").isNotEmpty())
			.andExpect(jsonPath("$.serial").isNotEmpty());

		// Rotação: um certificado vigente por nó, e o aniversário da anterior está registrado.
		Integer ativos = jdbc.queryForObject(
			"select count(*) from network_node_certificates where node_id = ? and revoked_at is null", Integer.class,
			nodeId);
		assertThat(ativos).isEqualTo(1);
		Integer revogados = jdbc.queryForObject(
			"select count(*) from network_node_certificates where node_id = ? and revoked_reason = 'rotacao'",
			Integer.class, nodeId);
		assertThat(revogados).isEqualTo(1);
	}

	/**
	 * Revogar o nó revoga o certificado na mesma transação (brief §10).
	 *
	 * <p>Antes: revogar matava a API key e deixava o certificado vigente. O nó derrubado voltaria a
	 * entrar pelo handshake TLS no dia em que ele fosse exigido — a perda de potência valia em um canal
	 * só. O banco agora conta a mesma história que a rede: nenhum certificado ativo para nó revogado.</p>
	 */
	@Test
	void revogar_o_no_revoga_o_certificado() throws Exception {
		KeyPair par = NodeCertificateService.newCaKeyPair();
		var result = mockMvc
			.perform(post("/network/nodes/enroll").contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(
					Map.of("provisionToken", provisionToken("no-revogado"), "csr", csr(par)))))
			.andExpect(status().isOk())
			.andReturn();
		String nodeId = objectMapper.readTree(result.getResponse().getContentAsString()).get("nodeId").asString();
		mockMvc.perform(post("/admin/network/nodes/" + nodeId + "/approve").header("X-Session-Token", admin()))
			.andExpect(status().isOk());

		mockMvc
			.perform(post("/admin/network/nodes/" + nodeId + "/status").header("X-Session-Token", admin())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("status", "REVOKED"))))
			.andExpect(status().isOk());

		Integer ativos = jdbc.queryForObject(
			"select count(*) from network_node_certificates where node_id = ? and revoked_at is null", Integer.class,
			nodeId);
		assertThat(ativos).isZero();
		String motivo = jdbc.queryForObject(
			"select revoked_reason from network_node_certificates where node_id = ?", String.class, nodeId);
		assertThat(motivo).isEqualTo("no revogado");
	}

	/** CSR forjado: recusado com 400 e nada gravado — o certificado não pode existir sem prova de posse. */
	@Test
	void enroll_com_csr_forjado_e_400_e_nao_grava_certificado() throws Exception {
		KeyPair chaveDoNo = NodeCertificateService.newCaKeyPair();
		KeyPair outra = NodeCertificateService.newCaKeyPair();

		mockMvc
			.perform(post("/network/nodes/enroll").contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(
					Map.of("provisionToken", provisionToken("no-forjado"), "csr", csrDe(chaveDoNo, outra)))))
			.andExpect(status().isBadRequest());

		assertThat(jdbc.queryForObject("select count(*) from network_node_certificates", Integer.class)).isZero();
	}

	/** Renovação exige a API key: certificado não se obtém só por saber o id do nó. */
	@Test
	void renovacao_sem_chave_e_401() throws Exception {
		mockMvc
			.perform(post("/network/nodes/me/certificate").header("X-Node-Id", "nd_qualquer")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("csr", csr(NodeCertificateService.newCaKeyPair())))))
			.andExpect(status().isUnauthorized());
	}

	// ------------------------------------------------------------------ helpers

	private String admin() throws Exception {
		var session = auth.authenticate("net_certificate_admin");
		com.socialcoisa.rede.shared.web.TestDatabase.setAdmin(session.userId());
		return session.token();
	}

	private String provisionToken(String nome) throws Exception {
		var result = mockMvc
			.perform(post("/admin/network/nodes/provision-token").header("X-Session-Token", admin())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(
					Map.of("displayName", nome, "ownerUserId", "owner-" + nome, "profiles", java.util.List.of("NODE_API")))))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asString();
	}

	private String apiKey(String nodeId) throws Exception {
		var result = mockMvc.perform(post("/admin/network/nodes/" + nodeId + "/keys")
			.header("X-Session-Token", admin())).andExpect(status().isOk()).andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("apiKey").asString();
	}

	private static X509Certificate parse(String pem) throws Exception {
		byte[] der = Base64.getMimeDecoder().decode(pem.replaceAll("-----[A-Z ]+-----", ""));
		return (X509Certificate) CertificateFactory.getInstance("X.509")
			.generateCertificate(new java.io.ByteArrayInputStream(der));
	}

	private static String csr(KeyPair par) throws Exception {
		return csrDe(par, par);
	}

	private static String csrDe(KeyPair chave, KeyPair assinadoPor) throws Exception {
		Signature signer = NodeCertificateIssuer.signature("Ed25519", assinadoPor.getPrivate(), true);
		byte[] info = NodeCertificateTestCsr.infoDer(chave);
		signer.update(NodeCertificateTestCsr.signedBytes(info));
		return NodeCertificateTestCsr.pem(info, signer.sign());
	}
}