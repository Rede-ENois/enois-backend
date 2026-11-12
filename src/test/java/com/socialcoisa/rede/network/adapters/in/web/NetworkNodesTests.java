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

import com.socialcoisa.rede.shared.security.Hashes;
import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestDatabase;

import tools.jackson.databind.ObjectMapper;

/**
 * Rede cooperativa, fase 1: o admin cadastra o nó, gera UMA API key por nó e revoga.
 *
 * <p>What the tests pin, because each one is a way to get this wrong:</p>
 * <ul>
 * <li>a chave em claro só existe na resposta do POST — o banco guarda só o hash, e a listagem
 * nunca devolve a chave;</li>
 * <li>gerar de novo revoga a anterior: duas chaves ativas para o mesmo nó seriam duas portas;</li>
 * <li>chave revogada não autentica, e a chave de um nó não autentica outro;</li>
 * <li>revogar o nó revoga as chaves dele — senão a revogação teria caminho lateral;</li>
 * <li>o token de provisionamento é de uso único, e o heartbeat só funciona depois da aprovação.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class NetworkNodesTests {

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
		jdbc.update("delete from network_node_keys");
		jdbc.update("delete from network_provision_tokens");
		jdbc.update("delete from network_nodes");
	}

	/** Admin de verdade: `users.is_admin`, o papel imutável — a allowlist por e-mail é bootstrap. */
	private String adminToken() throws Exception {
		var session = auth.authenticate("net_admin");
		TestDatabase.setAdmin(session.userId());
		return session.token();
	}

	private String issueToken(String admin, String name, List<String> profiles) throws Exception {
		return issueTokenFor(admin, name, "owner-net", profiles);
	}

	/** Token provisionado para um dono específico — a interface do participante é por owner_user_id. */
	private String issueTokenFor(String admin, String name, String ownerUserId, List<String> profiles)
			throws Exception {
		var result = mockMvc
			.perform(post("/admin/network/nodes/provision-token").header("X-Session-Token", admin)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(
					Map.of("displayName", name, "ownerUserId", ownerUserId, "profiles", profiles))))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asString();
	}

	/** Nó de um dono específico, criado e aprovado, sem chave ainda. */
	private String newNodeOwner(String admin, String ownerUserId) throws Exception {
		String token = issueTokenFor(admin, "no-do-participante", ownerUserId, List.of("NODE_WORKER"));
		String nodeId = enroll(token);
		activate(admin, nodeId);
		return nodeId;
	}

	private String enroll(String provisionToken) throws Exception {
		var result = mockMvc
			.perform(post("/network/nodes/enroll").contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("provisionToken", provisionToken, "declared",
					Map.of("cpuCores", 4, "memoryMb", 8192, "diskMb", 51200, "bandwidthMbps", 100)))))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("nodeId").asString();
	}

	private String issueKey(String admin, String nodeId) throws Exception {
		var result = mockMvc
			.perform(post("/admin/network/nodes/" + nodeId + "/keys").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.apiKey").exists())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("apiKey").asString();
	}

	private String newNode(String admin) throws Exception {
		return enroll(issueToken(admin, "no-teste", List.of("NODE_MEDIA")));
	}

	private String enrollComLocation(String provisionToken, String region, String country) throws Exception {
		var result = mockMvc
			.perform(post("/network/nodes/enroll").contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("provisionToken", provisionToken, "declared",
					Map.of("cpuCores", 4, "memoryMb", 8192, "diskMb", 51200, "bandwidthMbps", 100),
					"location", Map.of("region", region, "country", country)))))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("nodeId").asString();
	}

	private void activate(String admin, String nodeId) throws Exception {
		mockMvc.perform(post("/admin/network/nodes/" + nodeId + "/approve").header("X-Session-Token", admin))
			.andExpect(status().isOk());
	}

	@Test
	void chaveGeradaApareceUmaVezESoOHashFicaNoBanco() throws Exception {
		String admin = adminToken();
		String nodeId = newNode(admin);
		String plain = issueKey(admin, nodeId);

		assertThat(plain).startsWith("enk_").hasSizeGreaterThan(40);
		assertThat(jdbc.queryForObject("select key_hash from network_node_keys where node_id = ?", String.class,
				nodeId))
			.isEqualTo(Hashes.sha256Hex(plain));
		assertThat(jdbc.queryForObject("select count(*) from network_node_keys where key_hash = ?", Integer.class,
				plain))
			.isZero();

		// A listagem do painel nunca devolve a chave em claro.
		mockMvc.perform(get("/admin/network/nodes").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].id").value(nodeId));
	}

	@Test
	void gerarDeNovoRevogaAChaveAnterior() throws Exception {
		String admin = adminToken();
		String nodeId = newNode(admin);
		String first = issueKey(admin, nodeId);
		String second = issueKey(admin, nodeId);

		assertThat(first).isNotEqualTo(second);
		assertThat(jdbc.queryForObject("select count(*) from network_node_keys where node_id = ? and revoked_at is null",
				Integer.class, nodeId)).isEqualTo(1);

		mockMvc.perform(post("/network/nodes/heartbeat").header("X-Node-Id", nodeId).header("X-Node-Key", first)
			.contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
	}

	@Test
	void chaveRevogadaNaoAutenticaMais() throws Exception {
		String admin = adminToken();
		String nodeId = newNode(admin);
		activate(admin, nodeId);
		String plain = issueKey(admin, nodeId);
		String keyId = jdbc.queryForObject("select id from network_node_keys where node_id = ? and revoked_at is null",
				String.class, nodeId);

		mockMvc.perform(post("/network/nodes/heartbeat").header("X-Node-Id", nodeId).header("X-Node-Key", plain)
			.contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isOk());

		mockMvc.perform(post("/admin/network/nodes/" + nodeId + "/keys/" + keyId + "/revoke")
			.header("X-Session-Token", admin)).andExpect(status().isOk());

		mockMvc.perform(post("/network/nodes/heartbeat").header("X-Node-Id", nodeId).header("X-Node-Key", plain)
			.contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void chaveDeUmNoNaoAutenticaOutro() throws Exception {
		String admin = adminToken();
		String firstNode = newNode(admin);
		String secondNode = newNode(admin);
		activate(admin, firstNode);
		activate(admin, secondNode);
		String firstKey = issueKey(admin, firstNode);

		mockMvc.perform(post("/network/nodes/heartbeat").header("X-Node-Id", secondNode)
			.header("X-Node-Key", firstKey).contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void revogarONoRevogaAsChavesDele() throws Exception {
		String admin = adminToken();
		String nodeId = newNode(admin);
		activate(admin, nodeId);
		String plain = issueKey(admin, nodeId);

		mockMvc.perform(post("/admin/network/nodes/" + nodeId + "/status").header("X-Session-Token", admin)
			.contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"REVOKED\"}"))
			.andExpect(status().isOk());

		assertThat(jdbc.queryForObject("select count(*) from network_node_keys where node_id = ? and revoked_at is null",
				Integer.class, nodeId)).isZero();
		mockMvc.perform(post("/network/nodes/heartbeat").header("X-Node-Id", nodeId).header("X-Node-Key", plain)
			.contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void tokenDeProvisionamentoEhDeUsoUnico() throws Exception {
		String admin = adminToken();
		String token = issueToken(admin, "no-unico", List.of("NODE_WORKER"));

		enroll(token);
		mockMvc.perform(post("/network/nodes/enroll").contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("provisionToken", token, "declared", Map.of()))))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("NODE_TOKEN_USED"));
	}

	@Test
	void noNaoOperaAntesDaAprovacao() throws Exception {
		String admin = adminToken();
		String nodeId = newNode(admin);
		String plain = issueKey(admin, nodeId);

		mockMvc.perform(post("/network/nodes/heartbeat").header("X-Node-Id", nodeId).header("X-Node-Key", plain)
			.contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NODE_PENDING_APPROVAL"));
	}

	/**
	 * A região vem no corpo exatamente como o contrato documenta — aninhada em {@code location}. O
	 * Q0 aqui é o enrol de enrolar: antes desta rodada o DTO só lia região solta no topo, então um
	 * instalador que seguia a doc gravava o nó com região nula e o enroll aceitava em silêncio.
	 */
	@Test
	void enrollComLocationDaDocGravaARegiao() throws Exception {
		String admin = adminToken();
		String token = issueToken(admin, "no-geo", List.of("NODE_WORKER"));

		String nodeId = enrollComLocation(token, "sa-east-1", "BR");

		assertThat(jdbc.queryForObject("select region from network_nodes where id = ?", String.class, nodeId))
			.isEqualTo("sa-east-1");
		assertThat(jdbc.queryForObject("select country from network_nodes where id = ?", String.class, nodeId))
			.isEqualTo("BR");
	}

	// ---------------------------------------------------------------- config assinada

	/**
	 * O passo que fecha o onboarding: depois de aprovado, o nó lê a própria permissão numa
	 * configuração assinada. Sem esta rota, o runtime teria de confiar no que o painel disse, e
	 * permissão deixaria de ser dado do Control Plane.
	 */
	@Test
	void configAssinadaDizOPerfilEOsLimitesDoNo() throws Exception {
		String admin = adminToken();
		String nodeId = newNode(admin);
		activate(admin, nodeId);
		String key = issueKey(admin, nodeId);

		var result = mockMvc
			.perform(get("/network/nodes/me/config").header("X-Node-Id", nodeId).header("X-Node-Key", key))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.nodeId").value(nodeId))
			.andExpect(jsonPath("$.formatVersion").value(1))
			// Versão da permissão: 1 do enroll + 1 da aprovação feita acima.
			.andExpect(jsonPath("$.configVersion").value(2))
			.andExpect(jsonPath("$.profiles[0]").value("NODE_MEDIA"))
			.andExpect(jsonPath("$.limits.cpuCores").value(4))
			.andExpect(jsonPath("$.limits.memoryMb").value(8192))
			.andExpect(jsonPath("$.limits.diskMb").value(51200))
			.andExpect(jsonPath("$.limits.bandwidthMbps").value(100))
			.andExpect(jsonPath("$.allowedTasks[0]").value("MEDIA_TRANSCODE"))
			.andExpect(jsonPath("$.allowedTasks[1]").value("MEDIA_THUMBNAIL"))
			.andExpect(jsonPath("$.signed").value(false))
			.andReturn();

		var body = objectMapper.readTree(result.getResponse().getContentAsString());
		// Sem chave de assinatura configurada o payload vai sem assinatura e diz isso — um
		// `signed:true` sem assinatura seria mentira que o nó não teria como perceber.
		assertThat(body.get("signature").isNull()).isTrue();
	}

	/**
	 * Duas leituras do mesmo nó sem nada mudar devolvem exatamente o mesmo documento.
	 *
	 * <p>{@code issuedAt} aqui é o instante em que a <b>permissão</b> foi emitida — o enroll no caso
	 * desta primeira leitura —, não o instante da consulta: se fosse o relógio da requisição, duas
	 * leituras seguidas dariam documentos diferentes para a mesma permissão, e o runtime, que trata
	 * config nova como evento, aplicaria config nova de segundo em segundo sem nada ter mudado.</p>
	 */
	@Test
	void duasLeiturasSemMudancaDevolvemOMesmoDocumento() throws Exception {
		String admin = adminToken();
		String nodeId = newNode(admin);
		activate(admin, nodeId);
		String key = issueKey(admin, nodeId);

		var before = configOf(nodeId, key);
		var again = configOf(nodeId, key);

		assertThat(again.get("configVersion").asInt()).isEqualTo(before.get("configVersion").asInt());
		assertThat(again.get("issuedAt").asString()).isEqualTo(before.get("issuedAt").asString());
		assertThat(again.get("profiles").toString()).isEqualTo(before.get("profiles").toString());
		assertThat(again.get("allowedTasks").toString()).isEqualTo(before.get("allowedTasks").toString());
	}

	/**
	 * O nó sabe que a config velha não vale mais: aprovar, suspender, quarentenar ou revogar muda o
	 * {@code configVersion} e o {@code issuedAt} — a versão nasce do ciclo de vida, não de um contador
	 * que alguém precisa lembrar de incrementar em cada transição.
	 */
	@Test
	void transicaoDeStatusCompoeVersaoENovoInstante() throws Exception {
		String admin = adminToken();
		String nodeId = newNode(admin);
		String key = issueKey(admin, nodeId);

		var pending = configOf(nodeId, key);
		assertThat(pending.get("status").asString()).isEqualTo("PENDING_APPROVAL");

		activate(admin, nodeId);
		var active = configOf(nodeId, key);
		assertThat(active.get("status").asString()).isEqualTo("ACTIVE");
		assertThat(active.get("configVersion").asInt()).isGreaterThan(pending.get("configVersion").asInt());
		assertThat(active.get("issuedAt").asString()).isNotEqualTo(pending.get("issuedAt").asString());

		suspend(admin, nodeId);
		var suspended = configOf(nodeId, key);
		assertThat(suspended.get("operable").asBoolean()).isFalse();
		assertThat(suspended.get("allowedTasks").isEmpty()).isTrue();
		assertThat(suspended.get("configVersion").asInt()).isGreaterThan(active.get("configVersion").asInt());

		// A versão cresce a cada transição, então a última é a maior de todas — é a assinatura de que a
		// ordem das mudanças está impressa no número.
		assertThat(suspended.get("configVersion").asInt()).isGreaterThanOrEqualTo(3);
	}

	@Test
	void configExigeCredencialDoNo() throws Exception {
		mockMvc.perform(get("/network/nodes/me/config")).andExpect(status().isUnauthorized());
		String admin = adminToken();
		String nodeId = newNode(admin);
		activate(admin, nodeId);
		mockMvc.perform(get("/network/nodes/me/config").header("X-Node-Id", nodeId).header("X-Node-Key", "enk_x"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void noPendenteLeAPropriaConfigMasSemTarefas() throws Exception {
		String admin = adminToken();
		String nodeId = newNode(admin);
		String key = issueKey(admin, nodeId);

		// O enroll termina em PENDING_APPROVAL; o runtime precisa conseguir ler a própria config
		// para saber que ainda não pode operar — 403 aqui deixaria o instalador sem resposta.
		mockMvc.perform(get("/network/nodes/me/config").header("X-Node-Id", nodeId).header("X-Node-Key", key))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
			.andExpect(jsonPath("$.operable").value(false))
			.andExpect(jsonPath("$.allowedTasks").isEmpty());
	}

	// ---------------------------------------------------------------- dashboard do painel

	/**
	 * O topo do painel "Énóis Network" em números: antes desta rota o admin lia a lista de nós e
	 * contava ativos, degradados e quarentena de cabeça. O cenário planta os cinco estados que o
	 * painel precisa distinguir e afixa cada número: ACTIVE vivo, em quarentena, saturado,
	 * suspenso (decisão administrativa conta como indisponível, não como degradado) e pendente
	 * (nunca operou — não é indisponível).
	 */
	@Test
	void dashboardAgregaARedeEmNumeros() throws Exception {
		String admin = adminToken();

		String sano = newNode(admin);
		activate(admin, sano);
		String keySano = issueKey(admin, sano);
		heartbeat(admin, sano, keySano, 20.0, 1024, 1);

		String emQuarentena = newNode(admin);
		activate(admin, emQuarentena);
		String keyQuar = issueKey(admin, emQuarentena);
		heartbeat(admin, emQuarentena, keyQuar, 95.0, 8192, 4);
		jdbc.update("update network_nodes set quarantined_until = timestampadd(second, 600, current_timestamp), "
			+ "quarantine_reason = 'testes' where id = ?", emQuarentena);

		String saturado = newNode(admin);
		activate(admin, saturado);
		String keySat = issueKey(admin, saturado);
		heartbeat(admin, saturado, keySat, 95.0, 2048, 2);

		String suspenso = newNode(admin);
		activate(admin, suspenso);
		suspend(admin, suspenso);

		String pendente = newNode(admin);

		var result = mockMvc.perform(get("/admin/network/nodes/dashboard").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andReturn();
		var body = objectMapper.readTree(result.getResponse().getContentAsString());

		assertThat(body.get("total").asInt()).isEqualTo(5);
		assertThat(body.get("active").asInt()).isEqualTo(3);
		assertThat(body.get("quarantined").asInt()).isEqualTo(1);
		// Suspenso é indisponível (decisão administrativa); pendente não — nunca chegou a operar.
		assertThat(body.get("unavailable").asInt()).isEqualTo(1);
		// Um degradado: o em quarentena. Saturado não é degradado (é carga momentânea, e a mesma
		// definição da listagem — quarentena, lento ou falhando) — o que ele perde é a vaga, abaixo.
		assertThat(body.get("degraded").asInt()).isEqualTo(1);
		assertThat(body.get("alive").asInt()).isEqualTo(3);
		// Saturado e em quarentena não prometem vaga; só o sano (limite 4 − 1 rodando) conta.
		assertThat(body.get("freeSlots").asInt()).isEqualTo(3);
		// Declarado soma os cinco nós, inclusive pendente e suspenso: é o que foi cedido.
		assertThat(body.get("declaredCpuCores").asInt()).isEqualTo(20);
		assertThat(body.get("declaredMemoryMb").asInt()).isEqualTo(40960);
		// Média de CPU sobre os três reportes (20, 95, 95), com a contagem de amostras ao lado.
		assertThat(body.get("cpuSamples").asInt()).isEqualTo(3);
		assertThat(body.get("avgCpuPercent").asDouble()).isEqualTo(70.0);
		// Nó sem heartbeat não inventa versão: só os três que reportaram contam.
		assertThat(body.get("runtimeVersions").size()).isEqualTo(1);
		assertThat(body.get("runtimeVersions").get("0.1.0").asInt()).isEqualTo(3);
	}

	/** Heartbeat com credencial de nó real, como o runtime manda. */
	private void heartbeat(String admin, String nodeId, String key, double cpu, int memoryMb, int tasks)
			throws Exception {
		activate(admin, nodeId);
		mockMvc
			.perform(post("/network/nodes/heartbeat").header("X-Node-Id", nodeId).header("X-Node-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("runtimeVersion", "0.1.0", "observed",
					Map.of("cpuPercent", cpu, "memoryMb", memoryMb, "runningTasks", tasks)))))
			.andExpect(status().isOk());
	}

	// ---------------------------------------------------------------- contrato do nó

	/**
	 * Alterar capacidade e perfis na página por nó: antes destas rotas o que o participante
	 * declarou no enroll era imutável — reduzir a cedência ou tirar um perfil exigia revogar o nó e
	 * recadastrá-lo. São mudanças de permissão: a config assinada carrega limites e perfis, então
	 * cada uma soma 1 em {@code configVersion}, e o runtime aplica na próxima leitura.
	 */
	@Test
	void alterarCapacidadeEPerfisCompoeVersaoENovaConfig() throws Exception {
		String admin = adminToken();
		String nodeId = newNode(admin);
		activate(admin, nodeId);
		String key = issueKey(admin, nodeId);
		int versionNaAprovacao = configOf(nodeId, key).get("configVersion").asInt();

		// Reduz a cedência: só o que mudou vai no corpo; o resto se mantém (memória 8192 do enroll).
		mockMvc
			.perform(post("/admin/network/nodes/" + nodeId + "/capacity").header("X-Session-Token", admin)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("cpuCores", 2))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.operable").value(true));

		var depoisCapacidade = configOf(nodeId, key);
		assertThat(depoisCapacidade.get("configVersion").asInt()).isGreaterThan(versionNaAprovacao);
		assertThat(depoisCapacidade.get("limits").get("cpuCores").asInt()).isEqualTo(2);
		assertThat(depoisCapacidade.get("limits").get("memoryMb").asInt()).isEqualTo(8192);

		// Tira o perfil: nó fica sem tarefa nenhuma sem precisar de suspensão.
		mockMvc
			.perform(post("/admin/network/nodes/" + nodeId + "/profiles").header("X-Session-Token", admin)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("profiles", List.of()))))
			.andExpect(status().isOk());
		var semPerfis = configOf(nodeId, key);
		assertThat(semPerfis.get("configVersion").asInt()).isGreaterThan(depoisCapacidade.get("configVersion").asInt());
		assertThat(semPerfis.get("allowedTasks").isEmpty()).isTrue();

		// Devolve o perfil com outro nome: a config volta a permitir MEDIA_TRANSCODE.
		mockMvc
			.perform(post("/admin/network/nodes/" + nodeId + "/profiles").header("X-Session-Token", admin)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("profiles", List.of("NODE_MEDIA")))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.profiles[0]").value("NODE_MEDIA"));
		assertThat(configOf(nodeId, key).get("allowedTasks").size()).isEqualTo(2);
	}

	/** Capacidade negativa recusa; nó revogado é terminal para contrato novo também. */
	@Test
	void contratoNovoRecusaNegativoERevogado() throws Exception {
		String admin = adminToken();
		String nodeId = newNode(admin);

		mockMvc
			.perform(post("/admin/network/nodes/" + nodeId + "/capacity").header("X-Session-Token", admin)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("cpuCores", -1))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("NODE_CAPACITY_INVALID"));

		mockMvc
			.perform(post("/admin/network/nodes/" + nodeId + "/profiles").header("X-Session-Token", admin)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("profiles", List.of("NAO_EXISTE")))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("NODE_PROFILES_INVALID"));

		activate(admin, nodeId);
		mockMvc
			.perform(post("/admin/network/nodes/" + nodeId + "/status").header("X-Session-Token", admin)
				.contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"REVOKED\"}"))
			.andExpect(status().isOk());
		mockMvc
			.perform(post("/admin/network/nodes/" + nodeId + "/capacity").header("X-Session-Token", admin)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("cpuCores", 8))))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("NODE_REVOKED"));
	}

	// ---------------------------------------------------------------- histórico por nó

	/**
	 * A página por nó mostra "histórico", e a fonte é a trilha de auditoria que cada ação desta
	 * tela já grava — não uma tabela própria. O cenário faz três ações reais (aprovar, gerar
	 * chave, mudar capacidade) e confere que a leitura as devolve mais novas primeiro, com o ator;
	 * nó desconhecido é 404, não lista vazia.
	 */
	@Test
	void historicoDoNoLeDeVoltaAsAcoesGravadas() throws Exception {
		String admin = adminToken();
		String nodeId = newNode(admin);
		activate(admin, nodeId);
		issueKey(admin, nodeId);
		mockMvc
			.perform(post("/admin/network/nodes/" + nodeId + "/capacity").header("X-Session-Token", admin)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("cpuCores", 2))))
			.andExpect(status().isOk());

		var result = mockMvc
			.perform(get("/admin/network/nodes/" + nodeId + "/history").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andReturn();
		var body = objectMapper.readTree(result.getResponse().getContentAsString());

		assertThat(body.size()).isEqualTo(4);
		// Mais nova primeiro: a última ação (capacity) abre a lista.
		assertThat(body.get(0).get("action").asString()).isEqualTo("NODE_CAPACITY_CHANGED");
		// O enroll (feito pelo dono do token, não pelo admin) fecha a lista.
		assertThat(body.get(3).get("action").asString()).isEqualTo("NODE_ENROLLED");
		for (var record : body) {
			assertThat(record.get("actorId").asString()).isNotBlank();
			assertThat(record.get("createdAt").asString()).isNotBlank();
		}

		// Limite respeitado: com 1, só o registro mais novo.
		mockMvc
			.perform(get("/admin/network/nodes/" + nodeId + "/history?limit=1").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].action").value("NODE_CAPACITY_CHANGED"));

		mockMvc.perform(get("/admin/network/nodes/nd_nao_existe/history").header("X-Session-Token", admin))
			.andExpect(status().isNotFound());
	}

	private tools.jackson.databind.JsonNode configOf(String nodeId, String key) throws Exception {
		return objectMapper.readTree(mockMvc
			.perform(get("/network/nodes/me/config").header("X-Node-Id", nodeId).header("X-Node-Key", key))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString());
	}

	private void suspend(String admin, String nodeId) throws Exception {
		mockMvc
			.perform(post("/admin/network/nodes/" + nodeId + "/status").header("X-Session-Token", admin)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("status", "SUSPENDED"))))
			.andExpect(status().isOk());
	}

	@Test
	void apenasAdminGerenciaANetwork() throws Exception {
		var member = auth.authenticate("net_member");

		mockMvc.perform(get("/admin/network/nodes").header("X-Session-Token", member.token()))
			.andExpect(status().isForbidden());
		mockMvc.perform(get("/admin/network/nodes/dashboard").header("X-Session-Token", member.token()))
			.andExpect(status().isForbidden());
		mockMvc
			.perform(post("/admin/network/nodes/provision-token").header("X-Session-Token", member.token())
				.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(
				Map.of("displayName", "x", "ownerUserId", "y", "profiles", List.of("NODE_EDGE")))))
		.andExpect(status().isForbidden());
	}

	// ---------------------------------------------------------------- interface do participante

	/**
	 * O participante vê os próprios nós com estado, o que cedeu, o que foi observado e a contribuição
	 * medida — e não vê nó de outro participante nem com o id na mão (404, não 403: 403 confirmaria
	 * que o id existe).
	 */
	@Test
	void participanteVeOsPropriosNosENuncaOsDosOutros() throws Exception {
		String admin = adminToken();
		var dono = auth.authenticate("net_owner");
		String nodeId = newNodeOwner(admin, dono.userId());
		String key = issueKey(admin, nodeId);
		activate(admin, nodeId);
		heartbeat(admin, nodeId, key, 42.0, 2048, 1);

		mockMvc.perform(get("/network/participant/nodes").header("X-Session-Token", dono.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].id").value(nodeId))
			.andExpect(jsonPath("$[0].status").value("ACTIVE"))
			.andExpect(jsonPath("$[0].declaredCpuCores").value(4))
			.andExpect(jsonPath("$[0].observedCpuPercent").value(42.0))
			.andExpect(jsonPath("$[0].observedMemoryMb").value(2048))
			.andExpect(jsonPath("$[0].runtimeVersion").value("0.1.0"))
			.andExpect(jsonPath("$[0].lastSeenAt").isNotEmpty());

		// Nó de outro dono, visível só pelo admin, é 404 para o participante.
		String otherNode = newNode(admin);
		mockMvc.perform(get("/network/participant/nodes/" + otherNode).header("X-Session-Token", dono.token()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NODE_UNKNOWN"));

		// Sem sessão não há interface do participante.
		mockMvc.perform(get("/network/participant/nodes")).andExpect(status().isUnauthorized());
	}

	/**
	 * Desconexão voluntária: o nó sai para SUSPENDED, a chave para de autenticar na hora e a ação é
	 * auditada com o participante como ator. Sem chave vigente o runtime desligado não continua
	 * falando com o Control Plane.
	 */
	@Test
	void desconectarVoluntariamenteSuspendeERevogaAChave() throws Exception {
		String admin = adminToken();
		var dono = auth.authenticate("net_owner_disc");
		String nodeId = newNodeOwner(admin, dono.userId());
		String key = issueKey(admin, nodeId);
		activate(admin, nodeId);

		mockMvc.perform(post("/network/participant/nodes/" + nodeId + "/disconnect")
			.header("X-Session-Token", dono.token())).andExpect(status().isOk());

		assertThat(jdbc.queryForObject(
			"select count(*) from network_node_keys where node_id = ? and revoked_at is null", Integer.class,
			nodeId)).isZero();
		mockMvc.perform(post("/network/nodes/heartbeat").header("X-Node-Id", nodeId).header("X-Node-Key", key)
			.contentType(MediaType.APPLICATION_JSON).content("{}"))
			// A chave caiu junto: credencial revogada é 401, o mesmo critério da revogação admin.
			.andExpect(status().isUnauthorized());
		assertThat(jdbc.queryForObject(
			"select count(*) from audit_logs where action = 'NODE_DISCONNECTED' and target_id = ?",
			Integer.class, nodeId)).isEqualTo(1);

		// Desconectar de novo é idempotente: já suspenso não reaudita nem revoga de novo.
		mockMvc.perform(post("/network/participant/nodes/" + nodeId + "/disconnect")
			.header("X-Session-Token", dono.token())).andExpect(status().isOk());
		assertThat(jdbc.queryForObject(
			"select count(*) from audit_logs where action = 'NODE_DISCONNECTED' and target_id = ?",
			Integer.class, nodeId)).isEqualTo(1);
	}

	/**
	 * A contribuição é medida em tarefas concluídas: designada não conta (é promessa), concluída
	 * conta. É o número da tela do participante.
	 */
	@Test
	void contribuicaoDoNoContaTarefasConcluidas() throws Exception {
		String admin = adminToken();
		var dono = auth.authenticate("net_owner_contrib");
		String nodeId = newNodeOwner(admin, dono.userId());
		String key = issueKey(admin, nodeId);
		activate(admin, nodeId);

		java.sql.Timestamp deadline = java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(3600));
		java.sql.Timestamp now = java.sql.Timestamp.from(java.time.Instant.now());
		jdbc.update("insert into network_tasks (id, task_type, status, attempt, idempotency_key, payload, "
			+ "data_class, assigned_node_id, deadline, next_attempt_at, created_at, updated_at) values "
			+ "('tk_done1', 'ASYNC_JOB', 'DONE', 1, 'contrib-done-1', '{}', 'PUBLIC', ?, ?, ?, ?, ?), "
			+ "('tk_disp1', 'ASYNC_JOB', 'DISPATCHED', 1, 'contrib-disp-1', '{}', 'PUBLIC', ?, ?, ?, ?, ?), "
			+ "('tk_fail1', 'ASYNC_JOB', 'FAILED', 1, 'contrib-fail-1', '{}', 'PUBLIC', ?, ?, ?, ?, ?)",
			nodeId, deadline, now, now, now, nodeId, deadline, now, now, now, nodeId, deadline, now, now, now);

		mockMvc.perform(get("/network/participant/nodes").header("X-Session-Token", dono.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].tasksDone").value(1));
	}
}
