package com.socialcoisa.rede.talentbank.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestAuthHelper.AuthSession;

import tools.jackson.databind.ObjectMapper;

/**
 * Atalhos dos cenários do banco de talentos.
 *
 * <p>Recrutador aqui é quem liga o modo no perfil — o mesmo caminho que o app usa — e banca é a
 * que o recrutador cria pelo endpoint dele. Nada de estado plantado direto no banco: os testes
 * passam pelas rotas que o produto usa.</p>
 */
final class TalentBankTestSupport {

	private TalentBankTestSupport() {
	}

	/** Cria a conta e liga o modo recrutador pelo perfil. */
	static AuthSession recruiter(MockMvc mockMvc, ObjectMapper mapper, String userId) throws Exception {
		AuthSession session = new TestAuthHelper(mockMvc, mapper).authenticate(userId);
		setRecruiter(mockMvc, session, true, "recruiter-on-" + userId);
		return session;
	}

	/** Liga ou desliga o modo recrutador da conta. */
	static void setRecruiter(MockMvc mockMvc, AuthSession session, boolean active, String key) throws Exception {
		mockMvc.perform(patch("/me/profile")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"isRecruiter\":" + active + "}"))
			.andExpect(status().isOk());
	}

	/** A banca do recrutador, com os campos que ele pede. */
	static MvcResult createBank(MockMvc mockMvc, ObjectMapper mapper, AuthSession recruiter, String title,
			List<String> requestedFields) throws Exception {
		return mockMvc.perform(post("/talent-banks")
				.header("X-Session-Token", recruiter.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(mapper.writeValueAsString(Map.of(
					"title", title,
					"purpose", "Encontrar pessoas para o time de produto e tecnologia da empresa.",
					"description", "Banca aberta para profissionais da area de tecnologia.",
					"handlingConditions", "Os dados ficam com a empresa por 180 dias e saem a pedido da pessoa.",
					"focusArea", "Tecnologia",
					"desiredSkills", List.of("Java", "Produto"),
					"requestedFields", requestedFields))))
			.andExpect(status().isOk())
			.andReturn();
	}

	static String idOf(MvcResult result, ObjectMapper mapper) throws Exception {
		return mapper.readTree(result.getResponse().getContentAsString()).path("id").asText();
	}
}
