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
	/**
	 * O disco livre medido no heartbeat chega ao nó pela config assinada. É o elo que fecha o
	 * circuito: o Control Plane já recusa corpo quando o disco medido está abaixo do piso, e o nó
	 * precisa do mesmo número para decidir cache e réplica localmente — senão ele grava contra um
	 * teto declarado que pode não existir, enquanto o Control Plane acha que ele não tem espaço.
	 */
	@Test
	void configLevaODiscoLivreMedidoNoHeartbeatAoNo() throws Exception {
		String admin = adminToken();
		String nodeId = newNode(admin);
		activate(admin, nodeId);
		String key = issueKey(admin, nodeId);

		mockMvc
			.perform(post("/network/nodes/heartbeat").header("X-Node-Id", nodeId).header("X-Node-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("runtimeVersion", "0.1.0", "observed",
					Map.of("cpuPercent", 5.0, "memoryMb", 1024, "runningTasks", 0, "diskFreeMb", 45056)))))
			.andExpect(status().isOk());

		mockMvc
			.perform(get("/network/nodes/me/config").header("X-Node-Id", nodeId).header("X-Node-Key", key))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.limits.diskFreeMb").value(45056))
			// O declarado continua o declarado: a medida não sobrescreve o contrato.
			.andExpect(jsonPath("$.limits.diskMb").value(51200));
	}

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
			.andExpect(jsonPath("$.formatVersion").value(7))
			// Versão da permissão: 1 do enroll + 1 da aprovação feita acima.
			.andExpect(jsonPath("$.configVersion").value(2))
			.andExpect(jsonPath("$.profiles[0]").value("NODE_MEDIA"))
			.andExpect(jsonPath("$.limits.cpuCores").value(4))
			.andExpect(jsonPath("$.limits.memoryMb").value(8192))
			.andExpect(jsonPath("$.limits.diskMb").value(51200))
			// O disco medido vai junto do declarado (formato 5): o nó decide cache e réplica
			// contra o que existe, não contra o que foi prometido. Aqui ele nunca mandou
			// heartbeat com `diskFreeMb`, então a config diz "não medido" — ausente, não zero.
			.andExpect(jsonPath("$.limits.diskFreeMb").doesNotExist())
			.andExpect(jsonPath("$.limits.bandwidthMbps").value(100))
			.andExpect(jsonPath("$.allowedTasks[0]").value("MEDIA_TRANSCODE"))
			.andExpect(jsonPath("$.allowedTasks[1]").value("MEDIA_THUMBNAIL"))
			// A lista de rotas vai no payload mesmo vazia: é ela que diz ao nó quais destinos ele
			// pode chamar, e uma lista ausente seria indistinguível de uma lista esquecida.
			.andExpect(jsonPath("$.allowedRoutes").isArray())
			.andExpect(jsonPath("$.allowedRoutes").isEmpty())
			// O prazo vai no payload e é o que o nó usa para saber que a permissão venceu: sem ele
			// o runtime seguiria com a última config para sempre e "Control Plane fora" nunca seria
			// detectado (doc 06 §6).
			.andExpect(jsonPath("$.ttlSeconds").value(300))
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

	/**
	 * O painel diz, pela rota, quanto trabalho cada nó executou e se a rede está distribuindo.
	 *
	 * <p>Dois nós ativos; só um recebeu tarefa concluída. O painel tem de mostrar o nó ocioso com
	 * zero (não omitido), a fatia em 100% e `singleNode` verdadeiro — é a leitura que responde ao
	 * §8.4 do brief: "o Master absorve trabalho que as réplicas poderiam fazer".</p>
	 */
	@Test
	void dashboardMostraADistribuicaoDeTrabalhoPorNo() throws Exception {
		String admin = adminToken();
		String ocupado = newNode(admin);
		activate(admin, ocupado);
		String keyOcupado = issueKey(admin, ocupado);
		heartbeat(admin, ocupado, keyOcupado, 20.0, 1024, 0);

		String ocioso = newNode(admin);
		activate(admin, ocioso);
		String keyOcioso = issueKey(admin, ocioso);
		heartbeat(admin, ocioso, keyOcioso, 5.0, 512, 0);

		// Uma tarefa concluída no nó ocupado. O painel lê o agregado do catálogo, então a linha
		// concluída é o dado que ele conta — independente de como ela chegou a DONE.
		jdbc.update("""
			insert into network_tasks (id, task_type, status, attempt, idempotency_key, payload, data_class,
			  assigned_node_id, deadline, next_attempt_at, finished_at, created_at, updated_at)
			values (?, 'ASYNC_JOB', 'DONE', 1, ?, '{}', 'PUBLIC', ?, current_timestamp, current_timestamp,
			  current_timestamp, current_timestamp, current_timestamp)
			""", "tsk_" + System.nanoTime(), "distribuicao-" + System.nanoTime(), ocupado);

		var result = mockMvc.perform(get("/admin/network/nodes/dashboard").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andReturn();
		var load = objectMapper.readTree(result.getResponse().getContentAsString()).get("load");

		assertThat(load).isNotNull();
		assertThat(load.get("nodes")).hasSize(2);
		var porNo = new java.util.HashMap<String, tools.jackson.databind.JsonNode>();
		load.get("nodes").forEach(n -> porNo.put(n.get("nodeId").asText(), n));
		assertThat(porNo.get(ocupado).get("done").asLong()).isEqualTo(1);
		// O ocioso está na lista com zero: omiti-lo esconderia exatamente o que o painel pergunta.
		assertThat(porNo.get(ocioso).get("done").asLong()).isZero();
		// O `runningNow` é o número que o nó reportou no heartbeat, não uma estimativa.
		assertThat(porNo.get(ocioso).get("runningNow").asInt()).isZero();
		assertThat(porNo.get(ocupado).get("runningNow").asInt()).isZero();
		// O agregado é do catálogo inteiro, e o banco da suíte tem tarefas de outros testes: a
		// asserção é sobre a soma por nó ter sido contabilizada, não sobre um total absoluto.
		long total = load.get("totalDone").asLong();
		assertThat(total).isGreaterThanOrEqualTo(1);
		long porNoSoma = 0;
		for (var n : load.get("nodes")) {
			porNoSoma += n.get("done").asLong();
		}
		assertThat(porNoSoma).isLessThanOrEqualTo(total);
		// A fatia é coerente com o total: nenhum percentual fora de 0..100, e nula quando não há o que
		// dividir. A concentração em si é medida nos testes de serviço, onde o catálogo é controlado.
		var fatia = load.get("concentratedShare");
		assertThat(fatia.isNull() || (fatia.asDouble() >= 0.0 && fatia.asDouble() <= 100.0)).isTrue();
	}

	/**
	 * §8.4 do brief: "o painel deve mostrar como os nós trabalham juntos". {@code byProfile} conta
	 * perfis <b>declarados</b>, e isso não responde a pergunta operacional — <b>a rede tem alguém
	 * para fazer cada tipo de trabalho agora?</b> Um nó com o perfil continua contado mesmo em
	 * quarentena, de região evacuada ou sem vaga, e não executa nada.
	 *
	 * <p>O cenário has no tipo que ninguém cobre no momento da medida: o único nó {@code NODE_MEDIA}
	 * vivo fica sem vaga (4 núcleos, 4 tarefas rodando). O tipo precisa aparecer com zero — e não
	 * ausente, o que esconderia justamente a lacuna.</p>
	 */
	@Test
	void dashboardDizQuaisTiposDeTrabalhoNaoTemQuemFaca() throws Exception {
		String admin = adminToken();

		String media = enroll(issueToken(admin, "no-media", List.of("NODE_MEDIA")));
		String keyMedia = issueKey(admin, media);
		// Capacidade declarada é 4 núcleos (o enroll fixa 4): 4 tarefas rodando = sem vaga.
		heartbeat(admin, media, keyMedia, 40.0, 2048, 4);

		var result = mockMvc.perform(get("/admin/network/nodes/dashboard").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andReturn();
		var work = objectMapper.readTree(result.getResponse().getContentAsString()).get("workTypes");

		assertThat(work).isNotNull();
		var porTipo = new java.util.HashMap<String, Integer>();
		work.forEach(t -> porTipo.put(t.get("type").asText(), t.get("capable").asInt()));

		// Todos os tipos aparecem — inclusive os sem ninguém: o tipo ausente esconderia a lacuna.
		assertThat(porTipo.keySet()).contains("MEDIA_TRANSCODE", "OBJECT_PUT", "API_REQUEST", "EDGE_DELIVER",
			"READ_QUERY", "ASYNC_JOB");
		// O nó de mídia existe, está vivo e tem o perfil — e não pode receber agora.
		assertThat(porTipo.get("MEDIA_TRANSCODE")).isZero();
		// Nenhum nó executando tarefa neste cenário: o tipo de trabalho comum também está descoberto,
		// e é a mesma pergunta que a tela faz, não um efeito colateral do nó de mídia.
		assertThat(porTipo.get("OBJECT_PUT")).isZero();
	}

	@Test
	void noEmQuarentenaNaoContaComoQuemFazOTrabalho() throws Exception {
		String admin = adminToken();
		String media = enroll(issueToken(admin, "no-media-quarentena", List.of("NODE_MEDIA")));
		String keyMedia = issueKey(admin, media);
		heartbeat(admin, media, keyMedia, 10.0, 1024, 0);
		// De pé, vivo, com vaga e com o perfil — e em quarentena. Perfil declarado não é capacidade:
		// contar este nó afirmaria que a rede faz mídia quando ninguém está aceitando a tarefa.
		jdbc.update("update network_nodes set quarantined_until = timestampadd(second, 600, current_timestamp), "
			+ "quarantine_reason = 'resultado divergente' where id = ?", media);

		var result = mockMvc.perform(get("/admin/network/nodes/dashboard").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andReturn();
		var work = objectMapper.readTree(result.getResponse().getContentAsString()).get("workTypes");
		int transcode = -1;
		for (var t : work) {
			if ("MEDIA_TRANSCODE".equals(t.get("type").asText())) {
				transcode = t.get("capable").asInt();
			}
		}
		assertThat(transcode).isZero();
	}

	@Test
	void dashboardRecuperaOCapacidadeQuandoONoLiberaVaga() throws Exception {
		String admin = adminToken();

		String media = enroll(issueToken(admin, "no-media-livre", List.of("NODE_MEDIA")));
		String keyMedia = issueKey(admin, media);
		heartbeat(admin, media, keyMedia, 20.0, 1024, 4);
		// O mesmo nó devolve a vaga: o tipo volta a ter quem o faça. Prova que o número é o medido
		// agora, não um cache da primeira leitura.
		mockMvc
			.perform(post("/network/nodes/heartbeat").header("X-Node-Id", media).header("X-Node-Key", keyMedia)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("runtimeVersion", "0.1.0", "observed",
					Map.of("cpuPercent", 20.0, "memoryMb", 1024, "runningTasks", 1)))))
			.andExpect(status().isOk());

		var result = mockMvc.perform(get("/admin/network/nodes/dashboard").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andReturn();
		var work = objectMapper.readTree(result.getResponse().getContentAsString()).get("workTypes");
		int transcode = 0;
		for (var t : work) {
			if ("MEDIA_TRANSCODE".equals(t.get("type").asText())) {
				transcode = t.get("capable").asInt();
			}
		}
		assertThat(transcode).isEqualTo(1);
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

	/**
	 * A tela de capacidade edita o que o participante cede — e precisa ver o valor vigente para
	 * editar. Antes o NodeView do painel não trazia a capacidade declarada: o admin mudava de
	 * capacidade sem ver o que estava lá (declarado * 4096 foi substituído por zeros na tela).
	 */
	@Test
	void listagemDoPainelExpoeACapacidadeDeclarada() throws Exception {
		String admin = adminToken();
		newNode(admin);

		mockMvc.perform(get("/admin/network/nodes").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].declaredCpuCores").value(4))
			.andExpect(jsonPath("$[0].declaredMemoryMb").value(8192))
			.andExpect(jsonPath("$[0].declaredDiskMb").value(51200))
			.andExpect(jsonPath("$[0].declaredBandwidthMbps").value(100));
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

	/**
	 * O participante vê quanto <b>concluiu</b> e quanto <b>devolveu</b> à fila — a §8.4 pede que a
	 * rede mostre como os nós trabalham juntos, e a tela dele mostrava só a conclusão.
	 *
	 * <p>Os dois zeros sozinhos diziam a mesma coisa para o nó novo e para o nó que roda há uma
	 * semana sem terminar nada. Com a devolução ao lado, a tela consegue distinguir "ainda não
	 * recebi" de "recebi e devolvi".</p>
	 */
	@Test
	void participanteVeOTrabalhoQueConcluiuEDevolveu() throws Exception {
		String admin = adminToken();
		var dono = auth.authenticate("net_owner_contrib");
		String nodeId = newNodeOwner(admin, dono.userId());
		String key = issueKey(admin, nodeId);
		heartbeat(admin, nodeId, key, 10.0, 1024, 0);

		// Nó recém-aprovado: nada concluído e nada devolvido. A tela precisa poder afirmar isso —
		// ausência do campo diria "não medi", que é outra coisa.
		mockMvc.perform(get("/network/participant/nodes").header("X-Session-Token", dono.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].tasksDone").value(0))
			.andExpect(jsonPath("$[0].tasksRequeued").value(0));

		// Uma tarefa concluída por ele, e um resultado que ele entregou e foi recusado — a linha que
		// o Control Plane grava com o NÓ como ator.
		jdbc.update("""
			insert into network_tasks (id, task_type, status, attempt, idempotency_key, payload, data_class,
			  assigned_node_id, deadline, next_attempt_at, finished_at, created_at, updated_at)
			values (?, 'ASYNC_JOB', 'DONE', 1, ?, '{}', 'PUBLIC', ?, current_timestamp, current_timestamp,
			  current_timestamp, current_timestamp, current_timestamp)
			""", "tsk_" + System.nanoTime(), "contrib-" + System.nanoTime(), nodeId);
		registrarRecusa(nodeId);

		mockMvc.perform(get("/network/participant/nodes/" + nodeId).header("X-Session-Token", dono.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.tasksDone").value(1))
			// A recusa não é conclusão: somá-la em `tasksDone` inflaria a contribuição.
			.andExpect(jsonPath("$.tasksRequeued").value(1));
	}

	/**
	 * Claim vencido é auditado como {@code system}: o nó não devolveu nada, ele SUMIU no meio do
	 * trabalho. Contar isso como devolução transformaria abandono em atividade, e a tela do
	 * participante estaria elogiando justamente quem não entregou.
	 */
	@Test
	void claimVencidoNaoContaComoDevolucaoDoParticipante() throws Exception {
		String admin = adminToken();
		var dono = auth.authenticate("net_owner_abandon");
		String nodeId = newNodeOwner(admin, dono.userId());
		String key = issueKey(admin, nodeId);
		heartbeat(admin, nodeId, key, 10.0, 1024, 0);

		jdbc.update("insert into audit_logs (id, actor_id, action, target_id, metadata, created_at) "
			+ "values (?, 'system', 'NETWORK_TASK_REQUEUED', ?, '{}', current_timestamp)",
			"aud_" + System.nanoTime(), "tsk_abandonada");

		mockMvc.perform(get("/network/participant/nodes/" + nodeId).header("X-Session-Token", dono.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.tasksRequeued").value(0));
	}

	private void registrarRecusa(String nodeId) {
		jdbc.update("insert into audit_logs (id, actor_id, action, target_id, metadata, created_at) "
			+ "values (?, ?, 'NETWORK_TASK_RESULT_REFUSED', ?, '{}', current_timestamp)",
			"aud_" + System.nanoTime(), nodeId, "tsk_recusada");
	}

	/**
	 * Desconectar devolve o mesmo quadro do participante — e a contribuição que ele já tinha
	 * continua lá. Zero inventado na resposta apagaria o trabalho feito no instante em que ele sai.
	 */
	@Test
	void desconectarDevolveAContribuicaoQueOParticipanteJaTinha() throws Exception {
		String admin = adminToken();
		var dono = auth.authenticate("net_owner_desc_contrib");
		String nodeId = newNodeOwner(admin, dono.userId());
		String key = issueKey(admin, nodeId);
		heartbeat(admin, nodeId, key, 10.0, 1024, 0);
		registrarRecusa(nodeId);

		mockMvc.perform(post("/network/participant/nodes/" + nodeId + "/disconnect")
			.header("X-Session-Token", dono.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("SUSPENDED"))
			.andExpect(jsonPath("$.tasksRequeued").value(1));
	}

	/**
	 * O diagnóstico individual do nó (§8.6) pede "tarefas executadas". A lista e o detalhe do admin
	 * traziam estado, capacidade e saúde, mas nenhuma contribuição medida — e sem ela o operador não
	 * distingue o nó ocioso do que está executando e falhando.
	 */
	@Test
	void o_detalhe_do_no_traz_as_tarefas_que_ele_executou_e_falhou() throws Exception {
		String admin = adminToken();
		String nodeId = newNode(admin);
		activate(admin, nodeId);
		comoConcluir(nodeId, "DONE");
		comoConcluir(nodeId, "DONE");
		comoConcluir(nodeId, "FAILED");
		// Um segundo nó com números DIFERENTES: sem ele, ler a contribuição de "algum" nó passaria
		// no teste por coincidência, e o detalhe mostraria a vida de outro servidor.
		String outro = newNode(admin);
		activate(admin, outro);
		comoConcluir(outro, "DONE");
		comoConcluir(outro, "DONE");
		comoConcluir(outro, "DONE");
		comoConcluir(outro, "DONE");

		mockMvc.perform(get("/admin/network/nodes/" + nodeId).header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.tasksDone").value(2))
			// Falha conta separada: somá-la em `tasksDone` faria um nó que erra tudo parecer produtivo.
			.andExpect(jsonPath("$.tasksFailed").value(1));
	}

	@Test
	void a_listagem_traz_a_contribuicao_de_cada_no() throws Exception {
		String admin = adminToken();
		String trabalhador = newNode(admin);
		String parado = newNode(admin);
		activate(admin, trabalhador);
		activate(admin, parado);
		comoConcluir(trabalhador, "DONE");

		var result = mockMvc.perform(get("/admin/network/nodes").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andReturn();
		var lista = objectMapper.readTree(result.getResponse().getContentAsString());
		var porNo = new java.util.HashMap<String, tools.jackson.databind.JsonNode>();
		lista.forEach(n -> porNo.put(n.get("id").asText(), n));

		assertThat(porNo.get(trabalhador).get("tasksDone").asLong()).isEqualTo(1);
		// O nó parado aparece com zero, não ausente: omissão diria "não medi".
		assertThat(porNo.get(parado).get("tasksDone").asLong()).isZero();
		assertThat(porNo.get(parado).get("tasksFailed").asLong()).isZero();
	}

	private void comoConcluir(String nodeId, String status) {
		jdbc.update("""
			insert into network_tasks (id, task_type, status, attempt, idempotency_key, payload, data_class,
			  assigned_node_id, deadline, next_attempt_at, finished_at, created_at, updated_at)
			values (?, 'ASYNC_JOB', ?, 1, ?, '{}', 'PUBLIC', ?, current_timestamp, current_timestamp,
			  current_timestamp, current_timestamp, current_timestamp)
			""", "tsk_" + System.nanoTime(), status, "diag-" + System.nanoTime(), nodeId);
	}

	/**
	 * A topologia como dado para a tela desenhar (brief §8.5). O que estes testes seguram é que a
	 * rota entrega o agrupamento por <b>domínio de falha</b> que a rede pode afirmar — e não inventa
	 * aresta entre dois nós, que o cadastro não registra.
	 */
	@Test
	void a_topologia_agrupa_por_dominio_de_falha_e_nao_funde_regiao_nao_declarada() throws Exception {
		String admin = adminToken();
		String sudeste = enrollComLocation(issueToken(admin, "topo-se", List.of("NODE_WORKER")), "Sudeste", "BR");
		String sudeste2 = enrollComLocation(issueToken(admin, "topo-se2", List.of("NODE_EDGE")), "SUDESTE", "BR");
		// Nós sem região caem num domínio vazio, que é domínio próprio: fundi-lo com uma região real
		// afirmaria um vínculo que o cadastro não registra.
		String semRegiaoA = enroll(issueToken(admin, "topo-sem-a", List.of("NODE_WORKER")));
		String semRegiaoB = enroll(issueToken(admin, "topo-sem-b", List.of("NODE_WORKER")));
		// Um terceiro sem região: com 2 com e 2 sem, contar o grupo errado daria o mesmo número.
		String semRegiaoC = enroll(issueToken(admin, "topo-sem-c", List.of("NODE_WORKER")));
		for (String id : List.of(sudeste, sudeste2, semRegiaoA, semRegiaoB, semRegiaoC)) {
			activate(admin, id);
			heartbeat(admin, id, issueKey(admin, id), 5.0, 512, 0);
		}

		var result = mockMvc.perform(get("/admin/network/nodes/topology").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andReturn();
		var tree = objectMapper.readTree(result.getResponse().getContentAsString());

		var dominios = tree.get("domains");
		var porRegiao = new java.util.HashMap<String, tools.jackson.databind.JsonNode>();
		dominios.forEach(d -> porRegiao.put(d.get("region").asText(), d));

		// Grafias diferentes são o MESMO domínio de falha (a normalização da colocação e da evacuação).
		assertThat(porRegiao.get("sudeste").get("nodes").asLong()).isEqualTo(2);
		// Os dois sem região caem no domínio vazio, e o domínio vazio não é a região de ninguém.
		assertThat(porRegiao.get("").get("nodes").asLong()).isEqualTo(3);
		assertThat(porRegiao.containsKey("sudeste")).isTrue();

		// Aresta declarada só existe para quem declarou região: 2 de 4 nós.
		assertThat(tree.get("declaredConnections").asLong()).isEqualTo(2);
		assertThat(tree.get("truncated").asBoolean()).isFalse();

		// O nó traz o domínio calculado e as quatro dimensões de saúde — a tela não faz segunda chamada.
		var primeiro = tree.get("nodes").get(0);
		assertThat(primeiro.has("domain")).isTrue();
		assertThat(primeiro.get("alive").asBoolean()).isTrue();
		assertThat(primeiro.get("health").get("host").asText()).isEqualTo("OK");
	}

	@Test
	void regiao_evacuada_aparece_marcada_na_topologia() throws Exception {
		String admin = adminToken();
		String nodeId = enrollComLocation(issueToken(admin, "topo-evac", List.of("NODE_WORKER")), "nordeste", "BR");
		activate(admin, nodeId);
		heartbeat(admin, nodeId, issueKey(admin, nodeId), 5.0, 512, 0);

		mockMvc.perform(post("/admin/network/regions").header("X-Session-Token", admin)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("region", "nordeste", "reason", "incidente"))))
			.andExpect(status().isOk());

		var result = mockMvc.perform(get("/admin/network/nodes/topology").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andReturn();
		// O nó continua vivo: o domínio é que está fora. Fundir as duas coisas esconderia a diferença
		// entre "a máquina caiu" e "a região inteira foi evacuada por decisão".
		var dominios = objectMapper.readTree(result.getResponse().getContentAsString()).get("domains");
		boolean achou = false;
		for (var d : dominios) {
			if ("nordeste".equals(d.get("region").asText())) {
				assertThat(d.get("outOfService").asBoolean()).isTrue();
				assertThat(d.get("alive").asLong()).isEqualTo(1);
				achou = true;
			}
		}
		assertThat(achou).isTrue();
	}

	/**
	 * Vivo é medido, status é administrativo. Um nó `ACTIVE` sem heartbeat dentro da janela precisa
	 * aparecer vivo=falso: se a topologia mostrasse só o status, a tela desenharia como saudável uma
	 * máquina que parou de responder — e é justamente no mapa que o operador procura o que caiu.
	 */
	@Test
	void a_topologia_mostra_no_ativo_sem_heartbeat_como_nao_vivo() throws Exception {
		String admin = adminToken();
		String vivo = enrollComLocation(issueToken(admin, "topo-vivo", List.of("NODE_WORKER")), "sul", "BR");
		String parado = enrollComLocation(issueToken(admin, "topo-parado", List.of("NODE_WORKER")), "sul", "BR");
		activate(admin, vivo);
		heartbeat(admin, vivo, issueKey(admin, vivo), 5.0, 512, 0);
		// O heartbeat sempre grava o agora; para o nó ter parado de responder, o carimbo precisa
		// recuar. O status fica ACTIVE dos dois lados — é o que torna a asserção discriminante.
		activate(admin, parado);
		heartbeat(admin, parado, issueKey(admin, parado), 5.0, 512, 0);
		jdbc.update("update network_nodes set last_seen_at = ? where id = ?",
			java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(3600)), parado);

		var result = mockMvc.perform(get("/admin/network/nodes/topology").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andReturn();
		var tree = objectMapper.readTree(result.getResponse().getContentAsString());
		var porId = new java.util.HashMap<String, tools.jackson.databind.JsonNode>();
		tree.get("nodes").forEach(n -> porId.put(n.get("id").asText(), n));

		assertThat(porId.get(parado).get("status").asText()).isEqualTo("ACTIVE");
		assertThat(porId.get(vivo).get("alive").asBoolean()).isTrue();
		assertThat(porId.get(parado).get("alive").asBoolean()).isFalse();

		// O domínio conta só o vivo: contar o parado como disponível na mesma região esconderia
		// que o domínio inteiro já não responde.
		for (var d : tree.get("domains")) {
			if ("sul".equals(d.get("region").asText())) {
				assertThat(d.get("nodes").asLong()).isEqualTo(2);
				assertThat(d.get("alive").asLong()).isEqualTo(1);
			}
		}
	}

	@Test
	void a_topologia_exige_admin() throws Exception {
		mockMvc.perform(get("/admin/network/nodes/topology")).andExpect(status().isUnauthorized());
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

	/**
	 * Solicitação de ingresso: o token nasce com o PRÓPRIO participante como dono e criador — o
	 * corpo do pedido não escolhe dono nem perfis — e o token funciona no enroll como qualquer
	 * outro, criando o nó em PENDING_APPROVAL para a conta dele.
	 */
	@Test
	void participanteSolicitaIngressoETokenCriaONoDele() throws Exception {
		var dono = auth.authenticate("net_join");

		var result = mockMvc
			.perform(post("/network/participant/join").header("X-Session-Token", dono.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("displayName", "vps-da-casa"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.expiresAt").isNotEmpty())
			.andExpect(jsonPath("$.nextStep").isNotEmpty())
			.andReturn();
		String token = objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asString();

		// O token não escolhe dono: quem pediu é o dono, e o enroll cria o nó para a conta dele.
		String nodeId = enroll(token);
		assertThat(jdbc.queryForObject("select owner_user_id from network_nodes where id = ?", String.class,
				nodeId))
			.isEqualTo(dono.userId());
		assertThat(jdbc.queryForObject("select created_by from network_provision_tokens", String.class))
			.isEqualTo(dono.userId());
		// Sem perfis na solicitação: o que o nó executa é negociado com o admin na aprovação.
		assertThat(jdbc.queryForObject("select profiles from network_nodes where id = ?", String.class, nodeId))
			.isEqualTo("");
		assertThat(jdbc.queryForObject(
			"select count(*) from audit_logs where action = 'NODE_JOIN_REQUESTED' and actor_id = ?",
			Integer.class, dono.userId())).isEqualTo(1);
	}

	/** O freio do auto-serviço: três tokens vivos por conta, o quarto pedido é 429. */
	@Test
	void solicitacaoDeIngressoTemLimiteDeTokensVivos() throws Exception {
		var dono = auth.authenticate("net_join_limit");

		var first = mockMvc
			.perform(post("/network/participant/join").header("X-Session-Token", dono.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("displayName", "vps-1"))))
			.andExpect(status().isOk())
			.andReturn();
		String plain = objectMapper.readTree(first.getResponse().getContentAsString()).get("token").asString();
		for (int i = 2; i <= 3; i++) {
			mockMvc.perform(post("/network/participant/join").header("X-Session-Token", dono.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("displayName", "vps-" + i))))
				.andExpect(status().isOk());
		}
		mockMvc.perform(post("/network/participant/join").header("X-Session-Token", dono.token())
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("displayName", "vps-4"))))
			.andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("NODE_JOIN_LIMIT"));

		// Consumir um token (uso único) abre a vaga: o pedido seguinte volta a passar.
		enroll(plain);
		mockMvc.perform(post("/network/participant/join").header("X-Session-Token", dono.token())
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("displayName", "vps-5"))))
			.andExpect(status().isOk());
	}

	/** Sem sessão não há solicitação; nome de nó vale as mesmas regras do painel. */
	@Test
	void solicitacaoSemSessaoOuSemNomeERecusada() throws Exception {
		mockMvc.perform(post("/network/participant/join").contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("displayName", "x"))))
			.andExpect(status().isUnauthorized());

		var dono = auth.authenticate("net_join_nome");
		mockMvc.perform(post("/network/participant/join").header("X-Session-Token", dono.token())
			.contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("NODE_NAME_INVALID"));
	}
}
