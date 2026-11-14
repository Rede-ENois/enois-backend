package com.socialcoisa.rede.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import com.socialcoisa.rede.communityprojects.application.CommunityProjectService;
import com.socialcoisa.rede.feed.application.port.out.OutboxEventRepository;
import com.socialcoisa.rede.identity.application.EmailService;
import com.socialcoisa.rede.moderation.application.port.out.SanctionRepository;
import com.socialcoisa.rede.moderation.domain.UserSanction;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestAuthHelper.AuthSession;

import tools.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManager;

/** Publicação, autorização e entrega reais, dirigindo a outbox por id, sem espera nem rede. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
	"rede.admin.emails=notif676_mod@test.com",
	"rede.outbox.worker-initial-delay-ms=3600000",
	"rede.outbox.worker-delay-ms=3600000" })
class CommunityProjectNotificationTests {

	@Autowired private MockMvc mvc;
	@Autowired private ObjectMapper json;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private SpringDataOutboxEventJpaRepository eventosOutbox;
	@Autowired private SpringDataOutboxHandlerReceiptJpaRepository recibosOutbox;
	@Autowired private List<OutboxEventHandler> consumidores;
	@Autowired private PlatformTransactionManager transacoes;
	@Autowired private OutboxMetrics metricas;
	@Autowired private CommunityProjectService projects;
	@Autowired private TransactionTemplate transactions;
	@Autowired private EntityManager entityManager;
	@Autowired private SanctionRepository sancoes;
	@MockitoBean private EmailService email;
	@MockitoSpyBean private OutboxEventRepository outbox;
	private OutboxWorker worker;
	private final Clock relogioEntrega = Clock.fixed(Instant.parse("2090-01-01T00:00:00Z"), ZoneOffset.UTC);

	private final List<String> contas = new ArrayList<>();
	private final List<String> projetos = new ArrayList<>();
	private final List<String> contasSinteticas = new ArrayList<>();
	private final String marcador = "notif676_" + UUID.randomUUID().toString().substring(0, 8);
	private TestAuthHelper auth;
	private AuthSession moderador;
	private AuthSession autor;

	@BeforeEach
	void preparar() throws Exception {
		worker = new OutboxWorker(eventosOutbox, recibosOutbox, consumidores, relogioEntrega, transacoes, metricas, 60, 20);
		doAnswer(invocacao -> {
			invocacao.callRealMethod();
			adiarEventos(invocacao.getArgument(1, String.class));
			return null;
		}).when(outbox).append(eq("CommunityProjectPublished"), anyString(), anyString());
		auth = new TestAuthHelper(mvc, json);
		moderador = auth.authenticate("notif676_mod");
		autor = conta("autor");
	}

	@AfterEach
	void limpar() {
		for (String projeto : projetos) {
			jdbc.update("delete from processed_event_handlers where event_id in (select id from outbox_events where aggregate_id = ?)", projeto);
			jdbc.update("delete from outbox_events where aggregate_id = ?", projeto);
			jdbc.update("delete from community_projects where id = ?", projeto);
		}
		for (String conta : contas) {
			jdbc.update("delete from notification_preferences where user_id = ?", conta);
			jdbc.update("delete from notifications where user_id = ? or actor_id = ?", conta, conta);
			jdbc.update("delete from user_blocks where blocker_id = ? or blocked_id = ?", conta, conta);
			jdbc.update("delete from user_sanctions where user_id = ?", conta);
		}
		for (String conta : contasSinteticas) jdbc.update("delete from users where id = ?", conta);
	}

	@Test
	void aprovacaoPublicaUmAvisoPorProjetoApenasParaAssinantes() throws Exception {
		var assinante = conta("assinante");
		var padrao = conta("padrao");
		var desligado = conta("desligado");
		preferencia(assinante, true);
		preferencia(autor, true);
		preferencia(desligado, false);
		String projeto = enviar("Primeiro projeto");
		assertThat(eventos(projeto)).isEmpty();

		mvc.perform(post("/moderation/community-projects/" + projeto + "/approve")
				.header("X-Session-Token", assinante.token())).andExpect(status().isForbidden());
		assertThat(eventos(projeto)).isEmpty();
		aprovar(projeto);
		assertThat(avisos(assinante.userId())).isZero();
		String evento = unicoEvento(projeto);
		assertThat(worker.processOne(evento)).isTrue();
		assertThat(worker.processOne(evento)).isFalse();
		assertThat(avisos(assinante.userId())).isEqualTo(1);
		assertThat(avisos(autor.userId())).isZero();
		assertThat(avisos(padrao.userId())).isZero();
		assertThat(avisos(desligado.userId())).isZero();

		String username = jdbc.queryForObject("select username from users where id = ?", String.class, autor.userId());
		String href = "/projetos/" + username + "/primeiro-projeto";
		mvc.perform(get("/notifications").header("X-Session-Token", assinante.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].kind").value("NEW_COMMUNITY_PROJECT"))
			.andExpect(jsonPath("$.items[0].module").value("COMMUNITY_PROJECTS"))
			.andExpect(jsonPath("$.items[0].context").value("SOCIAL"))
			.andExpect(jsonPath("$.items[0].actorId").value(autor.userId()))
			.andExpect(jsonPath("$.items[0].href").value(href))
			.andExpect(jsonPath("$.items[0].read").value(false));
		mvc.perform(get("/community-projects/" + username + "/primeiro-projeto"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.id").value(projeto));

		String segundo = enviar("Segundo projeto");
		aprovar(segundo);
		processar(unicoEvento(segundo));
		assertThat(avisos(assinante.userId())).isEqualTo(2);
	}

	@Test
	void recusaEdicaoERestauracaoNaoGeramNovoLancamento() throws Exception {
		var assinante = conta("ciclo");
		preferencia(assinante, true);
		String projeto = enviar("Projeto revisado");
		mvc.perform(post("/moderation/community-projects/" + projeto + "/reject")
				.header("X-Session-Token", moderador.token()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"note\":\"Ajustar apresentação\"}")).andExpect(status().isOk());
		assertThat(eventos(projeto)).isEmpty();
		mvc.perform(post("/community-projects/" + projeto + "/resubmit")
				.header("X-Session-Token", autor.token())).andExpect(status().isOk());
		assertThat(eventos(projeto)).isEmpty();
		aprovar(projeto);
		processar(unicoEvento(projeto));
		mvc.perform(patch("/community-projects/" + projeto).header("X-Session-Token", autor.token())
				.contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Projeto ajustado\"}"))
			.andExpect(status().isOk());
		mvc.perform(post("/admin/community-projects/" + projeto + "/suspend")
				.header("X-Session-Token", moderador.token())).andExpect(status().isOk());
		mvc.perform(post("/admin/community-projects/" + projeto + "/unsuspend")
				.header("X-Session-Token", moderador.token())).andExpect(status().isOk());
		assertThat(eventos(projeto)).hasSize(1);
		assertThat(avisos(assinante.userId())).isEqualTo(1);
	}

	@Test
	void rollbackDaAprovacaoTambemDesfazOEvento() throws Exception {
		String projeto = enviar("Projeto transacional");
		transactions.executeWithoutResult(tx -> {
			projects.approve(projeto, moderador.userId(), null);
			entityManager.flush();
			assertThat(eventos(projeto)).hasSize(1);
			tx.setRollbackOnly();
		});
		assertThat(eventos(projeto)).isEmpty();
		assertThat(jdbc.queryForObject("select status from community_projects where id = ?", String.class, projeto))
			.isEqualTo("SUBMITTED");
	}

	@Test
	void entregaConfereAdesaoEProibeBloqueiosNosDoisSentidos() throws Exception {
		var desistente = conta("desistente");
		var bloqueador = conta("bloqueador");
		var bloqueado = conta("bloqueado");
		var assinante = conta("permitido");
		for (var conta : List.of(desistente, bloqueador, bloqueado, assinante)) preferencia(conta, true);
		String projeto = enviar("Projeto reservado");
		aprovar(projeto);
		preferencia(desistente, false);
		bloquear(bloqueador.userId(), autor.userId());
		bloquear(autor.userId(), bloqueado.userId());
		processar(unicoEvento(projeto));
		assertThat(avisos(desistente.userId())).isZero();
		assertThat(avisos(bloqueador.userId())).isZero();
		assertThat(avisos(bloqueado.userId())).isZero();
		assertThat(avisos(assinante.userId())).isEqualTo(1);
		bloquear(assinante.userId(), autor.userId());
		mvc.perform(get("/notifications").header("X-Session-Token", assinante.token()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
	}

	@ParameterizedTest
	@ValueSource(strings = { "suspenso", "removido", "autor_excluido", "autor_ausente", "autor_banido" })
	void entregaDescartaProjetoOuAutorIndisponivel(String motivo) throws Exception {
		var assinante = conta("indisponivel");
		preferencia(assinante, true);
		String projeto = enviar("Projeto indisponível");
		aprovar(projeto);
		switch (motivo) {
			case "suspenso" -> projects.suspend(projeto, moderador.userId(), null);
			case "removido" -> projects.remove(projeto, autor.userId());
			case "autor_excluido" -> jdbc.update("update users set username = ? where id = ?", "removed-" + marcador, autor.userId());
			case "autor_ausente" -> jdbc.update("update community_projects set user_id = ? where id = ?", marcador + "_ausente", projeto);
			case "autor_banido" -> banir(autor.userId());
			default -> throw new IllegalArgumentException("Motivo de indisponibilidade desconhecido");
		}
		String evento = unicoEvento(projeto);
		assertThat(worker.processOne(evento)).isTrue();
		assertThat(avisos(assinante.userId())).isZero();
		assertThat(jdbc.queryForObject("select status from outbox_events where id = ?", String.class, evento))
			.isEqualTo("PROCESSED");
	}

	@Test
	void paginaDeDuzentosContinuaPorCursorEReciboEvitaRepetirLote() throws Exception {
		List<String> assinantes = assinantesSinteticos(201);
		String projeto = enviar("Projeto paginado");
		aprovar(projeto);
		String inicio = unicoEvento(projeto);
		processar(inicio);
		assertThat(totalDeAvisosDoAutor()).isEqualTo(200);
		assertThat(eventos(projeto)).hasSize(2);
		String continuacao = eventos(projeto).stream().filter(id -> !id.equals(inicio)).findFirst().orElseThrow();
		String payload = jdbc.queryForObject("select payload from outbox_events where id = ?", String.class, continuacao);
		assertThat(json.readTree(payload).get("afterUserId").asString()).isEqualTo(assinantes.get(199));
		int recibos = jdbc.queryForObject("select count(*) from processed_event_handlers where event_id = ?", Integer.class, inicio);
		assertThat(recibos).isPositive();

		// Queda apos o commit do handler e antes de marcar o evento: o recibo permanece.
		jdbc.update("update outbox_events set status = 'PENDING', claimed_at = null, next_attempt_at = ? where id = ?",
			instanteElegivel(), inicio);
		processar(inicio);
		assertThat(totalDeAvisosDoAutor()).isEqualTo(200);
		assertThat(eventos(projeto)).hasSize(2);
		assertThat(jdbc.queryForObject("select count(*) from processed_event_handlers where event_id = ?", Integer.class, inicio))
			.isEqualTo(recibos);
		processar(continuacao);
		assertThat(totalDeAvisosDoAutor()).isEqualTo(201);
		assertThat(eventos(projeto)).hasSize(2);
		for (String assinante : assinantes) assertThat(avisos(assinante)).isEqualTo(1);
	}

	@Test
	void falhaAoContinuarDesfazAvisosEReciboDoLote() throws Exception {
		assinantesSinteticos(201);
		String projeto = enviar("Projeto com retentativa");
		aprovar(projeto);
		String evento = unicoEvento(projeto);
		AtomicBoolean primeira = new AtomicBoolean(true);
		doAnswer(invocacao -> {
			if (primeira.getAndSet(false)) throw new IllegalStateException("Falha simulada ao continuar projeto");
			invocacao.callRealMethod();
			adiarEventos(projeto);
			return null;
		}).when(outbox).append(eq("CommunityProjectPublished"), eq(projeto), anyString());
		processar(evento);
		assertThat(totalDeAvisosDoAutor()).isZero();
		assertThat(eventos(projeto)).hasSize(1);
		assertThat(jdbc.queryForObject("select last_error from outbox_events where id = ?", String.class, evento))
			.contains("Falha simulada ao continuar projeto");
		assertThat(jdbc.queryForObject("select count(*) from processed_event_handlers where event_id = ? and handler like '%NotificadorProjetoComunidade'",
			Integer.class, evento)).isZero();
		jdbc.update("update outbox_events set next_attempt_at = ? where id = ?", instanteElegivel(), evento);
		processar(evento);
		assertThat(totalDeAvisosDoAutor()).isEqualTo(200);
		assertThat(eventos(projeto)).hasSize(2);
		assertThat(jdbc.queryForObject("select count(*) from processed_event_handlers where event_id = ? and handler like '%NotificadorProjetoComunidade'",
			Integer.class, evento)).isEqualTo(1);
	}

	@Test
	void aprovacoesConcorrentesRegistramUmUnicoLancamento() throws Exception {
		String projeto = enviar("Projeto concorrente");
		CountDownLatch inicio = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var primeira = executor.submit(() -> aprovarConcorrentemente(projeto, inicio));
			var segunda = executor.submit(() -> aprovarConcorrentemente(projeto, inicio));
			inicio.countDown();
			assertThat(List.of(primeira.get(20, TimeUnit.SECONDS), segunda.get(20, TimeUnit.SECONDS)))
				.containsExactlyInAnyOrder("PUBLISHED", "NOT_SUBMITTED");
		}
		assertThat(eventos(projeto)).hasSize(1);
	}

	private String aprovarConcorrentemente(String projeto, CountDownLatch inicio) throws InterruptedException {
		if (!inicio.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Aprovação não iniciou no prazo");
		try {
			return projects.approve(projeto, moderador.userId(), null).status().name();
		}
		catch (ApiException conflito) {
			return conflito.code();
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "desistiu", "suspenso", "autor_excluido" })
	void continuacaoRevalidaAdesaoEPublicacao(String mudanca) throws Exception {
		var assinantes = assinantesSinteticos(201);
		String projeto = enviar("Projeto entre lotes");
		aprovar(projeto);
		String primeiro = unicoEvento(projeto);
		processar(primeiro);
		assertThat(totalDeAvisosDoAutor()).isEqualTo(200);
		switch (mudanca) {
			case "desistiu" -> jdbc.update("update notification_preferences set community_projects = false where user_id = ?", assinantes.getLast());
			case "suspenso" -> projects.suspend(projeto, moderador.userId(), null);
			case "autor_excluido" -> jdbc.update("update users set username = ? where id = ?", "removed-" + marcador, autor.userId());
			default -> throw new IllegalArgumentException("Mudança entre lotes desconhecida");
		}
		String continuacao = eventos(projeto).stream().filter(id -> !id.equals(primeiro)).findFirst().orElseThrow();
		processar(continuacao);
		assertThat(totalDeAvisosDoAutor()).isEqualTo(200);
		assertThat(avisos(assinantes.getLast())).isZero();
	}

	@Test
	void destinatariosExcluidosAusentesEBanidosNaoRecebem() throws Exception {
		var assinantes = assinantesSinteticos(4);
		jdbc.update("update users set username = ? where id = ?", "removed-" + marcador, assinantes.get(0));
		jdbc.update("delete from users where id = ?", assinantes.get(1));
		banir(assinantes.get(2));
		String projeto = enviar("Projeto com contas ativas");
		aprovar(projeto);
		processar(unicoEvento(projeto));
		assertThat(totalDeAvisosDoAutor()).isEqualTo(1);
		assertThat(avisos(assinantes.getLast())).isEqualTo(1);
	}

	@ParameterizedTest
	@ValueSource(strings = { "{}", "[]", "{", "null", "", "{\"afterUserId\":true}" })
	void eventoMalformadoFalhaExplicitamenteSemReciboDeEntrega(String payload) throws Exception {
		String projeto = enviar("Projeto com evento inválido");
		aprovar(projeto);
		String original = unicoEvento(projeto);
		transactions.executeWithoutResult(tx -> outbox.append("CommunityProjectPublished", projeto, payload));
		String evento = eventos(projeto).stream().filter(id -> !id.equals(original)).findFirst().orElseThrow();
		processar(evento);
		assertThat(jdbc.queryForObject("select status from outbox_events where id = ?", String.class, evento)).isEqualTo("PENDING");
		assertThat(jdbc.queryForObject("select attempts from outbox_events where id = ?", Integer.class, evento)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select last_error from outbox_events where id = ?", String.class, evento)).contains("afterUserId");
		assertThat(jdbc.queryForObject("select count(*) from processed_event_handlers where event_id = ? and handler like '%NotificadorProjetoComunidade'",
			Integer.class, evento)).isZero();
		assertThat(totalDeAvisosDoAutor()).isZero();
	}

	private void banir(String conta) {
		sancoes.save(UserSanction.ban(marcador + "_sancao", conta, marcador, "Sanção do cenário", java.time.Instant.now()));
	}

	/** Outros contextos compartilham o H2 e têm workers reais; só o relógio deste cenário vence o prazo. */
	private void adiarEventos(String projeto) {
		entityManager.flush();
		jdbc.update("update outbox_events set next_attempt_at = ? where aggregate_id = ? and event_type = 'CommunityProjectPublished' and status = 'PENDING'",
			instanteElegivel(), projeto);
	}

	private java.sql.Timestamp instanteElegivel() {
		return java.sql.Timestamp.from(relogioEntrega.instant().minusSeconds(1));
	}

	private List<String> assinantesSinteticos(int quantidade) {
		List<String> assinantes = new ArrayList<>();
		for (int i = 0; i < quantidade; i++) {
			String id = marcador + "_lote_" + "%04d".formatted(i);
			contas.add(id);
			contasSinteticas.add(id);
			jdbc.update("""
				insert into users (id, username, name, email, open_to_work, is_mentor, created_at, updated_at)
				values (?, ?, 'Assinante do cenário', ?, false, false, current_timestamp, current_timestamp)""",
				id, id, id + "@example.invalid");
			jdbc.update("insert into notification_preferences (user_id, community_projects, updated_at) values (?, true, current_timestamp)", id);
			assinantes.add(id);
		}
		return assinantes;
	}

	private int totalDeAvisosDoAutor() {
		return jdbc.queryForObject("select count(*) from notifications where actor_id = ? and kind = 'NEW_COMMUNITY_PROJECT'",
			Integer.class, autor.userId());
	}

	private void processar(String evento) {
		assertThat(worker.processOne(evento)).isTrue();
	}

	private AuthSession conta(String sufixo) throws Exception {
		var conta = auth.authenticate(marcador + "_" + sufixo);
		contas.add(conta.userId());
		return conta;
	}

	private void preferencia(AuthSession conta, boolean ligada) throws Exception {
		mvc.perform(put("/me/notification-preferences").header("X-Session-Token", conta.token())
				.contentType(MediaType.APPLICATION_JSON).content("{\"communityProjects\":" + ligada + "}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.communityProjects").value(ligada));
	}

	private String enviar(String titulo) throws Exception {
		var resultado = mvc.perform(post("/community-projects").header("X-Session-Token", autor.token())
				.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("title", titulo))))
			.andExpect(status().isOk()).andReturn();
		String id = json.readTree(resultado.getResponse().getContentAsString()).get("id").asString();
		projetos.add(id);
		return id;
	}

	private void aprovar(String projeto) throws Exception {
		mvc.perform(post("/moderation/community-projects/" + projeto + "/approve")
				.header("X-Session-Token", moderador.token())).andExpect(status().isOk());
	}

	private List<String> eventos(String projeto) {
		return jdbc.queryForList("select id from outbox_events where aggregate_id = ? and event_type = 'CommunityProjectPublished'",
			String.class, projeto);
	}

	private String unicoEvento(String projeto) {
		var eventos = eventos(projeto);
		assertThat(eventos).hasSize(1);
		return eventos.getFirst();
	}

	private int avisos(String conta) {
		return jdbc.queryForObject("select count(*) from notifications where user_id = ? and kind = 'NEW_COMMUNITY_PROJECT'",
			Integer.class, conta);
	}

	private void bloquear(String origem, String destino) {
		jdbc.update("insert into user_blocks (blocker_id, blocked_id, created_at) values (?, ?, current_timestamp)", origem, destino);
	}
}
