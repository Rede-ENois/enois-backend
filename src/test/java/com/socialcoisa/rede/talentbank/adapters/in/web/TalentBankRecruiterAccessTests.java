package com.socialcoisa.rede.talentbank.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestAuthHelper.AuthSession;

import tools.jackson.databind.ObjectMapper;

/**
 * Quem administra o banco de talentos e quem enxerga a vitrine.
 *
 * <p>Três decisões que a interface não pode inverter: só quem está no modo recrutador administra
 * banca; a banca de outra pessoa responde como se não existisse; e desligar o modo retirou a banca
 * da vitrine sem apagar nada — religar devolve tudo como estava.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class TalentBankRecruiterAccessTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void oRecrutadorAtivoCriaEAcessaOCeuBanco() throws Exception {
		AuthSession recruiter = TalentBankTestSupport.recruiter(mockMvc, objectMapper, "tb_recrutador_ativo");

		MvcResult created = TalentBankTestSupport.createBank(mockMvc, objectMapper, recruiter, "Time de produto",
			List.of("PROFILE", "SKILLS"));
		String bankId = TalentBankTestSupport.idOf(created, objectMapper);

		mockMvc.perform(get("/talent-banks/owned").header("X-Session-Token", recruiter.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(bankId))
			.andExpect(jsonPath("$.title").value("Time de produto"))
			.andExpect(jsonPath("$.discoverable").value(false));

		// Um banco por recrutador: o segundo pedido é recusado.
		mockMvc.perform(post("/talent-banks")
				.header("X-Session-Token", recruiter.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of(
					"title", "Outro banco",
					"purpose", "Finalidade suficiente para passar na validacao do banco.",
					"handlingConditions", "Condicoes suficientes para passar na validacao do banco.",
					"requestedFields", List.of("PROFILE")))))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("TALENT_BANK_EXISTS"));
	}

	@Test
	void quemNaoERecrutadorNaoTemAcessoAoBancoDeTalentos() throws Exception {
		var auth = new TestAuthHelper(mockMvc, objectMapper);
		AuthSession common = auth.authenticate("tb_usuario_comum");

		mockMvc.perform(get("/talent-banks/owned").header("X-Session-Token", common.token()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("RECRUITER_REQUIRED"));

		mockMvc.perform(post("/talent-banks")
				.header("X-Session-Token", common.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of(
					"title", "Banco sem modo",
					"purpose", "Finalidade suficiente para passar na validacao do banco.",
					"handlingConditions", "Condicoes suficientes para passar na validacao do banco.",
					"requestedFields", List.of("PROFILE")))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("RECRUITER_REQUIRED"));

		// E a vitrine continua sendo de quem tem sessao: sem ela, nem a busca abre.
		mockMvc.perform(get("/talent-banks"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void desligarOModoRecrutadorSuspendeOAcessoSemApagarOBanco() throws Exception {
		AuthSession recruiter = TalentBankTestSupport.recruiter(mockMvc, objectMapper, "tb_recrutador_desligado");
		MvcResult created = TalentBankTestSupport.createBank(mockMvc, objectMapper, recruiter, "Banca suspensa",
			List.of("PROFILE"));
		String bankId = TalentBankTestSupport.idOf(created, objectMapper);
		publish(recruiter, bankId, true);

		TalentBankTestSupport.setRecruiter(mockMvc, recruiter, false, "recruiter-off-tb_desligado");

		mockMvc.perform(get("/talent-banks/owned").header("X-Session-Token", recruiter.token()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("RECRUITER_REQUIRED"));

		mockMvc.perform(get("/talent-banks/" + bankId + "/candidates").header("X-Session-Token", recruiter.token()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("RECRUITER_REQUIRED"));

		// A banca sai da vitrine — e a página dela deixa de existir para quem procura.
		mockMvc.perform(get("/talent-banks").header("X-Session-Token", recruiter.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.id == '" + bankId + "')]").isEmpty());

		mockMvc.perform(get("/talent-banks/" + bankId).header("X-Session-Token", recruiter.token()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("TALENT_BANK_NOT_FOUND"));
	}

	@Test
	void religarOModoRecrutadorDevolveOBancoComoEstava() throws Exception {
		AuthSession recruiter = TalentBankTestSupport.recruiter(mockMvc, objectMapper, "tb_recrutador_religado");
		MvcResult created = TalentBankTestSupport.createBank(mockMvc, objectMapper, recruiter, "Banca religada",
			List.of("PROFILE", "RESUME"));
		String bankId = TalentBankTestSupport.idOf(created, objectMapper);
		publish(recruiter, bankId, true);

		TalentBankTestSupport.setRecruiter(mockMvc, recruiter, false, "recruiter-off-tb_religado");
		TalentBankTestSupport.setRecruiter(mockMvc, recruiter, true, "recruiter-on-tb_religado");

		mockMvc.perform(get("/talent-banks/owned").header("X-Session-Token", recruiter.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(bankId))
			.andExpect(jsonPath("$.discoverable").value(true))
			.andExpect(jsonPath("$.requestedFields").isArray());

		mockMvc.perform(get("/talent-banks").header("X-Session-Token", recruiter.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.id == '" + bankId + "')]").isNotEmpty());
	}

	@Test
	void umRecrutadorNaoAcessaABancaDeOutro() throws Exception {
		AuthSession dono = TalentBankTestSupport.recruiter(mockMvc, objectMapper, "tb_dono_da_banca");
		MvcResult created = TalentBankTestSupport.createBank(mockMvc, objectMapper, dono, "Banca do dono",
			List.of("PROFILE"));
		String bankId = TalentBankTestSupport.idOf(created, objectMapper);
		publish(dono, bankId, true);

		AuthSession outro = TalentBankTestSupport.recruiter(mockMvc, objectMapper, "tb_outro_recrutador");

		// A banca de outro é a mesma resposta de uma banca que não existe.
		mockMvc.perform(get("/talent-banks/" + bankId + "/candidates").header("X-Session-Token", outro.token()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("TALENT_BANK_NOT_FOUND"));

		mockMvc.perform(patch("/talent-banks/" + bankId)
				.header("X-Session-Token", outro.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of(
					"title", "Banca sequestrada",
					"purpose", "Finalidade suficiente para passar na validacao do banco.",
					"handlingConditions", "Condicoes suficientes para passar na validacao do banco.",
					"requestedFields", List.of("PROFILE")))))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("TALENT_BANK_NOT_FOUND"));

		mockMvc.perform(patch("/talent-banks/" + bankId + "/visibility")
				.header("X-Session-Token", outro.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"discoverable\":false}"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("TALENT_BANK_NOT_FOUND"));
	}

	@Test
	void bancaNaoPublicadaNaoApareceNaVitrine() throws Exception {
		AuthSession recruiter = TalentBankTestSupport.recruiter(mockMvc, objectMapper, "tb_recrutador_reservado");
		MvcResult created = TalentBankTestSupport.createBank(mockMvc, objectMapper, recruiter, "Banca reservada",
			List.of("PROFILE"));
		String bankId = TalentBankTestSupport.idOf(created, objectMapper);

		mockMvc.perform(get("/talent-banks").header("X-Session-Token", recruiter.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.id == '" + bankId + "')]").isEmpty());

		mockMvc.perform(get("/talent-banks/" + bankId).header("X-Session-Token", recruiter.token()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("TALENT_BANK_NOT_FOUND"));
	}

	@Test
	void aVitrineMostraORecrutadorEOQueABancaPede() throws Exception {
		AuthSession recruiter = TalentBankTestSupport.recruiter(mockMvc, objectMapper, "tb_recrutador_vitrine");
		MvcResult created = TalentBankTestSupport.createBank(mockMvc, objectMapper, recruiter, "Banca da vitrine",
			List.of("PROFILE", "SKILLS"));
		String bankId = TalentBankTestSupport.idOf(created, objectMapper);
		publish(recruiter, bankId, true);

		mockMvc.perform(get("/talent-banks").header("X-Session-Token", recruiter.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.id == '" + bankId + "')]").isNotEmpty())
			.andExpect(jsonPath("$.items[?(@.id == '" + bankId + "')].recruiter.userId").value(recruiter.userId()))
			.andExpect(jsonPath("$.items[?(@.id == '" + bankId + "')].requestedFields").isArray());

		mockMvc.perform(get("/talent-banks/" + bankId).header("X-Session-Token", recruiter.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.purpose").isNotEmpty())
			.andExpect(jsonPath("$.handlingConditions").isNotEmpty())
			.andExpect(jsonPath("$.recruiter.userId").value(recruiter.userId()));

		// A vitrine mostra a banca e o recrutador — nunca quem se inscreveu nela.
		mockMvc.perform(get("/talent-banks").param("search", "vitrine")
				.header("X-Session-Token", recruiter.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.id == '" + bankId + "')].candidates").doesNotExist());
	}

	private void publish(AuthSession recruiter, String bankId, boolean visible) throws Exception {
		mockMvc.perform(patch("/talent-banks/" + bankId + "/visibility")
				.header("X-Session-Token", recruiter.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"discoverable\":" + visible + "}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.discoverable").value(visible));
	}
}
