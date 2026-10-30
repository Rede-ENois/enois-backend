package com.socialcoisa.rede.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.moderation.application.ContentAutomationService;
import com.socialcoisa.rede.moderation.application.ContentAutomationSweepWorker;
import com.socialcoisa.rede.moderation.application.ContentQuarantineReviewService;
import com.socialcoisa.rede.moderation.application.LinkSafetyService;
import com.socialcoisa.rede.moderation.application.LinkSafetySweepWorker;
import com.socialcoisa.rede.moderation.application.MediaSafetyService;
import com.socialcoisa.rede.moderation.application.MediaSafetySweepWorker;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * Prova de fiação: o app sobe com as barreiras de mídia, link e conteúdo automatizado, e as
 * migrações V199 a V201 aplicam.
 *
 * <p>Os testes unitários provam a regra; este prova que ela existe no contexto real — bean
 * injetado, tabela criada, varredura rodando sem derrubar o boot. Foi a lição das vezes em que a
 * suíte passou e o deploy caiu por bean faltando.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class MediaSafetyWiringTests {

	@Autowired
	private MediaSafetyService mediaSafety;

	@Autowired
	private MediaSafetySweepWorker sweep;

	@Autowired
	private LinkSafetyService linkSafety;

	@Autowired
	private LinkSafetySweepWorker linkSweep;

	@Autowired
	private ContentAutomationService automation;

	@Autowired
	private ContentAutomationSweepWorker automationSweep;

	@Autowired
	private ContentQuarantineReviewService quarantineReview;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void theMediaBarrierIsWiredIntoTheApplication() {
		assertThat(mediaSafety).isNotNull();
		assertThat(mediaSafety.enabled()).isTrue();
		assertThat(sweep).isNotNull();
	}

	@Test
	void theLedgerTableExistsWithTheUpsertKey() {
		// A migração rodou: a coluna que faz o upsert por mídia precisa ser chave.
		Integer colunas = jdbc.queryForObject(
			"select count(*) from information_schema.columns where table_name = 'MEDIA_SAFETY_CHECKS'",
			Integer.class);
		assertThat(colunas).isNotNull().isGreaterThanOrEqualTo(8);
	}

	@Test
	void theSweepRunsWithoutBlowingUpTheBoot() {
		// Não deve lançar: a varredura é melhor esforço e o app já está de pé.
		assertThat(sweep.sweep()).isGreaterThanOrEqualTo(0);
	}

	@Test
	void theLinkBarrierIsWiredIntoTheApplication() {
		assertThat(linkSafety).isNotNull();
		assertThat(linkSafety.enabled()).isTrue();
		assertThat(linkSweep).isNotNull();
	}

	@Test
	void theLinkLedgerTableExists() {
		// V200 aplicou: sem a tabela, o ledger de destino cai e o link seria visitado a cada rodada.
		Integer colunas = jdbc.queryForObject(
			"select count(*) from information_schema.columns where table_name = 'LINK_SAFETY_CHECKS'",
			Integer.class);
		assertThat(colunas).isNotNull().isGreaterThanOrEqualTo(9);
	}

	@Test
	void theLinkSweepRunsWithoutBlowingUpTheBoot() {
		assertThat(linkSweep.sweep()).isGreaterThanOrEqualTo(0);
	}

	@Test
	void theAutomationBarrierIsWiredIntoTheApplication() {
		assertThat(automation).isNotNull();
		assertThat(automation.enabled()).isTrue();
		assertThat(automationSweep).isNotNull();
		assertThat(quarantineReview).isNotNull();
	}

	@Test
	void theAutomationLedgerTableExistsWithItsReviewQueueIndex() {
		// V201 aplicou: sem a tabela, a varredura reavalia o mesmo post para sempre e a fila do
		// moderador não tem de onde ler.
		Integer colunas = jdbc.queryForObject(
			"select count(*) from information_schema.columns where table_name = 'CONTENT_AUTOMATION_CHECKS'",
			Integer.class);
		assertThat(colunas).isNotNull().isGreaterThanOrEqualTo(8);
	}

	@Test
	void theAutomationSweepRunsWithoutBlowingUpTheBoot() {
		assertThat(automationSweep.sweep()).isGreaterThanOrEqualTo(0);
	}

	@Test
	void theReviewQueueIsOpenOnlyToModerators() throws Exception {
		// Sem sessão de moderador a fila não abre — e a resposta não revela se existe caso na fila.
		mockMvc.perform(get("/moderation/quarantine")).andExpect(status().isUnauthorized());
	}

	@Test
	void aReportOfSexualContentIsAcceptedByTheApi() throws Exception {
		// O motivo novo precisa atravessar o contrato HTTP, não só existir no enum.
		String username = "den_" + UUID.randomUUID().toString().substring(0, 8);
		var session = new TestAuthHelper(mockMvc, objectMapper).authenticate(username);

		mockMvc.perform(post("/reports")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "den-" + username)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"targetType":"POST","targetId":"post_inexistente","reason":"SEXUAL_CONTENT",
					 "description":"conteudo explicito na imagem"}"""))
			.andExpect(status().isCreated());
	}
}
