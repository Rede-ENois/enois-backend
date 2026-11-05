package com.socialcoisa.rede.calls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.moderation.application.AiVerdictApplicationService;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Denúncia de chamada com a captura do momento.
 *
 * <p>O que este teste tranca: só quem está no vínculo da chamada denuncia, e só aponta quem
 * realmente entrou naquela sala (aceitar o toque não basta); o que a denúncia guarda — quem estava
 * dentro, quem foi apontado, a captura — é montado no servidor, e a captura anexada precisa ter
 * sido enviada por quem denuncia; a mesma pessoa não abre dois casos contra o mesmo participante da
 * mesma chamada; o painel de moderação recebe o contexto da chamada; a penalidade e o banimento
 * caem sobre quem foi apontado, não sobre a sala; e a triagem por IA não penaliza sozinha uma
 * denúncia de chamada — a captura é de quem acusa, e quem decide é uma pessoa.
 */
@SpringBootTest(properties = {
		"rede.calls.epapo.url=https://chamadas.example.test",
		"spring.task.scheduling.enabled=false"
})
@AutoConfigureMockMvc
class CallReportTests {

	/** Segredo descartável da ponte, como no deploy: só ele chama a rota de entrada. */
	private static final String GATEWAY_SECRET = "crep-bridge-" + java.util.UUID.randomUUID();

	/** Gateway falso numa porta local: a chamada só existe com a ponte configurada. */
	private static final int GATEWAY_PORT = freePort();

	private static final FakeEpapoGateway GATEWAY = startGateway(GATEWAY_PORT);

	@org.springframework.test.context.DynamicPropertySource
	static void gateway(org.springframework.test.context.DynamicPropertyRegistry registry) {
		registry.add("rede.calls.epapo.gateway-url", () -> GATEWAY.url());
		registry.add("rede.calls.epapo.internal-token", () -> "epapo-crep");
		registry.add("rede.calls.gateway.authorize-token", () -> GATEWAY_SECRET);
	}

	@org.junit.jupiter.api.AfterAll
	static void shutGateway() {
		GATEWAY.stop();
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private AiVerdictApplicationService verdicts;

	@Autowired
	private com.socialcoisa.rede.moderation.application.port.out.ReportRepository reportRepository;

	private TestAuthHelper auth() {
		return new TestAuthHelper(mockMvc, objectMapper);
	}

	// ------------------------------------------------------------------ quem denuncia quem

	@Test
	void quemEntrouNaSalaApareceParaDenunciaEOServidorMontaOContexto() throws Exception {
		var host = auth().authenticate("crep-host");
		var guest = auth().authenticate("crep-guest");
		var absent = auth().authenticate("crep-absent");
		String conversation = directConversation(host, guest, "crep-host-guest");
		// A conversa tem duas pessoas; o terceiro não entra por lugar nenhum.
		String callId = startCall(host, conversation);
		answer(guest, callId);
		join(host, callId);
		JsonNode join = join(guest, callId);
		assertThat(join.get("state").asText()).isEqualTo("ACTIVE");

		// O nome que a tela mostra é o mesmo que a pessoa vê: só quem entrou, sem mim.
		JsonNode reportable = body(mockMvc.perform(get("/calls/" + callId + "/reportable")
				.header("X-Session-Token", guest.token()))
			.andExpect(status().isOk())
			.andReturn());
		assertThat(reportable.get("name")).isNull();
		assertThat(ids(reportable)).containsExactly(host.userId());
		assertThat(reportable.get(0).get("displayName").asText()).isNotBlank();

		JsonNode created = body(mockMvc.perform(post("/calls/" + callId + "/reports")
				.header("X-Session-Token", guest.token())
				.header("Idempotency-Key", "crep-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(reportBody(host.userId(), "HARASSMENT", "me seguiu por toda a chamada")))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("PENDING"))
			.andReturn());
		String reportId = created.get("id").asText();

		// O que fica gravado vem do servidor: a sala, quem foi apontado e quem já entrou nela.
		Map<String, Object> context = jdbc.queryForMap("select * from call_reports where report_id = ?", reportId);
		assertThat(context.get("call_id")).isEqualTo(callId);
		assertThat(context.get("reported_user_id")).isEqualTo(host.userId());
		assertThat(context.get("media_id")).isNull();
		assertThat((String) context.get("participants")).contains(host.userId()).contains(guest.userId())
			.doesNotContain(absent.userId());

		// A pessoa denunciada não é quem denunciou, e a descrição guardada diz o que aconteceu.
		Map<String, Object> report = jdbc.queryForMap("select * from reports where id = ?", reportId);
		assertThat(report.get("target_type")).isEqualTo("CALL");
		assertThat(report.get("target_id")).isEqualTo(callId);
		assertThat(report.get("reporter_id")).isEqualTo(guest.userId());
		assertThat((String) report.get("description")).contains("me seguiu por toda a chamada");
	}

	@Test
	void corpoDoPedidoNaoInventaParticipanteNemSala() throws Exception {
		var host = auth().authenticate("crep2-host");
		var guest = auth().authenticate("crep2-guest");
		var outsider = auth().authenticate("crep2-outsider");
		String callId = startCall(host, directConversation(host, guest, "crep2-host-guest"));
		answer(guest, callId);
		join(host, callId);
		join(guest, callId);

		// Alguém de fora da chamada não aparece na lista.
		JsonNode reportable = body(mockMvc.perform(get("/calls/" + callId + "/reportable")
				.header("X-Session-Token", guest.token())).andReturn());
		assertThat(ids(reportable)).doesNotContain(outsider.userId()).containsExactly(host.userId());

		// Tanto faz o que o corpo diga sobre a sala: o denunciado é conferido contra quem entrou.
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("reportedUserId", outsider.userId());
		body.put("reason", "SPAM");
		body.put("description", "tentativa");
		body.put("callId", "sala_que_nao_existe");
		body.put("participants", List.of(guest.userId(), outsider.userId()));
		mockMvc.perform(post("/calls/" + callId + "/reports")
				.header("X-Session-Token", guest.token())
				.header("Idempotency-Key", "crep2-body")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(body)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("NOT_IN_CALL"));
	}

	@Test
	void negativasDaDenunciaDeChamada() throws Exception {
		var host = auth().authenticate("crep3-host");
		var guest = auth().authenticate("crep3-guest");
		var outsider = auth().authenticate("crep3-outsider");
		String conversation = directConversation(host, guest, "crep3-host-guest");
		String callId = startCall(host, conversation);
		answer(guest, callId);
		join(host, callId);
		join(guest, callId);

		// De fora da conversa: não vê a lista nem denuncia.
		mockMvc.perform(get("/calls/" + callId + "/reportable").header("X-Session-Token", outsider.token()))
			.andExpect(status().isForbidden());
		mockMvc.perform(post("/calls/" + callId + "/reports")
				.header("X-Session-Token", outsider.token())
				.header("Idempotency-Key", "crep3-outsider")
				.contentType(MediaType.APPLICATION_JSON)
				.content(reportBody(host.userId(), "SPAM", "nada")))
			.andExpect(status().isForbidden());
		// Sem sessão não há denúncia.
		mockMvc.perform(post("/calls/" + callId + "/reports")
				.contentType(MediaType.APPLICATION_JSON)
				.content(reportBody(host.userId(), "SPAM", "nada")))
			.andExpect(status().isUnauthorized());

		// Denunciar a si mesmo.
		mockMvc.perform(post("/calls/" + callId + "/reports")
				.header("X-Session-Token", guest.token())
				.header("Idempotency-Key", "crep3-self")
				.contentType(MediaType.APPLICATION_JSON)
				.content(reportBody(guest.userId(), "SPAM", "eu mesmo")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REPORTED_USER"));
		// Sem motivo.
		mockMvc.perform(post("/calls/" + callId + "/reports")
				.header("X-Session-Token", guest.token())
				.header("Idempotency-Key", "crep3-noreason")
				.contentType(MediaType.APPLICATION_JSON)
				.content(reportBody(host.userId(), null, "sem motivo")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REASON"));
		// Relato acima do teto.
		mockMvc.perform(post("/calls/" + callId + "/reports")
				.header("X-Session-Token", guest.token())
				.header("Idempotency-Key", "crep3-long")
				.contentType(MediaType.APPLICATION_JSON)
				.content(reportBody(host.userId(), "SPAM", "x".repeat(1201))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("DESCRIPTION_TOO_LONG"));

		// A primeira vale; a segunda vira 409 em vez de abrir outro caso.
		mockMvc.perform(post("/calls/" + callId + "/reports")
				.header("X-Session-Token", guest.token())
				.header("Idempotency-Key", "crep3-first")
				.contentType(MediaType.APPLICATION_JSON)
				.content(reportBody(host.userId(), "SPAM", "primeira")))
			.andExpect(status().isCreated());
		mockMvc.perform(post("/calls/" + callId + "/reports")
				.header("X-Session-Token", guest.token())
				.header("Idempotency-Key", "crep3-second")
				.contentType(MediaType.APPLICATION_JSON)
				.content(reportBody(host.userId(), "SPAM", "de novo")))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("ALREADY_REPORTED"));
	}

	@Test
	void quemNuncaEntrouNaSalaNaoPodeSerDenunciado() throws Exception {
		// Membro do grupo que nunca abriu o painel: não estava na sala, então não há o que
		// denunciar contra ele nesta chamada. O teto é o servidor, não a tela.
		var host = auth().authenticate("crep4-host");
		var guest = auth().authenticate("crep4-guest");
		var absent = auth().authenticate("crep4-absent");
		String groupId = createGroup(host, "crep4 grupo");
		joinGroup(guest, groupId, "crep4-group-guest");
		joinGroup(absent, groupId, "crep4-group-absent");
		String callId = startGroupCall(host, groupId);
		join(host, callId);
		join(guest, callId);

		mockMvc.perform(post("/calls/" + callId + "/reports")
				.header("X-Session-Token", host.token())
				.header("Idempotency-Key", "crep4-never-joined")
				.contentType(MediaType.APPLICATION_JSON)
				.content(reportBody(absent.userId(), "SPAM", "nunca apareceu")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("NOT_IN_CALL"));
	}

	@Test
	void capturaAnexadaPrecisaSerDeQuemDenuncia() throws Exception {
		var host = auth().authenticate("crep5-host");
		var guest = auth().authenticate("crep5-guest");
		var third = auth().authenticate("crep5-third");
		String callId = startCall(host, directConversation(host, guest, "crep5-host-guest"));
		answer(guest, callId);
		join(host, callId);
		join(guest, callId);

		String foreign = insertImage("crep5_imagem_de_terceiro", third.userId());
		mockMvc.perform(post("/calls/" + callId + "/reports")
				.header("X-Session-Token", guest.token())
				.header("Idempotency-Key", "crep5-foreign")
				.contentType(MediaType.APPLICATION_JSON)
				.content(reportBody(host.userId(), "HARASSMENT", "com imagem alheia", foreign)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("MEDIA_OWNER_MISMATCH"));

		String own = insertImage("crep5_imagem_propria", guest.userId());
		String reportId = body(mockMvc.perform(post("/calls/" + callId + "/reports")
				.header("X-Session-Token", guest.token())
				.header("Idempotency-Key", "crep5-own")
				.contentType(MediaType.APPLICATION_JSON)
				.content(reportBody(host.userId(), "HARASSMENT", "olha o print", own)))
			.andExpect(status().isCreated())
			.andReturn()).get("id").asText();

		Map<String, Object> context = jdbc.queryForMap("select media_id from call_reports where report_id = ?",
			reportId);
		assertThat(context.get("media_id")).isEqualTo(own);
	}

	// ------------------------------------------------------------------ moderação

	@Test
	void painelDeModeracaoRecebeOContextoDaChamada() throws Exception {
		var admin = auth().authenticate("group-admin");
		var host = auth().authenticate("crep6-host");
		var guest = auth().authenticate("crep6-guest");
		String callId = startCall(host, directConversation(host, guest, "crep6-host-guest"));
		answer(guest, callId);
		join(host, callId);
		join(guest, callId);
		String capture = insertImage("crep6_captura", guest.userId());
		String reportId = report(guest, callId, host.userId(), "HARASSMENT", "gritou comigo", capture);

		JsonNode review = body(mockMvc.perform(get("/admin/reports/" + reportId)
				.header("X-Session-Token", admin.token()))
			.andExpect(status().isOk())
			.andReturn());

		JsonNode call = review.get("call");
		assertThat(call.get("callId").asText()).isEqualTo(callId);
		assertThat(call.get("reportedUserId").asText()).isEqualTo(host.userId());
		System.out.println("DEBUG_CALL " + call);
		assertThat(call.get("mediaId").asText()).isEqualTo(capture);
		assertThat(call.get("participants").toString()).contains(guest.userId()).contains(host.userId());
		assertThat(call.get("reportedAt").asText()).isNotBlank();

		// Quem foi apontado é "o autor" para o painel: é sobre ele que a decisão recai.
		assertThat(review.get("authorId").asText()).isEqualTo(host.userId());
		// O relato e o contexto chegam ao texto que o painel mostra (e que a triagem lê).
		assertThat(review.get("content").asText()).contains("gritou comigo")
			.contains("captura da tela")
			.contains(host.userId());
		// E a captura do momento viaja para o painel como imagem.
		JsonNode media = review.get("media");
		assertThat(media).isNotNull();
		assertThat(media.toString()).contains(capture);
	}

	@Test
	void penalidadeEBanimentoCaemSobreQuemFoiApontado() throws Exception {
		var admin = auth().authenticate("group-admin");
		var host = auth().authenticate("crep7-host");
		var guest = auth().authenticate("crep7-guest");
		var other = auth().authenticate("crep7-other");
		String conversation = directConversation(host, guest, "crep7-host-guest");
		// Numa chamada de grupo o terceiro também está na sala e pode denunciar o mesmo alvo.
		String groupId = createGroup(host, "crep7 grupo");
		joinGroup(guest, groupId, "crep7-group-guest");
		joinGroup(other, groupId, "crep7-group-other");
		String callId = startGroupCall(host, groupId);
		join(host, callId);
		join(guest, callId);
		join(other, callId);
		String reportId = report(guest, callId, host.userId(), "HARASSMENT", "ofensas");

		mockMvc.perform(post("/admin/reports/" + reportId + "/penalize")
				.header("X-Session-Token", admin.token()))
			.andExpect(status().isOk());

		assertThat(sanctionsOf(host.userId())).isNotEmpty();
		assertThat(sanctionsOf(guest.userId())).isEmpty();
		assertThat(sanctionsOf(other.userId())).isEmpty();

		// Outro participante da mesma sala denuncia o mesmo alvo: ali o banimento é o desfecho.
		String second = report(other, callId, host.userId(), "HARASSMENT", "tambem me ofendeu");
		var ban = mockMvc.perform(post("/admin/reports/" + second + "/ban")
				.header("X-Session-Token", admin.token()))
			.andReturn();
		assertThat(ban.getResponse().getStatus()).as("ban: " + ban.getResponse().getContentAsString())
			.isEqualTo(200);
		assertThat(bannedOf(host.userId())).as("linhas: " + jdbc.queryForList(
			"select id, user_id, type, status from user_sanctions where user_id = ?", host.userId()))
			.isNotEmpty();
		assertThat(bannedOf(guest.userId())).isEmpty();
	}

	@Test
	void triagemPorIaNaoPenalizaSozinhaDenunciaDeChamada() throws Exception {
		var host = auth().authenticate("crep8-host");
		var guest = auth().authenticate("crep8-guest");
		String callId = startCall(host, directConversation(host, guest, "crep8-host-guest"));
		answer(guest, callId);
		join(host, callId);
		join(guest, callId);
		String reportId = report(guest, callId, host.userId(), "SEXUAL_CONTENT", "mostrou coisas na camera");

		// Mesmo com veredito confiante aplicado pelo caminho automático (o modelo "tem certeza"),
		// a denúncia de chamada fica para uma pessoa: a captura foi feita por quem acusa.
		var pending = reportRepository.findById(reportId).orElseThrow();
		pending.attachAiTriage("""
			{"categoria":"Conteudo sexual","regra":"Nudez","gravidade":"CRITICAL",
			"confianca":0.99,"evidencias":["captura"],"contexto":"chamada","ambiguo":false,
			"acao":"PENALIZAR","justificativa":"explicito na tela"}""", java.time.Instant.now());
		reportRepository.save(pending);
		verdicts.applyPendingVerdicts(200);

		assertThat(anySanctionOf(host.userId())).as("denuncia de chamada espera uma pessoa").isEmpty();
		Map<String, Object> report = jdbc.queryForMap("select status from reports where id = ?", reportId);
		assertThat(report.get("status")).isEqualTo("PENDING");
	}

	// ------------------------------------------------------------------ utilidades

	private String report(TestAuthHelper.AuthSession reporter, String callId, String reportedUserId,
			String reason, String description) throws Exception {
		return report(reporter, callId, reportedUserId, reason, description, null);
	}

	private String report(TestAuthHelper.AuthSession reporter, String callId, String reportedUserId,
			String reason, String description, String mediaId) throws Exception {
		MvcResult result = mockMvc.perform(post("/calls/" + callId + "/reports")
				.header("X-Session-Token", reporter.token())
				.header("Idempotency-Key", "crep-report-" + System.nanoTime())
				.contentType(MediaType.APPLICATION_JSON)
				.content(reportBody(reportedUserId, reason, description, mediaId)))
			.andExpect(status().isCreated())
			.andReturn();
		return body(result).get("id").asText();
	}

	private String reportBody(String reportedUserId, String reason, String description) {
		return reportBody(reportedUserId, reason, description, null);
	}

	private String reportBody(String reportedUserId, String reason, String description, String mediaId) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("reportedUserId", reportedUserId);
		body.put("reason", reason);
		body.put("description", description);
		if (mediaId != null) {
			body.put("mediaId", mediaId);
		}
		return objectMapper.writeValueAsString(body);
	}

	/** Instante de captura como o app envia: upload feito pela própria pessoa, já disponível. */
	private String insertImage(String id, String ownerId) {
		jdbc.update("""
			insert into media_assets (id, owner_id, file_name, mime_type, size_bytes, purpose, object_key,
				cdn_url, status, delete_status, created_at, expires_at)
			values (?, ?, ?, 'image/jpeg', 2048, 'REPORT_CAPTURE', ?, ?, 'READY', 'NONE', ?, ?)
			""", id, ownerId, "media/" + id + "/captura.jpg", "media/" + id + "/captura.jpg",
			"https://cdn.test/" + id + ".jpg", java.time.Instant.now(),
			java.time.Instant.now().plusSeconds(3600));
		return id;
	}

	private List<String> ids(JsonNode list) {
		List<String> result = new java.util.ArrayList<>();
		list.forEach(item -> result.add(item.get("userId").asText()));
		return result;
	}

	/** Só o passo do gateway: quem registra entrada na sala. */
	private void enterOnly(TestAuthHelper.AuthSession who, String callId,
			org.springframework.test.web.servlet.ResultMatcher expected) throws Exception {
		mockMvc.perform(post("/calls/" + callId + "/gateway/entry/" + who.userId())
				.header("X-Enois-Authorize", GATEWAY_SECRET))
			.andExpect(expected);
	}

	/** Qualquer sanção da conta, para afirmar sobre a pessoa que NÃO pode ser punida. */
	private List<Map<String, Object>> anySanctionOf(String userId) {
		return jdbc.queryForList("select id from user_sanctions where user_id = ?", userId);
	}

	private List<Map<String, Object>> sanctionsOf(String userId) {
		return jdbc.queryForList("select id from user_sanctions where user_id = ? and status = 'ACTIVE'", userId);
	}

	private List<Map<String, Object>> bannedOf(String userId) {
		return jdbc.queryForList("select id from user_sanctions where user_id = ? and type = 'BAN'", userId);
	}

	private String createGroup(TestAuthHelper.AuthSession owner, String name) throws Exception {
		MvcResult result = mockMvc.perform(post("/groups")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "crep-group-" + name.replace(' ', '-'))
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", name, "description", "tamo junto",
					"joinMode", "OPEN"))))
			.andExpect(status().isCreated())
			.andReturn();
		return body(result).get("id").asText();
	}

	private void joinGroup(TestAuthHelper.AuthSession who, String groupId, String key) throws Exception {
		mockMvc.perform(post("/groups/" + groupId + "/join")
				.header("X-Session-Token", who.token())
				.header("Idempotency-Key", key))
			.andExpect(status().isOk());
	}

	private String startGroupCall(TestAuthHelper.AuthSession initiator, String groupId) throws Exception {
		MvcResult result = mockMvc.perform(post("/groups/" + groupId + "/calls")
				.header("X-Session-Token", initiator.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"kind\":\"VIDEO\"}"))
			.andExpect(status().isOk())
			.andReturn();
		return body(result).get("id").asText();
	}

	private String startCall(TestAuthHelper.AuthSession initiator, String conversation) throws Exception {
		MvcResult result = mockMvc.perform(post("/conversations/" + conversation + "/calls")
				.header("X-Session-Token", initiator.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"kind\":\"VIDEO\"}"))
			.andExpect(status().isOk())
			.andReturn();
		return body(result).get("id").asText();
	}

	private void answer(TestAuthHelper.AuthSession who, String callId) throws Exception {
		mockMvc.perform(post("/calls/" + callId + "/answer").header("X-Session-Token", who.token()))
			.andExpect(status().isOk());
	}

	/**
	 * Entrar na sala. O painel registra no servidor que aquela pessoa entrou (é o passo que o
	 * gateway faz antes de assinar o token, e o que decide quem dá para denunciar); o teste faz o
	 * mesmo passo pelo caminho do servidor.
	 */
	private JsonNode join(TestAuthHelper.AuthSession who, String callId) throws Exception {
		mockMvc.perform(post("/calls/" + callId + "/join")
				.header("X-Session-Token", who.token()))
			.andExpect(status().isOk());
		mockMvc.perform(post("/calls/" + callId + "/gateway/entry/" + who.userId())
				.header("X-Enois-Authorize", GATEWAY_SECRET))
			.andExpect(status().isOk());
		MvcResult result = mockMvc.perform(get("/calls/" + callId)
				.header("X-Session-Token", who.token()))
			.andExpect(status().isOk())
			.andReturn();
		return body(result);
	}

	private String directConversation(TestAuthHelper.AuthSession a, TestAuthHelper.AuthSession b, String key)
			throws Exception {
		MvcResult result = mockMvc.perform(post("/conversations/direct")
				.header("X-Session-Token", a.token())
				.header("Idempotency-Key", "dm-" + key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("userId", b.userId()))))
			.andExpect(status().isOk())
			.andReturn();
		return body(result).get("id").asText();
	}

	private static int freePort() {
		try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
			return socket.getLocalPort();
		}
		catch (java.io.IOException failure) {
			throw new IllegalStateException("sem porta livre para o gateway falso", failure);
		}
	}

	private static FakeEpapoGateway startGateway(int port) {
		try {
			return new FakeEpapoGateway(port);
		}
		catch (java.io.IOException failure) {
			throw new IllegalStateException("gateway falso nao subiu", failure);
		}
	}

	private JsonNode body(MvcResult result) throws Exception {
		String content = result.getResponse().getContentAsString();
		return content == null || content.isBlank() ? objectMapper.createObjectNode()
			: objectMapper.readTree(content);
	}
}
