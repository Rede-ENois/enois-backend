package com.socialcoisa.rede.cosmetics.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
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

	@Test
	void animatedFrameAppearsOnPostAndComment() throws Exception {
		var buyer = auth("moldura_feed_buyer");
		mockMvc.perform(get("/carteira").header("X-Session-Token", buyer.token())).andExpect(status().isOk());
		mockMvc.perform(post("/loja/moldura-aurora/comprar").header("X-Session-Token", buyer.token())).andExpect(status().isOk());
		mockMvc.perform(post("/loja/moldura-aurora/equipar").header("X-Session-Token", buyer.token())).andExpect(status().isOk());
		MvcResult published = mockMvc.perform(post("/feed/posts")
			.header("X-Session-Token", buyer.token()).header("Idempotency-Key", "frame-post")
			.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of(
				"type", "INSIGHT", "content", "Uma publicacao com moldura", "tags", List.of(), "mediaIds", List.of()))))
			.andExpect(status().isCreated()).andExpect(jsonPath("$.authorAvatarFrame").value("moldura-aurora")).andReturn();
		String postId = objectMapper.readTree(published.getResponse().getContentAsString()).get("id").asText();
		mockMvc.perform(post("/feed/posts/" + postId + "/comments")
			.header("X-Session-Token", buyer.token()).header("Idempotency-Key", "frame-comment")
			.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of(
				"content", "Comentario com moldura", "mediaIds", List.of(), "mentionedUserIds", List.of()))))
			.andExpect(status().isCreated()).andExpect(jsonPath("$.authorAvatarFrame").value("moldura-aurora"));
		mockMvc.perform(get("/feed/posts/" + postId + "/comments").header("X-Session-Token", buyer.token()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.items[0].authorAvatarFrame").value("moldura-aurora"));
	}

	private TestAuthHelper.AuthSession auth(String name) throws Exception {
		return new TestAuthHelper(mockMvc, objectMapper).authenticate(name);
	}
}
