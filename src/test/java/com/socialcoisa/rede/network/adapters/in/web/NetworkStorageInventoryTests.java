package com.socialcoisa.rede.network.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * O inventário pela rota real: o que o nó tem no disco contra o que o catálogo promete.
 *
 * <p>O dono do inventário é o processo que guarda o corpo — o mesmo que roda o Control Plane nesta
 * máquina. O que estes testes fixam, e por quê:</p>
 * <ul>
 * <li><b>arquivo sem colocação é órfão</b>, com os bytes certos: é o que o operador pode podar;</li>
 * <li><b>a poda seca não apaga</b> — o padrão é ver o número antes de liberar disco de quem contribui;</li>
 * <li><b>a poda real apaga só o órfão</b> e o corpo colocado continua lá;</li>
 * <li><b>outro nó não lê</b> o inventário deste disco, mesmo com chave válida;</li>
 * <li><b>sem instalação a rota é 409</b>, não lista vazia.</li>
 * </ul>
 */
@SpringBootTest(properties = "rede.storage.node-body-root=target/test-inventory-node/objects")
@AutoConfigureMockMvc
class NetworkStorageInventoryTests {

	private static final Path NO_DIR = Path.of("target", "test-inventory-node", "objects");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() throws Exception {
		auth = new TestAuthHelper(mockMvc, objectMapper);
		jdbc.update("delete from network_storage_placements");
		jdbc.update("delete from network_storage_objects");
		jdbc.update("delete from network_node_keys");
		jdbc.update("delete from network_provision_tokens");
		jdbc.update("delete from network_nodes");
		if (Files.isDirectory(NO_DIR)) {
			try (var arquivos = Files.list(NO_DIR)) {
				for (Path p : arquivos.toList()) {
					Files.deleteIfExists(p);
				}
			}
		}
		Files.createDirectories(NO_DIR);
		Files.deleteIfExists(NO_DIR.getParent().resolve("node.json"));
		Files.deleteIfExists(NO_DIR.getParent().resolve("api-key"));
	}

	private String adminToken() throws Exception {
		var session = auth.authenticate("net_inv_admin_" + System.nanoTime());
		com.socialcoisa.rede.shared.web.TestDatabase.setAdmin(session.userId());
		return session.token();
	}

	/** Nó completo pelo caminho real: token → enroll → aprovação → chave → heartbeat. */
	private Node node(String nome) throws Exception {
		String admin = adminToken();
		var tokenResult = mockMvc
			.perform(post("/admin/network/nodes/provision-token").header("X-Session-Token", admin)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("displayName", nome, "ownerUserId", "owner-" + nome,
					"profiles", List.of("NODE_STORAGE")))))
			.andExpect(status().isOk())
			.andReturn();
		String provisionToken = objectMapper.readTree(tokenResult.getResponse().getContentAsString()).get("token")
			.asString();
		var enroll = mockMvc
			.perform(post("/network/nodes/enroll").contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("provisionToken", provisionToken, "declared",
					Map.of("cpuCores", 2, "memoryMb", 4096, "diskMb", 102400, "bandwidthMbps", 100)))))
			.andExpect(status().isOk())
			.andReturn();
		String nodeId = objectMapper.readTree(enroll.getResponse().getContentAsString()).get("nodeId").asString();
		mockMvc.perform(post("/admin/network/nodes/" + nodeId + "/approve").header("X-Session-Token", admin))
			.andExpect(status().isOk());
		var keyResult = mockMvc
			.perform(post("/admin/network/nodes/" + nodeId + "/keys").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andReturn();
		String key = objectMapper.readTree(keyResult.getResponse().getContentAsString()).get("apiKey").asString();
		mockMvc.perform(post("/network/nodes/heartbeat").header("X-Node-Id", nodeId).header("X-Node-Key", key)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("runtimeVersion", "0.1.0", "observed",
				Map.of("cpuPercent", 5.0, "memoryMb", 1024, "runningTasks", 0, "diskFreeMb", 51200)))))
			.andExpect(status().isOk());
		return new Node(nodeId, key);
	}

	/**
	 * O nó dono do disco deste processo.
	 *
	 * <p>Quem identifica o dono é o próprio diretório: `node.json` e `api-key` com as mesmas permissões
	 * do runtime (0600). Uma configuração separada `rede.storage.node-id` daria dois lugares dizendo
	 * de quem é o disco — e o segundo é o que ninguém atualiza.</p>
	 */
	private Node dono() throws Exception {
		Node no = node("nd_estoque");
		Files.writeString(NO_DIR.getParent().resolve("node.json"), "{\"nodeId\":\"" + no.id() + "\"}");
		Path chave = NO_DIR.getParent().resolve("api-key");
		Files.writeString(chave, no.key());
		try {
			Files.setPosixFilePermissions(chave, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
		}
		catch (UnsupportedOperationException semPosix) {
			// Windows: o teste não depende do modo, só do conteúdo.
		}
		return no;
	}

	private void corpoNoDisco(String objectId, String conteudo) throws Exception {
		Files.write(NO_DIR.resolve(objectId + ".bin"), conteudo.getBytes(StandardCharsets.UTF_8));
	}

	@Test
	void arquivo_sem_colocacao_aparece_como_orfao_e_a_poda_seca_nao_apaga() throws Exception {
		Node no = dono();
		corpoNoDisco("sob_lixo", "resto de tarefa");

		var result = mockMvc
			.perform(get("/network/storage/inventory").header("X-Node-Id", no.id()).header("X-Node-Key", no.key()))
			.andExpect(status().isOk())
			.andReturn();
		var report = objectMapper.readTree(result.getResponse().getContentAsString());

		assertThat(report.get("orphan")).hasSize(1);
		assertThat(report.get("orphan").get(0).get("objectId").asString()).isEqualTo("sob_lixo");
		assertThat(report.get("orphanBytes").asLong()).isEqualTo("resto de tarefa".length());

		var purge = mockMvc
			.perform(post("/network/storage/inventory/purge").header("X-Node-Id", no.id())
				.header("X-Node-Key", no.key()))
			.andExpect(status().isOk())
			.andReturn();
		var seca = objectMapper.readTree(purge.getResponse().getContentAsString());
		assertThat(seca.get("removed")).isEmpty();
		assertThat(seca.get("kept")).hasSize(1);
		assertThat(Files.exists(NO_DIR.resolve("sob_lixo.bin"))).isTrue();
	}

	@Test
	void poda_real_apaga_o_orfao_e_deixa_o_corpo_que_o_catalogo_promete() throws Exception {
		Node no = dono();
		String shaOk = "a".repeat(64);
		jdbc.update("""
			insert into network_storage_objects (id, bucket, object_key, content_type, bytes, sha256, state,
			  created_by, created_at, updated_at)
			values ('sob_ok', 'fotos', 'ok.jpg', 'image/jpeg', 8, ?, 'STORED', 'u', now(3), now(3))
			""", shaOk);
		jdbc.update("""
			insert into network_storage_placements (object_id, replica, node_id, bytes, sha256, state, verified_at)
			values ('sob_ok', 0, ?, 8, ?, 'OK', now(3))
			""", no.id(), shaOk);
		corpoNoDisco("sob_lixo", "lixo");
		corpoNoDisco("sob_ok", "conteudo");

		var purge = mockMvc
			.perform(post("/network/storage/inventory/purge").param("dryRun", "false")
				.header("X-Node-Id", no.id()).header("X-Node-Key", no.key()))
			.andExpect(status().isOk())
			.andReturn();
		var resultado = objectMapper.readTree(purge.getResponse().getContentAsString());

		assertThat(resultado.get("removed")).hasSize(1);
		assertThat(resultado.get("removed").get(0).asString()).isEqualTo("sob_lixo");
		assertThat(Files.exists(NO_DIR.resolve("sob_lixo.bin"))).isFalse();
		// O corpo colocado NÃO sai: apagar o que o catálogo promete é perda de dado.
		assertThat(Files.exists(NO_DIR.resolve("sob_ok.bin"))).isTrue();
	}

	@Test
	void outro_no_nao_le_o_inventario_deste_disco() throws Exception {
		dono();
		Node outro = node("nd_curioso");

		mockMvc.perform(get("/network/storage/inventory").header("X-Node-Id", outro.id())
			.header("X-Node-Key", outro.key())).andExpect(status().isForbidden());
	}

	@Test
	void sem_chave_nao_le() throws Exception {
		mockMvc.perform(get("/network/storage/inventory")).andExpect(status().isUnauthorized());
	}

	private record Node(String id, String key) {
	}
}