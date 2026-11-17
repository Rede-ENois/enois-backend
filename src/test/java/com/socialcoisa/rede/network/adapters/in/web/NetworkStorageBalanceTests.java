package com.socialcoisa.rede.network.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestDatabase;

import tools.jackson.databind.ObjectMapper;

/**
 * A rota do equilíbrio responde pelo caminho real, sobre os nós que o banco tem.
 *
 * <p>O que este teste garante além dos unitários: a rota exige admin, o corpo tem o formato do
 * contrato ({@code measurable}, {@code nodes}, {@code skewed}) e o campo de disco de um nó sem
 * medida chega como <b>nulo</b> — não como zero —, porque zero diria "sem espaço".</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class NetworkStorageBalanceTests {

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
		jdbc.update("delete from network_storage_placements");
		jdbc.update("delete from network_storage_objects");
		jdbc.update("delete from network_node_keys");
		jdbc.update("delete from network_provision_tokens");
		jdbc.update("delete from network_nodes");
	}

	@Test
	void oEquilibrioExigeAdmin() throws Exception {
		mockMvc.perform(get("/admin/network/nodes/storage/balance")).andExpect(status().isUnauthorized());
	}

	@Test
	void semNoDeArmazenamentoNaoInventaBase() throws Exception {
		String admin = adminToken();

		var result = mockMvc
			.perform(get("/admin/network/nodes/storage/balance").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.nodesConsidered").value(0))
			.andExpect(jsonPath("$.measurable").value(false))
			.andReturn();

		var tree = objectMapper.readTree(result.getResponse().getContentAsString());
		assertThat(tree.get("nodes")).isEmpty();
		assertThat(tree.get("skewed")).isEmpty();
	}

	@Test
	void noDeArmazenamentoSemDiscoMedidoApareceComDiscoNulo() throws Exception {
		String admin = adminToken();
		// Nó ACTIVE e fresco, com perfil de armazenamento e SEM disco medido. Insert direto: o
		// enroll + heartbeat exigiriam passar pelo caminho do runtime, que sempre manda o medido.
		insertNode("nd_sem_medida");

		var result = mockMvc
			.perform(get("/admin/network/nodes/storage/balance").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.nodesConsidered").value(1))
			.andReturn();

		var tree = objectMapper.readTree(result.getResponse().getContentAsString());
		var node = tree.get("nodes").get(0);
		assertThat(node.get("nodeId").asString()).isEqualTo("nd_sem_medida");
		// Nulo: sem medida não há folga conhecida. `0` seria "sem espaço".
		assertThat(node.get("diskFreeMb").isNull()).isTrue();
		assertThat(node.get("capacitySharePercent").isNull()).isTrue();
		// Sem régua não se afirma equilíbrio nem desequilíbrio.
		assertThat(tree.get("measurable").asBoolean()).isFalse();
		assertThat(tree.get("skewed")).isEmpty();
	}

	/**
	 * O Storage no painel: capacidade declarada, disponível MEDIDO e uso MEDIDO.
	 *
	 * <p>O nó declara 100 GB e reporta 4 GB livres: o painel tem de mostrar os dois como coisas
	 * diferentes. Um objeto STORED com uma cópia OK de 10 bytes entra em `usedBytes`; uma designação
	 * PENDING_REMOTE de 999 bytes não entra, porque não está em disco nenhum.</p>
	 */
	@Test
	void aVisaoDeStorageDistingueDeclaradoDeMedidoEAceitaSoCopiaConfirmada() throws Exception {
		String admin = adminToken();
		insertNode("nd_ov");
		jdbc.update("update network_nodes set observed_disk_free_mb = 4000 where id = 'nd_ov'");
		jdbc.update("""
			insert into network_storage_objects (id, bucket, object_key, content_type, bytes, sha256, state,
			  created_by, created_at, updated_at)
			values ('sob_ov', 'fotos', 'p.jpg', 'image/jpeg', 10, repeat('a', 64), 'STORED', 'u', now(3), now(3))
			""");
		jdbc.update("""
			insert into network_storage_placements (object_id, replica, node_id, bytes, sha256, state, verified_at)
			values ('sob_ov', 0, 'nd_ov', 10, repeat('a', 64), 'OK', now(3)),
			       ('sob_ov', 1, 'nd_ov', 999, repeat('a', 64), 'PENDING_REMOTE', now(3))
			""");

		mockMvc.perform(get("/admin/network/nodes/storage/overview").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.capacityMb").value(102400))
			.andExpect(jsonPath("$.freeMb").value(4000))
			.andExpect(jsonPath("$.nodesDeclaringCapacity").value(1))
			.andExpect(jsonPath("$.unmeasuredNodes").value(0))
			// A reserva de cada volume vai junto: total não reportado (V341 não aplicada a este nó)
			// é linha com totalMb e reservedMb nulos — nunca zero, que diria "não reservou nada".
			.andExpect(jsonPath("$.disks[0].nodeId").value("nd_ov"))
			.andExpect(jsonPath("$.disks[0].totalMb").doesNotExist())
			.andExpect(jsonPath("$.disks[0].reservedMb").doesNotExist())
			// Só a cópia OK: a designação de 999 bytes não é espaço ocupado.
			.andExpect(jsonPath("$.usedBytes").value(10))
			.andExpect(jsonPath("$.objectsByState.STORED").value(1))
			.andExpect(jsonPath("$.copiesByState.PENDING_REMOTE").value(1))
			.andExpect(jsonPath("$.replicasExpected").value(2));
	}

	@Test
	void aVisaoDeStorageMostraReservaComVolumeMedido() throws Exception {
		String admin = adminToken();
		insertNode("nd_res");
		// 2 TB de volume, 1,5 TB cedidos: reserva de 0,5 TB — a conta do brief §7, com o número que
		// o próprio nó mediu (V341), não o que ele prometeu.
		jdbc.update("update network_nodes set observed_disk_free_mb = 400000, observed_disk_total_mb = 2097152 "
			+ "where id = 'nd_res'");
		jdbc.update("""
			insert into network_storage_objects (id, bucket, object_key, content_type, bytes, sha256, state,
			  created_by, created_at, updated_at)
			values ('sob_res', 'fotos', 'r.jpg', 'image/jpeg', 10, repeat('b', 64), 'STORED', 'u', now(3), now(3))
			""");

		mockMvc.perform(get("/admin/network/nodes/storage/overview").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.disks[0].nodeId").value("nd_res"))
			.andExpect(jsonPath("$.disks[0].declaredMb").value(102400))
			.andExpect(jsonPath("$.disks[0].totalMb").value(2097152))
			.andExpect(jsonPath("$.disks[0].freeMb").value(400000))
			.andExpect(jsonPath("$.disks[0].reservedMb").value(2097152 - 102400))
			.andExpect(jsonPath("$.unmeasuredDiskTotals").value(0));
	}

	@Test
	void aVisaoDeStorageExigeAdmin() throws Exception {
		mockMvc.perform(get("/admin/network/nodes/storage/overview")).andExpect(status().isUnauthorized());
	}

	// ---------------------------------------------------------------- apoio

	private void insertNode(String id) {
		jdbc.update("""
			insert into network_nodes (id, display_name, owner_user_id, status, profiles, declared_cpu_cores,
			  declared_memory_mb, declared_disk_mb, declared_bandwidth_mbps, region, country, created_at, last_seen_at)
			values (?, ?, 'owner-balance', 'ACTIVE', 'NODE_STORAGE', 4, 8192, 102400, 100, 'sudeste', 'BR', now(3), now(3))
			""", id, "no-" + id);
	}

	private String adminToken() throws Exception {
		var session = auth.authenticate("net_balance_admin");
		TestDatabase.setAdmin(session.userId());
		return session.token();
	}
}