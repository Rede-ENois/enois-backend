package com.socialcoisa.rede.network.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.network.application.NetworkTaskService;
import com.socialcoisa.rede.network.domain.DataClass;
import com.socialcoisa.rede.network.domain.TaskType;
import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestDatabase;

import tools.jackson.databind.ObjectMapper;

/**
 * A DLQ aberta por fila no painel de operação (brief §8.2, "DLQ por fila").
 *
 * <p>O total de {@code dead} diz que há tarefa esgotada; não diz em qual tipo. O que aqui se prende é
 * a <b>atribuição</b>: cada tarefa morta tem de aparecer na fila dela, e não na de outra nem no
 * bolo. Uma implementação que somasse tudo num tipo só passaria num teste de "existe um número
 * grande"; por isso cada caso tem os dois lados — a fila que deve e a que não deve.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class NetworkTaskQueueTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private NetworkTaskService tasks;

	@BeforeEach
	void setUp() {
		jdbc.update("delete from network_tasks");
	}

	@Test
	void aTarefaMortaApareceNaFilaDelaENaoNoBolo() throws Exception {
		String admin = adminToken();
		morta(TaskType.OBJECT_PUT);
		morta(TaskType.OBJECT_PUT);
		morta(TaskType.MEDIA_THUMBNAIL);
		// Não é terminal sem sucesso: não entra no quadro da DLQ.
		viva(TaskType.OBJECT_PUT);

		mockMvc
			.perform(get("/admin/network/tasks/overview").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.dead").value(3))
			.andExpect(jsonPath("$.deadByQueue.OBJECT_PUT.DEAD").value(2))
			.andExpect(jsonPath("$.deadByQueue.MEDIA_THUMBNAIL.DEAD").value(1))
			// Uma fila morta não contamina a vizinha.
			.andExpect(jsonPath("$.deadByQueue.OBJECT_GET").doesNotExist())
			// Tarefa viva não aparece como morta.
			.andExpect(jsonPath("$.deadByQueue.OBJECT_PUT.FAILED").doesNotExist());
	}

	@Test
	void falhadaECanceladaAparecemSeparadasDaMorta() throws Exception {
		String admin = adminToken();
		morta(TaskType.ASYNC_JOB);
		comEstado(TaskType.ASYNC_JOB, "FAILED");
		comEstado(TaskType.ASYNC_JOB, "CANCELLED");

		mockMvc
			.perform(get("/admin/network/tasks/overview").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.deadByQueue.ASYNC_JOB.DEAD").value(1))
			.andExpect(jsonPath("$.deadByQueue.ASYNC_JOB.FAILED").value(1))
			.andExpect(jsonPath("$.deadByQueue.ASYNC_JOB.CANCELLED").value(1));
	}

	@Test
	void semTarefaTerminalOQuadroVemVazio() throws Exception {
		String admin = adminToken();
		viva(TaskType.EDGE_CACHE);

		var corpo = mockMvc
			.perform(get("/admin/network/tasks/overview").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.dead").value(0))
			.andReturn()
			.getResponse()
			.getContentAsString();
		var dead = objectMapper.readTree(corpo).get("deadByQueue");
		assertThat(dead.isEmpty()).as("nada terminal: quadro vazio, não uma lista de zeros").isTrue();
	}

	private void morta(TaskType tipo) {
		comEstado(tipo, "DEAD");
	}

	private void comEstado(TaskType tipo, String estado) {
		var task = tasks.enqueue(new NetworkTaskService.EnqueueCommand(tipo, "q-" + java.util.UUID.randomUUID(),
			"{}", DataClass.PUBLIC, null));
		jdbc.update("update network_tasks set status = ?, last_error = 'TASK_ABANDONED' where id = ?", estado,
			task.id());
	}

	private void viva(TaskType tipo) {
		tasks.enqueue(new NetworkTaskService.EnqueueCommand(tipo, "q-" + java.util.UUID.randomUUID(), "{}",
			DataClass.PUBLIC, null));
	}

	private String adminToken() throws Exception {
		var auth = new TestAuthHelper(mockMvc, objectMapper);
		var session = auth.authenticate("net_queue_admin");
		TestDatabase.setAdmin(session.userId());
		return session.token();
	}
}