package com.socialcoisa.rede.network.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestDatabase;

import tools.jackson.databind.ObjectMapper;

/**
 * Tipos de storage node (brief §6) vistos pelo participante: ele declara a banda própria de cada
 * tipo, vê o que declarou, e o <b>runtime recebe a lista na config assinada</b>.
 *
 * <p>O último ponto é o que dá sentido ao primeiro: limite que não chega na config assinada é
 * número guardado. Aqui a rota do participante, a config do nó e a assinatura são conferidas na
 * mesma roda.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class NetworkParticipantStorageKindTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	private TestAuthHelper auth;

	private static final java.security.KeyPair PAIR = generate();

	/** Chave de assinatura da rede de teste: sem ela a config sai com {@code signed=false}. */
	@org.springframework.test.context.DynamicPropertySource
	static void properties(org.springframework.test.context.DynamicPropertyRegistry registry) {
		registry.add("rede.network.config.signing-key",
			() -> java.util.Base64.getEncoder().encodeToString(PAIR.getPrivate().getEncoded()));
		registry.add("rede.network.config.signing-public-key",
			() -> java.util.Base64.getEncoder().encodeToString(PAIR.getPublic().getEncoded()));
	}

	private static java.security.KeyPair generate() {
		try {
			return java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
		}
		catch (java.security.NoSuchAlgorithmException impossible) {
			throw new IllegalStateException(impossible);
		}
	}

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
		jdbc.update("delete from network_node_storage_bandwidth");
		jdbc.update("delete from network_node_keys");
		jdbc.update("delete from network_provision_tokens");
		jdbc.update("delete from network_nodes");
	}

	/** O participante define a banda de um tipo; a tela devolve o que foi declarado. */
	@Test
	void participante_declara_banda_por_tipo() throws Exception {
		var dono = auth.authenticate("net_dono_tipo");
		String admin = adminToken();
		String nodeId = storageNodeFor(admin, dono.userId(), List.of("NODE_STORAGE_MEDIA"));

		mockMvc
			.perform(post("/network/participant/nodes/" + nodeId + "/storage-bandwidth/MEDIA")
				.header("X-Session-Token", dono.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"mbps\":15}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.storageBandwidth.MEDIA").value(15));

		assertThat(jdbc.queryForObject("select bandwidth_mbps from network_node_storage_bandwidth where node_id = ?"
			+ " and kind = 'MEDIA'", Integer.class, nodeId)).isEqualTo(15);
	}

	/**
	 * O limite chega ao runtime na config <b>assinada</b>, no formato 7 — e a assinatura confere
	 * com a lista. Sem esta conferência, a rota gravaria um número e o nó aplicaria a banda geral.
	 */
	@Test
	void a_banda_por_tipo_vai_na_config_assinada() throws Exception {
		var dono = auth.authenticate("net_dono_tipo_cfg");
		String admin = adminToken();
		String nodeId = storageNodeFor(admin, dono.userId(), List.of("NODE_STORAGE_MEDIA"));
		String key = issueKey(admin, nodeId);

		mockMvc
			.perform(post("/network/participant/nodes/" + nodeId + "/storage-bandwidth/MEDIA")
				.header("X-Session-Token", dono.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"mbps\":15}"))
			.andExpect(status().isOk());

		var config = mockMvc
			.perform(get("/network/nodes/me/config").header("X-Node-Id", nodeId).header("X-Node-Key", key))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.formatVersion").value(7))
			.andExpect(jsonPath("$.limits.storageBandwidth[0]").value("MEDIA=15"))
			.andReturn();
		var body = objectMapper.readTree(config.getResponse().getContentAsString());
		assertThat(body.get("signed").asBoolean()).isTrue();
		// Versão de config mudou: sem isso o runtime continuaria com a config anterior.
		assertThat(body.get("configVersion").asInt()).isGreaterThan(0);
		// E a assinatura cobre a lista: reconstruir a linha do formato 7 com "[\"MEDIA=15\"]" no
		// fim e verificar com a chave pública. Sem isso, a lista chegaria sem estar assinada.
		String canonical = String.join("|", "7", body.get("configVersion").asString(), nodeId,
			jsonList(List.of("NODE_STORAGE_MEDIA")), "4", "8192", "102400", "100",
			jsonList(List.of("OBJECT_GET", "OBJECT_PUT")), "[]", body.get("issuedAt").asString(),
			body.get("ttlSeconds").asString(), body.get("term").asString(), "ausente", "ausente",
			"[\"MEDIA=15\"]");
		assertThat(verify(canonical, body.get("signature").asString())).isTrue();
	}

	/** Tipo que o nó não declara: 409, e nada gravado. */
	@Test
	void tipo_nao_declarado_e_recusado() throws Exception {
		var dono = auth.authenticate("net_dono_tipo_recusa");
		String admin = adminToken();
		String nodeId = storageNodeFor(admin, dono.userId(), List.of("NODE_STORAGE_MEDIA"));

		mockMvc
			.perform(post("/network/participant/nodes/" + nodeId + "/storage-bandwidth/ARCHIVE")
				.header("X-Session-Token", dono.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"mbps\":15}"))
			.andExpect(status().isConflict());

		assertThat(jdbc.queryForObject("select count(*) from network_node_storage_bandwidth", Integer.class))
			.isZero();
	}

	/** Valor nulo remove a especialização: o tipo volta a usar a banda geral do nó. */
	@Test
	void banda_nula_remove_a_especializacao() throws Exception {
		var dono = auth.authenticate("net_dono_tipo_remove");
		String admin = adminToken();
		String nodeId = storageNodeFor(admin, dono.userId(), List.of("NODE_STORAGE_MEDIA"));
		mockMvc
			.perform(post("/network/participant/nodes/" + nodeId + "/storage-bandwidth/MEDIA")
				.header("X-Session-Token", dono.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"mbps\":15}"))
			.andExpect(status().isOk());

		mockMvc
			.perform(post("/network/participant/nodes/" + nodeId + "/storage-bandwidth/MEDIA")
				.header("X-Session-Token", dono.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"mbps\":null}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.storageBandwidth.MEDIA").doesNotExist());

		assertThat(jdbc.queryForObject("select count(*) from network_node_storage_bandwidth", Integer.class))
			.isZero();
	}

	private static String jsonList(List<String> values) {
		return values.stream().sorted().map(value -> "\"" + value + "\"")
			.collect(java.util.stream.Collectors.joining(",", "[", "]"));
	}

	private static boolean verify(String canonical, String signature) throws Exception {
		java.security.Signature check = java.security.Signature.getInstance("Ed25519");
		check.initVerify(PAIR.getPublic());
		check.update(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		return check.verify(java.util.Base64.getDecoder().decode(signature));
	}

	private String adminToken() throws Exception {
		var session = auth.authenticate("net_tipo_admin");
		TestDatabase.setAdmin(session.userId());
		return session.token();
	}

	private String storageNodeFor(String admin, String ownerUserId, List<String> profiles) throws Exception {
		var tokenResult = mockMvc
			.perform(post("/admin/network/nodes/provision-token").header("X-Session-Token", admin)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("displayName", "no-do-tipo",
					"ownerUserId", ownerUserId, "profiles", profiles))))
			.andExpect(status().isOk())
			.andReturn();
		String provisionToken = objectMapper.readTree(tokenResult.getResponse().getContentAsString())
			.get("token").asString();
		var enroll = mockMvc
			.perform(post("/network/nodes/enroll").contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("provisionToken", provisionToken, "declared",
					Map.of("cpuCores", 4, "memoryMb", 8192, "diskMb", 102400, "bandwidthMbps", 100)))))
			.andExpect(status().isOk())
			.andReturn();
		String nodeId = objectMapper.readTree(enroll.getResponse().getContentAsString()).get("nodeId").asString();
		mockMvc.perform(post("/admin/network/nodes/" + nodeId + "/approve").header("X-Session-Token", admin))
			.andExpect(status().isOk());
		return nodeId;
	}

	private String issueKey(String admin, String nodeId) throws Exception {
		var result = mockMvc
			.perform(post("/admin/network/nodes/" + nodeId + "/keys").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("apiKey").asString();
	}
}
