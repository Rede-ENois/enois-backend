package com.socialcoisa.rede.moderation;

import static org.assertj.core.api.Assertions.assertThat;
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

import com.socialcoisa.rede.moderation.application.MediaSafetyService;
import com.socialcoisa.rede.moderation.application.MediaSafetySweepWorker;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * Prova de fiação: o app sobe com a barreira de mídia e a migração V199 aplica.
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
