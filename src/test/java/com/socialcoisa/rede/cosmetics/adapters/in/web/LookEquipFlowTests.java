package com.socialcoisa.rede.cosmetics.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Vestir e tirar um look pronto pelo card da loja: o clique veste o que já é seu, o clique de novo
 * tira. Nenhum dos dois mexe em quem não tem todas as peças.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(SeasonalTestClock.class)
class LookEquipFlowTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private SeasonalTestClock.MovableClock clock;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		clock.set(SeasonalTestClock.DUEL_DAY);
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	private TestAuthHelper.AuthSession wallet(String name) throws Exception {
		var user = auth.authenticate(name);
		// Opens the wallet: 100 faíscas of genesis + 5 of presence = 105.000 mf.
		mockMvc.perform(get("/carteira").header("X-Session-Token", user.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.balance").value(105_000));
		return user;
	}

	private JsonNode me(TestAuthHelper.AuthSession user) throws Exception {
		MvcResult result = mockMvc.perform(get("/me").header("X-Session-Token", user.token()))
			.andExpect(status().isOk()).andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}

	/** One look of the collection list: the first with this title (`looks` carry the viewer state). */
	private JsonNode look(TestAuthHelper.AuthSession user, String title) throws Exception {
		MvcResult result = mockMvc.perform(get("/loja/colecoes").header("X-Session-Token", user.token()))
			.andExpect(status().isOk()).andReturn();
		for (JsonNode collection : objectMapper.readTree(result.getResponse().getContentAsString())) {
			for (JsonNode look : collection.get("looks")) {
				if (title.equals(look.get("title").asText())) {
					return look;
				}
			}
		}
		throw new AssertionError("look fora da loja: " + title);
	}

	private void wear(TestAuthHelper.AuthSession user, String lookId) throws Exception {
		mockMvc.perform(post("/loja/looks/" + lookId + "/equipar").header("X-Session-Token", user.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(lookId))
			.andExpect(jsonPath("$.equipped").value(true));
	}

	private void takeOff(TestAuthHelper.AuthSession user, String lookId) throws Exception {
		mockMvc.perform(post("/loja/looks/" + lookId + "/desequipar").header("X-Session-Token", user.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(lookId))
			.andExpect(jsonPath("$.equipped").value(false));
	}

	@Test
	void aLookIsMarkedOwnedAndEquippedOnlyForWhoHasEveryPiece() throws Exception {
		var owner = wallet("look_equip_flags");

		// Antes de comprar: não é seu, não está vestido.
		JsonNode before = look(owner, "Papel");
		assertThat(before.get("owned").asBoolean()).isFalse();
		assertThat(before.get("equipped").asBoolean()).isFalse();

		mockMvc.perform(post("/loja/looks/look-papel/comprar").header("X-Session-Token", owner.token()))
			.andExpect(status().isOk());

		// Comprado, o look já vem vestido — e o card diz isso.
		JsonNode after = look(owner, "Papel");
		assertThat(after.get("owned").asBoolean()).isTrue();
		assertThat(after.get("equipped").asBoolean()).isTrue();

		// O mesmo look para outro olhar: owned/equipped são de quem pergunta, não do catálogo.
		JsonNode other = look(wallet("look_equip_other"), "Papel");
		assertThat(other.get("owned").asBoolean()).isFalse();
		assertThat(other.get("equipped").asBoolean()).isFalse();

		// Sem sessão não há o que marcar: os campos não vão.
		MvcResult anonymous = mockMvc.perform(get("/loja/colecoes")).andExpect(status().isOk()).andReturn();
		JsonNode guestLook = objectMapper.readTree(anonymous.getResponse().getContentAsString())
			.get(0).get("looks").get(0);
		assertThat(guestLook.has("owned")).isFalse();
		assertThat(guestLook.has("equipped")).isFalse();
	}

	@Test
	void clickingAnOwnedLookTakesItOffAndPutsItBack() throws Exception {
		var owner = wallet("look_equip_toggle");
		mockMvc.perform(post("/loja/looks/look-manha-de-domingo/comprar").header("X-Session-Token", owner.token()))
			.andExpect(status().isOk());

		// Primeiro clique no card (já vestido pela compra): tira.
		takeOff(owner, "look-manha-de-domingo");
		JsonNode bare = me(owner);
		assertThat(bare.get("cardTheme").isNull()).isTrue();
		assertThat(bare.get("avatarFrame").isNull()).isTrue();
		assertThat(bare.get("nameTheme").isNull()).isTrue();
		assertThat(bare.get("profileEffect").isNull()).isTrue();
		assertThat(bare.get("nameplate").isNull()).isTrue();
		assertThat(bare.get("statusText").isNull()).isTrue();
		assertThat(bare.get("statusIcon").isNull()).isTrue();
		assertThat(objectMapper.convertValue(bare.get("badges"), List.class)).isEmpty();
		JsonNode off = look(owner, "Manhã de domingo");
		assertThat(off.get("owned").asBoolean()).isTrue();
		assertThat(off.get("equipped").asBoolean()).isFalse();

		// Segundo clique: veste de novo, com todas as peças e o status do look.
		wear(owner, "look-manha-de-domingo");
		JsonNode worn = me(owner);
		assertThat(worn.get("cardTheme").asText()).isEqualTo("tema-papel");
		assertThat(worn.get("avatarFrame").asText()).isEqualTo("moldura-coral");
		assertThat(worn.get("nameTheme").asText()).isEqualTo("nome-papel");
		assertThat(worn.get("profileEffect").asText()).isEqualTo("efeito-estrelas");
		assertThat(worn.get("nameplate").asText()).isEqualTo("placa-papel");
		assertThat(worn.get("statusText").asText()).isEqualTo("Café, jornal e nada marcado");
		assertThat(worn.get("statusIcon").asText()).isEqualTo("sol");
		assertThat(objectMapper.convertValue(worn.get("badges"), List.class))
			.containsExactly("emblema-papel", "emblema-estrela-calma");
		assertThat(look(owner, "Manhã de domingo").get("equipped").asBoolean()).isTrue();
	}

	@Test
	void wearingTheSameLookTwiceKeepsTheProfileAsItIs() throws Exception {
		var owner = wallet("look_equip_idempotent");
		mockMvc.perform(post("/loja/looks/look-papel/comprar").header("X-Session-Token", owner.token()))
			.andExpect(status().isOk());
		JsonNode first = me(owner);

		wear(owner, "look-papel");
		wear(owner, "look-papel");

		assertThat(me(owner)).isEqualTo(first);
	}

	@Test
	void takingOffALookLeavesWhatWasChosenAfterItAlone() throws Exception {
		var owner = wallet("look_equip_after");
		mockMvc.perform(post("/loja/looks/look-papel/comprar").header("X-Session-Token", owner.token()))
			.andExpect(status().isOk());

		// Trocou a moldura (o look "Papel" veste a moldura âmbar) e escreveu outro recado depois.
		mockMvc.perform(post("/loja/moldura-violeta/comprar").header("X-Session-Token", owner.token()))
			.andExpect(status().isOk());
		mockMvc.perform(post("/loja/moldura-violeta/equipar").header("X-Session-Token", owner.token()))
			.andExpect(status().isOk());
		mockMvc.perform(put("/me/status").header("X-Session-Token", owner.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("text", "Recado novo", "icon", "cafe"))))
			.andExpect(status().isOk());

		takeOff(owner, "look-papel");

		JsonNode me = me(owner);
		// O look saiu dos espaços que ainda eram dele...
		assertThat(me.get("cardTheme").isNull()).isTrue();
		assertThat(me.get("nameTheme").isNull()).isTrue();
		assertThat(me.get("nameplate").isNull()).isTrue();
		assertThat(objectMapper.convertValue(me.get("badges"), List.class)).isEmpty();
		// ...e o que veio depois continua onde está.
		assertThat(me.get("avatarFrame").asText()).isEqualTo("moldura-violeta");
		assertThat(me.get("statusText").asText()).isEqualTo("Recado novo");
		assertThat(me.get("statusIcon").asText()).isEqualTo("cafe");
	}

	@Test
	void aLookWithAPieceMissingIsNotWornAndCostsNothing() throws Exception {
		var buyer = wallet("look_equip_partial");
		// Uma peça do look do Saci comprada não veste o look: falta o resto.
		mockMvc.perform(post("/loja/moldura-saci-completo/comprar").header("X-Session-Token", buyer.token()))
			.andExpect(status().isOk());

		mockMvc.perform(post("/loja/looks/look-saci/equipar").header("X-Session-Token", buyer.token()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NOT_OWNED"));

		// Nada do look foi vestido, e só a peça comprada saiu da carteira.
		JsonNode me = me(buyer);
		assertThat(me.get("cardTheme").isNull()).isTrue();
		assertThat(me.get("avatarFrame").isNull()).isTrue();
		assertThat(look(buyer, "Saci-pererê").get("owned").asBoolean()).isFalse();
		MvcResult balance = mockMvc.perform(get("/carteira").header("X-Session-Token", buyer.token()))
			.andExpect(status().isOk()).andReturn();
		assertThat(objectMapper.readTree(balance.getResponse().getContentAsString()).get("balance").asLong())
			.isEqualTo(105_000 - 8_000);
	}

	@Test
	void aLookOutsideTheSeasonCanStillBeWornByItsOwner() throws Exception {
		var owner = wallet("look_equip_out_of_season");
		// Compra o look do Saci na temporada...
		mockMvc.perform(post("/loja/looks/look-saci/comprar").header("X-Session-Token", owner.token()))
			.andExpect(status().isOk());
		takeOff(owner, "look-saci");

		// ...e a temporada passa: o look não está mais na loja, mas as peças compradas continuam.
		// A sessão aberta na temporada vence junto com ela, então entra de novo depois da virada.
		// 2026-11-04T02:30Z é 2026-11-03 23:30 em São Paulo: um dia depois do fim da janela (11-02).
		clock.set(java.time.Instant.parse("2026-11-04T02:30:00Z"));
		var after = auth.authenticate("look_equip_out_of_season");
		MvcResult shop = mockMvc.perform(get("/loja/colecoes")).andExpect(status().isOk()).andReturn();
		assertThat(shop.getResponse().getContentAsString()).doesNotContain("Saci-pererê");

		wear(after, "look-saci");
		assertThat(me(after).get("cardTheme").asText()).isEqualTo("tema-saci");
	}

	@Test
	void unknownLookAndNoSessionAreRefused() throws Exception {
		mockMvc.perform(post("/loja/looks/look-nada/equipar"))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(post("/loja/looks/look-papel/equipar").header("X-Session-Token", "look-equip-invalid"))
			.andExpect(status().isUnauthorized());

		var user = wallet("look_equip_missing");
		mockMvc.perform(post("/loja/looks/look-nada/equipar").header("X-Session-Token", user.token()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("ITEM_NOT_FOUND"));
		mockMvc.perform(post("/loja/looks/look-nada/desequipar").header("X-Session-Token", user.token()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("ITEM_NOT_FOUND"));
	}

	}
