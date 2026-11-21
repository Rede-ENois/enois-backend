package com.socialcoisa.rede.network.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
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
import com.socialcoisa.rede.shared.web.TestDatabase;

import tools.jackson.databind.ObjectMapper;

/**
 * Rebalanceamento do Storage pelas rotas admin (brief §16, V354).
 *
 * <p>O que estas rotas <b>não</b> fazem, por desenho: remover a cópia antiga sem a confirmação do
 * destino. O {@code complete} é o único passo que marca a antiga como {@code GONE}, e ele devolve 409
 * enquanto a nova estiver {@code PENDING_REMOTE}. Um teste fixa justamente isso, porque é a única
 * frase do §16 que, violada, perde arquivo.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class NetworkStorageRebalanceTests {

	private static final String SHA = "c".repeat(64);

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	private TestAuthHelper auth;
	private String admin;
	private String objectId;

	@BeforeEach
	void setUp() throws Exception {
		auth = new TestAuthHelper(mockMvc, objectMapper);
		jdbc.update("delete from network_storage_rebalances");
		jdbc.update("delete from network_nodes where id = 'nd_destino'");
		jdbc.update("delete from network_storage_placements");
		jdbc.update("delete from network_storage_objects");
		admin = adminToken();
		// O destino tem de existir de verdade: recusar nó inexistente é o comportamento correto da
		// rota, e um teste que dependesse disso não provaria nada sobre a transferência.
		jdbc.update("insert into network_nodes (id, display_name, owner_user_id, status, profiles, "
			+ "declared_cpu_cores, declared_memory_mb, declared_disk_mb, declared_bandwidth_mbps, "
			+ "created_at, last_seen_at) values ('nd_destino', 'destino', 'owner-destino', 'ACTIVE', "
			+ "'NODE_STORAGE', 4, 8192, 51200, 100, ?, ?)",
			java.sql.Timestamp.from(Instant.now()), java.sql.Timestamp.from(Instant.now()));
		objectId = objetoComDuasCopias();
	}

	/**
	 * Pedir a transferência cria a réplica nova {@code PENDING_REMOTE} e deixa a antiga {@code OK}.
	 * Sem isso, a página de mídia que aponta para a antiga pararia no meio da transferência.
	 */
	@Test
	void mover_cria_replica_nova_pendente_e_mantem_a_antiga_ok() throws Exception {
		mockMvc.perform(post("/admin/network/storage/rebalance").header("X-Session-Token", admin)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("objectId", objectId, "fromReplica", 1,
				"toNodeId", "nd_destino", "bandwidthLimitMbps", 4))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("PENDING"))
			.andExpect(jsonPath("$.toReplica").value(3))
			.andExpect(jsonPath("$.bandwidthLimitMbps").value(4));

		// A réplica nova continua o número do catálogo: as duas existentes são 1 e 2.
		assertThat(estadoDaCopia(3)).isEqualTo("PENDING_REMOTE");
		assertThat(estadoDaCopia(1)).isEqualTo("OK");
	}

	/** A única cópia saudável não sai, e a recusa é explícita na borda. */
	@Test
	void mover_a_unica_copia_devolve_409() throws Exception {
		jdbc.update("delete from network_storage_placements where replica = 1");
		jdbc.update("delete from network_storage_placements where replica = 0");

		mockMvc.perform(post("/admin/network/storage/rebalance").header("X-Session-Token", admin)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("objectId", objectId, "fromReplica", 2,
				"toNodeId", "nd_destino"))))
			.andExpect(status().isConflict());
	}

	/**
	 * <b>A regra do brief.</b> Sem a confirmação do destino, o {@code complete} responde 409 e a
	 * cópia antiga continua {@code OK} — nunca {@code GONE}.
	 */
	@Test
	void completar_sem_destino_confirmado_nao_remove_a_antiga() throws Exception {
		String id = mover();

		mockMvc.perform(post("/admin/network/storage/rebalance/" + id + "/complete")
			.header("X-Session-Token", admin))
			.andExpect(status().isConflict());

		assertThat(estadoDaCopia(1)).isEqualTo("OK");
		assertThat(estadoDaCopia(3)).isEqualTo("PENDING_REMOTE");
	}

	/** Confirmado o destino, a antiga vira {@code GONE} e a transferência fecha. */
	@Test
	void completar_com_destino_ok_remove_a_antiga() throws Exception {
		String id = mover();
		jdbc.update("update network_storage_placements set state = 'OK' where object_id = ? and replica = 3",
			objectId);

		mockMvc.perform(post("/admin/network/storage/rebalance/" + id + "/complete")
			.header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("DONE"));

		assertThat(estadoDaCopia(1)).isEqualTo("GONE");
		assertThat(estadoDaCopia(3)).isEqualTo("OK");
	}

	/** Desfazer recolhe a nova e deixa a antiga: o objeto nunca perde a cópia que servia. */
	@Test
	void cancelar_recolhe_a_nova_e_deixa_a_antiga() throws Exception {
		String id = mover();

		mockMvc.perform(post("/admin/network/storage/rebalance/" + id + "/cancel")
			.header("X-Session-Token", admin)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("reason", "operador desistiu"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("CANCELLED"));

		assertThat(estadoDaCopia(1)).isEqualTo("OK");
		assertThat(estadoDaCopia(3)).isEqualTo("GONE");
	}

	/** Depois de concluída, cancelar é 409: desfazer o DONE tiraria a única cópia que restou. */
	@Test
	void cancelar_depois_de_concluida_devolve_409() throws Exception {
		String id = mover();
		jdbc.update("update network_storage_placements set state = 'OK' where object_id = ? and replica = 3",
			objectId);
		mockMvc.perform(post("/admin/network/storage/rebalance/" + id + "/complete")
			.header("X-Session-Token", admin)).andExpect(status().isOk());

		mockMvc.perform(post("/admin/network/storage/rebalance/" + id + "/cancel").header("X-Session-Token", admin)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{}"))
			.andExpect(status().isConflict());
		assertThat(estadoDaCopia(3)).isEqualTo("OK");
	}

	/** O painel vê as transferências vivas, com a antiga e a nova. */
	@Test
	void listagem_mostra_as_transferencias_vivas() throws Exception {
		mover();

		mockMvc.perform(get("/admin/network/storage/rebalance").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].fromReplica").value(1))
			.andExpect(jsonPath("$[0].toNodeId").value("nd_destino"));
	}

	/**
	 * A transferência que perdeu a designação aparece como órfã e é fechada pela rota — e a cópia
	 * antiga continua {@code OK}.
	 *
	 * <p><b>Antes:</b> {@code stale()} e {@code fail()} existiam no serviço e <b>nenhuma rota</b> os
	 * chamava. A designação do destino era recolhida por outra rota (a soltura de pendência, o
	 * healing), a transferência ficava {@code PENDING} para sempre, e a §12.3 da doc 17 prometia um
	 * {@code FAILED} que nenhum caminho alcançava.</p>
	 *
	 * <p><b>Agora:</b> a órfã aparece em {@code GET /stale}, o {@code POST} seco não grava, e o
	 * {@code POST} com {@code dryRun=false} fecha em {@code FAILED} com a cópia antiga intocada.</p>
	 */
	@Test
	void transferencia_orfa_e_listada_e_fechada_sem_tocar_a_antiga() throws Exception {
		String id = mover();
		// A designação do destino sai como a soltura de pendência a deixaria.
		jdbc.update("delete from network_storage_placements where object_id = ? and replica = 3", objectId);

		mockMvc.perform(get("/admin/network/storage/rebalance/stale").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].id").value(id));

		// Seco por padrão: a lista mostra, nada é gravado.
		mockMvc.perform(post("/admin/network/storage/rebalance/stale/release").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.dryRun").value(true))
			.andExpect(jsonPath("$.stale.length()").value(1));
		assertThat(estadoDaTransferencia(id)).isEqualTo("PENDING");

		mockMvc.perform(post("/admin/network/storage/rebalance/stale/release").header("X-Session-Token", admin)
			.param("dryRun", "false"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.dryRun").value(false))
			.andExpect(jsonPath("$.stale.length()").value(1));

		assertThat(estadoDaTransferencia(id)).isEqualTo("FAILED");
		assertThat(estadoDaCopia(1)).isEqualTo("OK");
	}

	private String estadoDaTransferencia(String id) {
		return jdbc.queryForObject("select state from network_storage_rebalances where id = ?",
			(rs, row) -> rs.getString(1), id);
	}

	/** Sem admin não se move arquivo de nó nenhum. */
	@Test
	void sem_admin_nao_rebalanceia() throws Exception {
		mockMvc.perform(get("/admin/network/storage/rebalance")).andExpect(status().isUnauthorized());
	}

	private String mover() throws Exception {
		var result = mockMvc.perform(post("/admin/network/storage/rebalance").header("X-Session-Token", admin)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("objectId", objectId, "fromReplica", 1,
				"toNodeId", "nd_destino"))))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asString();
	}

	private String estadoDaCopia(int replica) {
		return jdbc.queryForObject("select state from network_storage_placements where object_id = ? and replica = ?",
			(rs, row) -> rs.getString(1), objectId, replica);
	}

	/** Objeto servível com duas réplicas {@code OK} em nós distintos. */
	private String objetoComDuasCopias() {
		String id = "sob_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
		Instant now = Instant.now();
		jdbc.update("""
			insert into network_storage_objects (id, bucket, object_key, content_type, bytes, sha256, state,
				created_by, created_at, updated_at)
			values (?, 'rebal', ?, 'image/jpeg', 10, ?, 'STORED', 'user-test', ?, ?)
			""", id, id + ".jpg", SHA, java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
		jdbc.update("""
			insert into network_storage_placements (object_id, replica, node_id, bytes, sha256, state, verified_at)
			values (?, 1, 'nd_cheio', 10, ?, 'OK', ?)
			""", id, SHA, java.sql.Timestamp.from(now));
		jdbc.update("""
			insert into network_storage_placements (object_id, replica, node_id, bytes, sha256, state, verified_at)
			values (?, 2, 'nd_outro', 10, ?, 'OK', ?)
			""", id, SHA, java.sql.Timestamp.from(now));
		return id;
	}

	private String adminToken() throws Exception {
		var session = auth.authenticate("net_rebal_admin_" + System.nanoTime());
		TestDatabase.setAdmin(session.userId());
		return session.token();
	}
}