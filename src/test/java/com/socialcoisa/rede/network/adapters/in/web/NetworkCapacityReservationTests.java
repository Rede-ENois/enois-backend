package com.socialcoisa.rede.network.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.network.application.NodeKeyService;
import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestDatabase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A capacidade do Storage, brief §7, pelo caminho que existe: o nó mede o disco no heartbeat, o
 * Control Plane guarda, a config assinada devolve o que ele pode usar, e o painel mostra a conta de
 * reserva de cada volume.
 *
 * <ul>
 * <li>{@code diskTotalMb} chega pelo heartbeat e vira {@code observed_disk_total_mb} — não é um
 * campo de tela inventado a partir do declarado;</li>
 * <li>a config assinada carrega o total, e ele está DENTRO da linha canônica: trocar o número por
 * outro no caminho muda a reserva que o participante vê, e a assinatura recusa;</li>
 * <li>o painel devolve {@code total - declarado} por nó, com {@code null} quando o nó não mediu o
 * tamanho — nunca zero, que diria "não reservou nada".</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class NetworkCapacityReservationTests {

	private static final KeyPair PAIR = generate();

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("rede.network.config.signing-key", () -> privateKey(PAIR));
		registry.add("rede.network.config.signing-public-key", () -> publicKey(PAIR));
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private NodeKeyService keys;

	@Test
	void oTamanhoMedidoNoHeartbeatViraReservaNoPainelENaConfigAssinada() throws Exception {
		insertNode("nd_cap");
		String key = keys.issue("nd_cap", "owner-cap").plainTextKey();

		// 2 TB de volume, 1,5 TB cedidos: o nó mede o tamanho no heartbeat, como o runtime faz com `df`.
		mockMvc
			.perform(post("/network/nodes/heartbeat").header("X-Node-Id", "nd_cap").header("X-Node-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("runtimeVersion", "teste",
					"observed", Map.of("diskFreeMb", 900000, "diskTotalMb", 2097152, "usedMb", 768000)))))
			.andExpect(status().isOk());

		assertThat(jdbc.queryForObject("select observed_disk_total_mb from network_nodes where id = 'nd_cap'",
				Long.class))
			.isEqualTo(2097152L);

		// A base H2 é compartilhada entre classes: a linha é localizada pelo id, nunca pela posição.
		var overview = mockMvc
			.perform(get("/admin/network/nodes/storage/overview").header("X-Session-Token", adminToken()))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode meu = discoDe(overview, "nd_cap");
		assertThat(meu.get("totalMb").asLong()).isEqualTo(2097152L);
		assertThat(meu.get("declaredMb").asInt()).isEqualTo(1536000);
		assertThat(meu.get("reservedMb").asLong()).isEqualTo(2097152L - 1536000L);
		// §7 "capacidade utilizada pelo Énóis": o uso vem do nó, não de uma soma do catálogo.
		assertThat(meu.get("usedMb").asLong()).isEqualTo(768000L);
		assertThat(meu.get("usedPercent").asDouble()).isEqualTo(50.0);

		// A config do nó leva o mesmo total, e ele está dentro da linha assinada.
		var config = mockMvc
			.perform(get("/network/nodes/me/config").header("X-Node-Id", "nd_cap").header("X-Node-Key", key))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.formatVersion").value(7))
			.andExpect(jsonPath("$.limits.diskTotalMb").value(2097152))
			.andExpect(jsonPath("$.limits.diskMb").value(1536000))
			.andReturn();
		JsonNode tree = objectMapper.readTree(config.getResponse().getContentAsString());
		JsonNode limits = tree.get("limits");
		String canonical = String.join("|", tree.get("formatVersion").asString(),
			tree.get("configVersion").asString(), tree.get("nodeId").asString(), jsonList(tree.get("profiles")),
			limits.get("cpuCores").asString(), limits.get("memoryMb").asString(), limits.get("diskMb").asString(),
			limits.get("bandwidthMbps").asString(), jsonList(tree.get("allowedTasks")),
			jsonList(tree.get("allowedRoutes")), tree.get("issuedAt").asString(),
			tree.get("ttlSeconds").asString(), tree.get("term").asString(),
			textOrAbsent(limits.get("diskFreeMb")), textOrAbsent(limits.get("diskTotalMb")),
			// Formato 7: a banda por tipo (brief §6) fecha a linha, depois do tamanho do volume.
			jsonList(limits.get("storageBandwidth")));
		assertThat(verify(canonical, tree.get("signature").asString())).as("canonical montada: %s", canonical).isTrue();

		// O total trocado no caminho não confere: é a assinatura que impede anunciar outra reserva.
		String comOutroTotal = canonical.replaceFirst("\\|2097152\\|", "|512000|");
		assertThat(verify(comOutroTotal, tree.get("signature").asString())).isFalse();
	}

	@Test
	void semTamanhoMedidoAReservaEDesconhecidaENaoZero() throws Exception {
		insertNode("nd_cap_sem");
		String key = keys.issue("nd_cap_sem", "owner-cap").plainTextKey();
		// Runtime anterior: manda o livre, não o tamanho.
		mockMvc
			.perform(post("/network/nodes/heartbeat").header("X-Node-Id", "nd_cap_sem").header("X-Node-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("observed", Map.of("diskFreeMb", 4000)))))
			.andExpect(status().isOk());

		assertThat(jdbc.queryForObject("select observed_disk_total_mb from network_nodes where id = 'nd_cap_sem'",
				Long.class))
			.isNull();

		var overview = mockMvc
			.perform(get("/admin/network/nodes/storage/overview").header("X-Session-Token", adminToken()))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode meu = discoDe(overview, "nd_cap_sem");
		assertThat(meu.get("freeMb").asLong()).isEqualTo(4000L);
		// Nulo, não zero: "não reservou nada" seria outra afirmação.
		assertThat(meu.get("totalMb").isNull()).isTrue();
		assertThat(meu.get("reservedMb").isNull()).isTrue();
	}

	private JsonNode discoDe(org.springframework.test.web.servlet.MvcResult resultado, String nodeId)
			throws Exception {
		JsonNode tree = objectMapper.readTree(resultado.getResponse().getContentAsString());
		for (JsonNode disco : tree.get("disks")) {
			if (nodeId.equals(disco.get("nodeId").asString())) {
				return disco;
			}
		}
		throw new AssertionError("nó " + nodeId + " não apareceu no painel: " + tree.get("disks"));
	}

	private static String textOrAbsent(JsonNode value) {
		return value == null || value.isNull() ? "ausente" : value.asString();
	}

	private static String jsonList(JsonNode array) {
		// A linha canônica ordena as listas: a ordem do banco (e do enum) não é contrato. Montar na
		// ordem que veio no JSON daria uma linha diferente da assinada pela permissão certa.
		var valores = new java.util.TreeSet<String>();
		for (JsonNode item : array) {
			valores.add(item.asString());
		}
		StringBuilder out = new StringBuilder("[");
		valores.forEach(valor -> {
			if (out.length() > 1) {
				out.append(',');
			}
			out.append('"').append(valor).append('"');
		});
		return out.append(']').toString();
	}

	private void insertNode(String id) {
		jdbc.update("""
			insert into network_nodes (id, display_name, owner_user_id, status, profiles, declared_cpu_cores,
			  declared_memory_mb, declared_disk_mb, declared_bandwidth_mbps, region, country, created_at, last_seen_at)
			values (?, ?, 'owner-cap', 'ACTIVE', 'NODE_STORAGE', 4, 8192, 1536000, 100, 'sudeste', 'BR', now(3), now(3))
			""", id, "no-" + id);
	}

	private String adminToken() throws Exception {
		var session = new TestAuthHelper(mockMvc, objectMapper).authenticate("net_cap_admin");
		TestDatabase.setAdmin(session.userId());
		return session.token();
	}

	private static KeyPair generate() {
		try {
			return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
		}
		catch (Exception impossible) {
			throw new IllegalStateException(impossible);
		}
	}

	private static String privateKey(KeyPair pair) {
		return Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
	}

	private static String publicKey(KeyPair pair) {
		return Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
	}

	private static boolean verify(String canonical, String signature) {
		try {
			Signature verifier = Signature.getInstance("Ed25519");
			verifier.initVerify(PAIR.getPublic());
			verifier.update(canonical.getBytes(StandardCharsets.UTF_8));
			return verifier.verify(Base64.getDecoder().decode(signature));
		}
		catch (Exception impossible) {
			throw new IllegalStateException(impossible);
		}
	}
}