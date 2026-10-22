package com.socialcoisa.rede.cosmetics.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import java.util.List;
import java.util.Map;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.ObjectMapper;

import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestDatabase;

/**
 * A loja de cosméticos (plano C3): comprar moldura de avatar com faíscas (débito direto,
 * sem escrow — item da plataforma), equipar, e o catálogo marca o que já é seu.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CosmeticsFlowTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void catalogBuyEquipAndDoubleBuyRejected() throws Exception {
		var buyer = auth("loja_buyer");

		// Abre a carteira (genesis: 100 faíscas = 100.000 mf + presença 5.000).
		mockMvc.perform(get("/carteira").header("X-Session-Token", buyer.token())).andExpect(status().isOk());

		// O catálogo é público e tem as molduras nativas.
		MvcResult catalog = mockMvc.perform(get("/loja"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.id == 'moldura-violeta')].priceMf").value(5_000))
			.andExpect(jsonPath("$[?(@.id == 'moldura-buraco-negro')].priceMf").value(10_000))
			.andExpect(jsonPath("$[?(@.id == 'moldura-nebulosa')].priceMf").value(10_000))
                        .andExpect(jsonPath("$[?(@.id == 'moldura-supernova')].priceMf").value(10_000))
                        .andExpect(jsonPath("$[?(@.id == 'moldura-dragao')]").isEmpty())
			.andReturn();

		// A compra direta: 5.000 mf saem (105.000 -> 100.000).
		mockMvc.perform(post("/loja/moldura-violeta/comprar").header("X-Session-Token", buyer.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.owned").value(true));

		// Comprar de novo recusa (já é sua).
		mockMvc.perform(post("/loja/moldura-violeta/comprar").header("X-Session-Token", buyer.token()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("ALREADY_OWNED"));

		// Equipa: o catálogo passa a marcar equipped=true.
		mockMvc.perform(post("/loja/moldura-violeta/equipar").header("X-Session-Token", buyer.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.equipped").value(true));
		mockMvc.perform(get("/loja").header("X-Session-Token", buyer.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.id == 'moldura-violeta')].owned").value(true))
			.andExpect(jsonPath("$[?(@.id == 'moldura-violeta')].equipped").value(true));

		// Equipar o que não tem recusa.
		mockMvc.perform(post("/loja/moldura-coral/equipar").header("X-Session-Token", buyer.token()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NOT_OWNED"));

		// Desequipar (none) vale sem posse.
		mockMvc.perform(post("/loja/none/equipar").header("X-Session-Token", buyer.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.equipped").value(false));
	}

        @ParameterizedTest
        @ValueSource(strings = { "moldura-aurora", "moldura-tempestade", "moldura-inferno",
                "moldura-buraco-negro", "moldura-orbita", "moldura-glacial", "moldura-toxica",
                "moldura-nebulosa", "moldura-supernova", "moldura-prisma", "moldura-portal",
                "moldura-holograma" })
        void animatedFrameAppearsOnPostAndComment(String frame) throws Exception {
                var buyer = auth("frame_" + frame.substring("moldura-".length()).replace('-', '_'));
                mockMvc.perform(get("/carteira").header("X-Session-Token", buyer.token())).andExpect(status().isOk());
                mockMvc.perform(get("/loja"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$[?(@.id == '" + frame + "')].priceMf")
                                .value("moldura-aurora".equals(frame) ? 5_000 : 10_000));
                mockMvc.perform(post("/loja/" + frame + "/comprar").header("X-Session-Token", buyer.token()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.owned").value(true));
                mockMvc.perform(post("/loja/" + frame + "/equipar").header("X-Session-Token", buyer.token())).andExpect(status().isOk());
		MvcResult published = mockMvc.perform(post("/feed/posts")
			.header("X-Session-Token", buyer.token()).header("Idempotency-Key", "frame-post")
			.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of(
				"type", "INSIGHT", "content", "Uma publicacao com moldura", "tags", List.of(), "mediaIds", List.of()))))
                        .andExpect(status().isCreated()).andExpect(jsonPath("$.authorAvatarFrame").value(frame)).andReturn();
		String postId = objectMapper.readTree(published.getResponse().getContentAsString()).get("id").asText();
		mockMvc.perform(post("/feed/posts/" + postId + "/comments")
			.header("X-Session-Token", buyer.token()).header("Idempotency-Key", "frame-comment")
			.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of(
				"content", "Comentario com moldura", "mediaIds", List.of(), "mentionedUserIds", List.of()))))
                        .andExpect(status().isCreated()).andExpect(jsonPath("$.authorAvatarFrame").value(frame));
		mockMvc.perform(get("/feed/posts/" + postId + "/comments").header("X-Session-Token", buyer.token()))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].authorAvatarFrame").value(frame));
	}

	private TestAuthHelper.AuthSession auth(String name) throws Exception {
		String email = name + "@test.com";
		mockMvc.perform(post("/auth/signup")
				.header("Idempotency-Key", "cosmetics-signup-" + name)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
						"name", name, "email", email, "password", "Test1234!",
						"acceptedTerms", true, "birthDate", "1990-05-10"))))
			.andExpect(status().isCreated());

		// A upstream cria a conta apenas na confirmacao: usamos o mesmo fluxo no teste da loja.
		String verificationToken = "cosmetics-verify-" + name;
		TestDatabase.setPendingSignupToken(email, verificationToken);
		MvcResult verified = mockMvc.perform(post("/auth/email/verify")
				.header("Idempotency-Key", "cosmetics-verify-" + name)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("token", verificationToken))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.user.emailVerified").value(true))
			.andReturn();
		var session = objectMapper.readTree(verified.getResponse().getContentAsString());
		return new TestAuthHelper.AuthSession(session.get("token").asText(),
				session.get("refreshToken").asText(), TestDatabase.userIdByEmail(email).orElseThrow());
	}
}
