package com.socialcoisa.rede.network.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
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

import com.socialcoisa.rede.network.application.NetworkAuthorityService;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestDatabase;

import tools.jackson.databind.ObjectMapper;

/**
 * Fase 6, Control Plane redundante: termo monotônico, eleitorado declarado e quórum explícito.
 *
 * <p>O defeito que estes testes fixam: até aqui qualquer Control Plane podia escrever o modo em
 * {@code network_state} e nada dizia qual autoridade era, nem desde quando. Dois Control Planes
 * subindo juntos não tinham como perceber que havia dois — e é exatamente essa invisibilidade que a
 * fase 6 existe para fechar (docs/rede-distribuida/06-contingencia.md §4).</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class NetworkAuthorityTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private NetworkAuthorityService authorities;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
		jdbc.update("delete from network_authorities");
		jdbc.update("delete from network_instance_readers");
		jdbc.update("delete from network_state");
		// Em produção a linha global é invariante (seed da V288): sem ela, o CAS da promoção
		// atualizaria zero linhas e TODA promoção falharia com PROMOTION_LOST. O teste mantém o
		// mesmo invariante em vez de medir a falha que o seed impede de existir.
		jdbc.update("insert into network_state (id, mode, reason, since, term) values ('global', 'NORMAL', ?, ?, 0)",
			"estado de teste", java.sql.Timestamp.from(Instant.now()));
		jdbc.update("delete from audit_logs where action like 'NETWORK_AUTHORITY_%'");
	}

	// ---------------------------------------------------------------- termo base

	@Test
	void semPromocaoOTermoEhZeroESemDono() throws Exception {
		var admin = adminSession();

		mockMvc.perform(get("/admin/network/authority").header("X-Session-Token", admin.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.term").value(0))
			.andExpect(jsonPath("$.authorityId").value(org.hamcrest.Matchers.nullValue()))
			.andExpect(jsonPath("$.declared").isEmpty());
	}

	// ---------------------------------------------------------------- sem eleitorado, ninguém promove

	/**
	 * O erro que o desenho existe para evitar: eleitorado derivado de "quem está respondendo". Com
	 * zero autoridades DECLARADAS, a metade isolada de uma partição se acharia 100% dos vivos e se
	 * promoveria sozinha. Declarar é decisão humana, e sem ela o quórum não fecha.
	 */
	@Test
	void semEleitoradoNinguemPromove() throws Exception {
		var admin = adminSession();
		alive("cp-1", Instant.now());

		mockMvc
			.perform(post("/admin/network/authority/promote").header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("instanceId", "cp-1", "reason", "teste"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("NETWORK_AUTHORITY_NOT_ALIVE"));

		assertThat(authorities.current().term()).isZero();
	}

	@Test
	void instanciaDeclaradaMasSemSinalNaoPromove() throws Exception {
		var admin = adminSession();
		authorities.declare(admin.userId(), "cp-1");
		authorities.declare(admin.userId(), "cp-2");
		// cp-2 deu sinal; cp-1 (a pedinte) não deu — não há prova de que existe.
		alive("cp-2", Instant.now());

		assertThatThrownBy(() -> authorities.promote(admin.userId(), "cp-1", "trocar de autoridade"))
			.isInstanceOf(ApiException.class)
			.satisfies(error -> assertThat(((ApiException) error).code()).isEqualTo("NETWORK_AUTHORITY_NOT_ALIVE"));
		assertThat(authorities.current().term()).isZero();
	}

	// ---------------------------------------------------------------- quórum

	/**
	 * Fail secure: com o eleitorado dividido ao meio (1 de 2 viva), ninguém promove. Melhor ficar sem
	 * controlador novo do que ter dois — a autoridade que está de pé continua valendo.
	 */
	@Test
	void semQuorumNinguemPromove() throws Exception {
		var admin = adminSession();
		authorities.declare(admin.userId(), "cp-1");
		authorities.declare(admin.userId(), "cp-2");
		alive("cp-1", Instant.now());

		assertThatThrownBy(() -> authorities.promote(admin.userId(), "cp-1", "metade da rede isolada"))
			.isInstanceOf(ApiException.class)
			.satisfies(error -> assertThat(((ApiException) error).code()).isEqualTo("NETWORK_AUTHORITY_NO_QUORUM"));
		// E o termo não andou: a autoridade original continua sendo a que vale.
		assertThat(authorities.current().term()).isZero();
	}

	@Test
	void comMaioriaVivaAPromocaoSobeOTermoEUmaVezSo() throws Exception {
		var admin = adminSession();
		authorities.declare(admin.userId(), "cp-1");
		authorities.declare(admin.userId(), "cp-2");
		authorities.declare(admin.userId(), "cp-3");
		alive("cp-1", Instant.now());
		alive("cp-2", Instant.now());

		var promoted = authorities.promote(admin.userId(), "cp-2", "cp-1 perdeu a região sudeste");

		assertThat(promoted.term()).isEqualTo(1);
		assertThat(promoted.authorityId()).isEqualTo("cp-2");
		assertThat(authorities.current().term()).isEqualTo(1);
		assertThat(authorities.current().authorityId()).isEqualTo("cp-2");

		// Auditoria com termo anterior e novo: sem isso, "promoveu" não diz de onde para onde.
		String metadata = jdbc.queryForObject(
			"select metadata from audit_logs where action = 'NETWORK_AUTHORITY_PROMOTED' and target_id = 'cp-2'",
			String.class);
		assertThat(objectMapper.readTree(metadata).get("from").asInt()).isZero();
		assertThat(objectMapper.readTree(metadata).get("term").asInt()).isEqualTo(1);
	}

	@Test
	void autoridadeRevogadaSaiDoEleitoradoENaoPromove() throws Exception {
		var admin = adminSession();
		authorities.declare(admin.userId(), "cp-1");
		authorities.declare(admin.userId(), "cp-2");
		alive("cp-1", Instant.now());
		alive("cp-2", Instant.now());
		assertThat(authorities.revoke(admin.userId(), "cp-2")).isTrue();

		assertThatThrownBy(() -> authorities.promote(admin.userId(), "cp-2", "ainda tentando"))
			.isInstanceOf(ApiException.class)
			.satisfies(error -> assertThat(((ApiException) error).code()).isEqualTo("NETWORK_AUTHORITY_NOT_ALIVE"));
	}

	@Test
	void promocaoSemMotivoERecusada() throws Exception {
		var admin = adminSession();
		authorities.declare(admin.userId(), "cp-1");
		alive("cp-1", Instant.now());

		assertThatThrownBy(() -> authorities.promote(admin.userId(), "cp-1", "  "))
			.isInstanceOf(ApiException.class)
			.satisfies(error -> assertThat(((ApiException) error).code())
				.isEqualTo("NETWORK_AUTHORITY_REASON_REQUIRED"));
		assertThat(authorities.current().term()).isZero();
	}

	// ---------------------------------------------------------------- termo na config assinada

	/**
	 * O termo entra no documento E na assinatura: é o que permite ao nó recusar config de termo menor
	 * conferindo contra a chave pinada, em vez de confiar no transporte. Um documento sem termo na
	 * linha assinada deixaria o número editável e a regra do nó decorativa.
	 */
	@Test
	void configDoNoCarregaOTermoVigenteEDeclaraOFormatoNovo() throws Exception {
		var admin = adminSession();
		authorities.declare(admin.userId(), "cp-1");
		alive("cp-1", Instant.now());
		authorities.promote(admin.userId(), "cp-1", "primeira promocao");
		var node = activeNode(admin.token(), "no-termo");

		mockMvc
			.perform(get("/network/nodes/me/config").header("X-Node-Id", node.id()).header("X-Node-Key", node.key()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.term").value(1))
			.andExpect(jsonPath("$.formatVersion").value(4));
	}

	// ---------------------------------------------------------------- painel

	@Test
	void membroComumNaoLeNemPromoveAutoridade() throws Exception {
		var member = auth.authenticate("net_auth_member");
		authorities.declare("system", "cp-1");

		mockMvc.perform(get("/admin/network/authority").header("X-Session-Token", member.token()))
			.andExpect(status().isForbidden());
		mockMvc
			.perform(post("/admin/network/authority/promote").header("X-Session-Token", member.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("instanceId", "cp-1", "reason", "teste"))))
			.andExpect(status().isForbidden());
		mockMvc
			.perform(post("/admin/network/authority/declared").header("X-Session-Token", member.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("instanceId", "cp-2"))))
			.andExpect(status().isForbidden());
	}

	@Test
	void painelDeclaraERevogaAutoridade() throws Exception {
		var admin = adminSession();

		mockMvc
			.perform(post("/admin/network/authority/declared").header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("instanceId", "cp-1"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.declared[0]").value("cp-1"));

		mockMvc
			.perform(delete("/admin/network/authority/declared/cp-1").header("X-Session-Token", admin.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.declared").isEmpty());
	}

	@Test
	void revogarInstanciaNaoDeclaradaRespondeNaoEncontrado() throws Exception {
		var admin = adminSession();

		mockMvc.perform(delete("/admin/network/authority/declared/cp-fantasma")
			.header("X-Session-Token", admin.token())).andExpect(status().isNotFound());
	}

	// ---------------------------------------------------------------- helpers

	private record Node(String id, String key) {
	}

	private record Session(String token, String userId) {
	}

	private Session adminSession() throws Exception {
		var session = auth.authenticate("net_auth_admin");
		TestDatabase.setAdmin(session.userId());
		return new Session(session.token(), session.userId());
	}

	/** Marca a instância como leitora viva, que é o que {@code aliveSince} lê (V278). */
	private void alive(String instanceId, Instant seenAt) {
		Timestamp at = Timestamp.from(seenAt);
		jdbc.update("""
				insert into network_instance_readers (instance_id, started_at, last_seen_at, last_seq)
				values (?, ?, ?, 0)
				on duplicate key update last_seen_at = values(last_seen_at)
				""", instanceId, at, at);
	}

	private Node activeNode(String admin, String name) throws Exception {
		var tokenResult = mockMvc
			.perform(post("/admin/network/nodes/provision-token").header("X-Session-Token", admin)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("displayName", name, "ownerUserId", "owner-" + name,
					"profiles", List.of("NODE_WORKER")))))
			.andExpect(status().isOk())
			.andReturn();
		String provisionToken = objectMapper.readTree(tokenResult.getResponse().getContentAsString())
			.get("token")
			.asString();

		var enrollResult = mockMvc
			.perform(post("/network/nodes/enroll").contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("provisionToken", provisionToken, "declared",
					Map.of("cpuCores", 2, "memoryMb", 4096, "diskMb", 20480, "bandwidthMbps", 50)))))
			.andExpect(status().isOk())
			.andReturn();
		String nodeId = objectMapper.readTree(enrollResult.getResponse().getContentAsString()).get("nodeId")
			.asString();

		mockMvc.perform(post("/admin/network/nodes/" + nodeId + "/approve").header("X-Session-Token", admin))
			.andExpect(status().isOk());
		var keyResult = mockMvc
			.perform(post("/admin/network/nodes/" + nodeId + "/keys").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andReturn();
		String key = objectMapper.readTree(keyResult.getResponse().getContentAsString()).get("apiKey").asString();
		return new Node(nodeId, key);
	}

}
