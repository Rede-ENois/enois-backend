package com.socialcoisa.rede.notifications.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

/**
 * Issue #1147 (front): dez avisos LIDOS mais recentes escondiam o aviso nao lido mais antigo atras
 * da primeira pagina, e o sino ficava parado em 1 para sempre.
 *
 * <p>Com {@code unreadFirst=true} a lista entrega primeiro os nao lidos, na ordem de chegada, e
 * depois os lidos; o cursor desse modo e a posicao na ordem, entao a pagina seguinte nao repete nem
 * pula linha na troca de grupo. Sem o parametro, a ordem e a de sempre.</p>
 *
 * <p>A base H2 e compartilhada com as outras classes, entao cada cenario so afirma sobre as proprias
 * linhas: os ids levam um sufixo aleatorio e a leitura filtra por ele.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class NotificationUnreadFirstTests {

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
	}

	/** O cenario da issue: 10 lidos recentes na frente e o nao lido antigo na pagina 2. */
	@Test
	void theOldestUnreadComesFirstEvenBehindTenReadOnes() throws Exception {
		var session = auth.authenticate("notif_unread_first");
		Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		insert(session.userId(), "uf_unread_old", "NEW_REACTION", false, base.minusSeconds(3600));
		for (int i = 0; i < 10; i++) {
			insert(session.userId(), "uf_read_" + i, "NEW_COMMENT", true, base.minusSeconds(600 - i));
		}

		JsonNode first = page(session.token(), 10, "SOCIAL", null, true);
		assertThat(mine(first, "uf_")).containsExactly("uf_unread_old", "uf_read_9", "uf_read_8", "uf_read_7",
			"uf_read_6", "uf_read_5", "uf_read_4", "uf_read_3", "uf_read_2", "uf_read_1");
		assertThat(first.get("nextCursor").asString()).isNotBlank();

		JsonNode second = page(session.token(), 10, "SOCIAL", first.get("nextCursor").asString(), true);
		assertThat(mine(second, "uf_")).containsExactly("uf_read_0");

		// Sem o parametro nada muda: o lido mais recente continua na frente e o nao lido fica fora
		// da primeira pagina — o bug da issue, preservado como default.
		JsonNode legacy = page(session.token(), 10, "SOCIAL", null, null);
		assertThat(mine(legacy, "uf_")).doesNotContain("uf_unread_old").hasSize(10);
	}

	@Test
	void severalUnreadKeepTheArrivalOrderAheadOfTheReadOnes() throws Exception {
		var session = auth.authenticate("notif_unread_several");
		Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		insert(session.userId(), "ufs_read_a", "NEW_COMMENT", true, base.minusSeconds(10));
		insert(session.userId(), "ufs_read_b", "NEW_COMMENT", true, base.minusSeconds(20));
		insert(session.userId(), "ufs_read_c", "NEW_COMMENT", true, base.minusSeconds(30));
		insert(session.userId(), "ufs_unread_newest", "NEW_REACTION", false, base.minusSeconds(40));
		insert(session.userId(), "ufs_unread_middle", "MENTION", false, base.minusSeconds(50));
		insert(session.userId(), "ufs_unread_oldest", "NEW_REACTION", false, base.minusSeconds(60));

		assertThat(mine(page(session.token(), 20, null, null, true), "ufs_")).containsExactly(
			"ufs_unread_newest", "ufs_unread_middle", "ufs_unread_oldest", "ufs_read_a", "ufs_read_b", "ufs_read_c");
	}

	/** Tudo lido: a ordem continua a de chegada, como antes da mudanca, e o resumo concorda. */
	@Test
	void everythingReadKeepsThePlainOrderAndTheSummaryAgrees() throws Exception {
		var session = auth.authenticate("notif_unread_none");
		Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		for (int i = 0; i < 4; i++) {
			insert(session.userId(), "ufn_read_" + i, "NEW_COMMENT", true, base.minusSeconds(100 - i));
		}

		assertThat(mine(page(session.token(), 20, null, null, true), "ufn_")).containsExactly("ufn_read_3",
			"ufn_read_2", "ufn_read_1", "ufn_read_0");
		mockMvc.perform(get("/notifications/unread-summary").header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(0));
	}

	/**
	 * O cursor do modo novo e a posicao na ordem: pagina a pagina, como o front faz, nenhuma linha do
	 * cenario se repete, a troca de grupo nao pula os lidos e o ultimo lido e entregue.
	 */
	@Test
	void theCursorWalksThroughBothGroupsWithoutRepeatingOrSkipping() throws Exception {
		var session = auth.authenticate("notif_unread_cursor");
		Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		List<String> expected = new ArrayList<>();
		for (int i = 4; i > 0; i--) {
			String id = "ufc_unread_" + (i - 1);
			insert(session.userId(), id, "NEW_REACTION", false, base.minusSeconds(1000 - (i - 1)));
			expected.add(id);
		}
		for (int i = 6; i > 0; i--) {
			String id = "ufc_read_" + (i - 1);
			insert(session.userId(), id, "NEW_COMMENT", true, base.minusSeconds(500 - (i - 1)));
			expected.add(id);
		}

		List<String> seen = new ArrayList<>();
		String cursor = null;
		for (int pages = 0; pages < 6; pages++) {
			JsonNode page = page(session.token(), 4, "SOCIAL", cursor, true);
			seen.addAll(mine(page, "ufc_"));
			cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asString();
			if (cursor == null) {
				break;
			}
		}

		assertThat(seen).doesNotHaveDuplicates().containsExactlyElementsOf(expected);
	}

	/** Contextos diferentes nao emprestam o nao lido um do outro. */
	@Test
	void eachContextBringsItsOwnUnreadFirst() throws Exception {
		var session = auth.authenticate("notif_unread_context");
		Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		insert(session.userId(), "ufx_social_read", "NEW_REACTION", true, base.minusSeconds(10));
		insert(session.userId(), "ufx_social_unread", "NEW_COMMENT", false, base.minusSeconds(20));
		insert(session.userId(), "ufx_work_read", "JOB_INTERVIEW", true, base.minusSeconds(30));
		insert(session.userId(), "ufx_work_unread", "JOB_APPLICATION_STATUS", false, base.minusSeconds(40));

		assertThat(mine(page(session.token(), 20, "SOCIAL", null, true), "ufx_social_"))
			.containsExactly("ufx_social_unread", "ufx_social_read");
		assertThat(mine(page(session.token(), 20, "PROFESSIONAL", null, true), "ufx_work_"))
			.containsExactly("ufx_work_unread", "ufx_work_read");
	}

	/**
	 * O ator bloqueado sai da pagina; a posicao do cursor anda pelo tamanho lido, entao a linha
	 * seguinte nao e repetida nem pulada.
	 */
	@Test
	void blockedActorStaysOutOfBothModes() throws Exception {
		var session = auth.authenticate("notif_unread_blocker");
		var blocked = auth.authenticate("notif_unread_blocked");
		Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		mockMvc.perform(post("/network/blocks/" + blocked.userId())
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "uf-block-" + System.nanoTime()))
			.andExpect(status().isCreated());
		// Os tres avisos do bloqueado sao os mais recentes: com pagina de 1, as tres primeiras linhas
		// sao descartadas pelo filtro, entao duas paginas saem vazias — e cada uma traz o cursor da
		// posicao seguinte.
		for (int i = 0; i < 3; i++) {
			insertFrom(session.userId(), "ufb_blocked_" + i, "NEW_REACTION", blocked.userId(), false,
				base.plusSeconds(10 - i));
		}
		List<String> expected = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			String id = "ufb_unread_" + i;
			insert(session.userId(), id, "NEW_COMMENT", false, base.minusSeconds(60 + i));
			expected.add(id);
		}
		for (int i = 0; i < 3; i++) {
			String id = "ufb_read_" + i;
			insert(session.userId(), id, "NEW_COMMENT", true, base.minusSeconds(200 + i));
			expected.add(id);
		}

		List<String> seen = new ArrayList<>();
		String cursor = null;
		int empty = 0;
		for (int pages = 0; pages < 10; pages++) {
			JsonNode page = page(session.token(), 1, "SOCIAL", cursor, true);
			assertThat(page.get("items").toString()).doesNotContain("ufb_blocked_");
			if (page.get("items").isEmpty()) {
				empty++;
				assertThat(page.get("nextCursor").isNull()).as("pagina vazia do filtro continua a posicao")
					.isFalse();
			}
			seen.addAll(mine(page, "ufb_"));
			cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asString();
			if (cursor == null) {
				break;
			}
		}

		assertThat(empty).isGreaterThan(0);
		assertThat(seen).doesNotHaveDuplicates().containsExactlyElementsOf(expected);
	}

	@Test
	void rejectsAnyValueOtherThanTrueOrFalse() throws Exception {
		var session = auth.authenticate("notif_unread_param");
		mockMvc.perform(get("/notifications").param("unreadFirst", "talvez")
				.header("X-Session-Token", session.token()))
			.andExpect(status().isBadRequest());
	}

	/** No modo dos nao lidos o cursor e a posicao: posicao negativa e lixo nao viram pagina 1. */
	@Test
	void rejectsACursorThatIsNotAPosition() throws Exception {
		var session = auth.authenticate("notif_unread_cursor_param");
		for (String cursor : new String[] { "o:-1", "o:abc" }) {
			mockMvc.perform(get("/notifications").param("unreadFirst", "true").param("cursor", cursor)
					.header("X-Session-Token", session.token()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
		}
	}

	private JsonNode page(String token, int limit, String context, String cursor, Boolean unreadFirst)
			throws Exception {
		var request = get("/notifications").param("limit", String.valueOf(limit))
			.header("X-Session-Token", token);
		if (unreadFirst != null) {
			request.param("unreadFirst", String.valueOf(unreadFirst));
		}
		if (context != null) {
			request.param("context", context);
		}
		if (cursor != null) {
			request.param("cursor", cursor);
		}
		String body = mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse()
			.getContentAsString();
		return objectMapper.readTree(body);
	}

	/** Ids do cenario, sem o sufixo aleatorio, na ordem da pagina. */
	private static List<String> mine(JsonNode page, String prefix) {
		List<String> ids = new ArrayList<>();
		page.get("items").forEach(item -> {
			String id = item.get("id").asString();
			if (id.startsWith(prefix)) {
				ids.add(id.substring(0, id.lastIndexOf('_')));
			}
		});
		return ids;
	}

	private void insert(String userId, String id, String kind, boolean read, Instant createdAt) {
		insertFrom(userId, id, kind, "sistema", read, createdAt);
	}

	/** {@code id} e so a etiqueta do cenario; o id gravado e ele mais um sufixo aleatorio. */
	private void insertFrom(String userId, String id, String kind, String actorId, boolean read, Instant createdAt) {
		jdbc.update("""
				insert into notifications (id, user_id, kind, actor_id, title, body, is_read, created_at)
				values (?, ?, ?, ?, 'Aviso', 'corpo', ?, ?)
				""", id + "_" + UUID.randomUUID().toString().substring(0, 8), userId, kind, actorId, read,
			Timestamp.from(createdAt));
	}
}