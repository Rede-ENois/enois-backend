package com.socialcoisa.rede.network.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * O participante vendo a <b>própria cota</b> do Storage (brief §18: "consultar espaço utilizado",
 * "consultar sua contribuição").
 *
 * <p>Até o ciclo 80 a cota só existia na recusa: quem recebia {@code 507} ficava sabendo os números
 * daquele instante, e mais ninguém. O participante — que é justamente quem controla a própria conta —
 * só descobria o limite ao bater nele, sem poder apagar antes de encher nem pedir mais ao operador
 * com o número na mão.</p>
 *
 * <p>O discriminante: a rota devolve o limite configurado, o usado medido pelo banco e o livre; outro
 * participante vê os próprios números (a cota é por dono); e a instalação sem cota responde
 * {@code limite = 0} com {@code livre = null} — "sem limite" não pode virar "cheio".</p>
 */
@SpringBootTest
@TestPropertySource(properties = "rede.storage.max-bytes-per-owner=" + NetworkParticipantStorageQuotaTests.COTA)
@AutoConfigureMockMvc
class NetworkParticipantStorageQuotaTests {

	/** 1 KB: cota pequena o bastante para o teste provar o número exato sem gravar corpo. */
	static final long COTA = 1024;

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
	}

	/** Um objeto contável: corrente, sem lápide — é o que {@code bytesOwnedBy} soma. */
	private void objeto(String dono, long bytes, String estado) {
		jdbc.update("""
			insert into network_storage_objects
				(id, bucket, object_key, content_type, bytes, sha256, state, created_by, created_at, updated_at)
			values (?, 'fotos', ?, 'image/jpeg', ?, ?, ?, ?, now(3), now(3))
			""", "sob_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10),
			UUID.randomUUID() + ".jpg", bytes, "a".repeat(64), estado, dono);
	}

	@Test
	void participante_ve_a_propria_cota_com_o_que_usa_e_o_que_resta() throws Exception {
		var dono = auth.authenticate("quota_painel_" + UUID.randomUUID().toString().substring(0, 8));
		objeto(dono.userId(), 300, "STORED");
		objeto(dono.userId(), 200, "STORED");
		// Lápide não conta: é o mesmo critério da recusa, e o painel não pode contradizê-la.
		objeto(dono.userId(), 500, "DELETED");

		mockMvc.perform(get("/network/participant/storage/quota").header("X-Session-Token", dono.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.limite").value((int) COTA))
			.andExpect(jsonPath("$.usado").value(500))
			.andExpect(jsonPath("$.livre").value((int) COTA - 500));
	}

	@Test
	void a_cota_e_do_dono_e_o_vizinho_nao_paga() throws Exception {
		var primeiro = auth.authenticate("quota_painel_a_" + UUID.randomUUID().toString().substring(0, 8));
		var segundo = auth.authenticate("quota_painel_b_" + UUID.randomUUID().toString().substring(0, 8));
		objeto(primeiro.userId(), 900, "STORED");

		assertThat(mockMvc.perform(get("/network/participant/storage/quota")
			.header("X-Session-Token", segundo.token())).andReturn().getResponse().getContentAsString())
			.contains("\"usado\":0");
		mockMvc.perform(get("/network/participant/storage/quota").header("X-Session-Token", primeiro.token()))
			.andExpect(jsonPath("$.usado").value(900))
			.andExpect(jsonPath("$.livre").value((int) COTA - 900));
	}

	@Test
	void sem_sessao_nao_le_cota_de_ninguem() throws Exception {
		mockMvc.perform(get("/network/participant/storage/quota")).andExpect(status().isUnauthorized());
	}
}
