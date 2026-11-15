package com.socialcoisa.rede.messaging.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class MessagingReplyContractTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private org.springframework.jdbc.core.JdbcTemplate banco;

	private TestAuthHelper auth;

	@BeforeEach
	void preparar() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	@Test
	void respostaCifradaPreservaEnvelopeCompletoForaDaPagina() throws Exception {
		var remetente = auth.authenticate("reply_cifrada_a");
		var leitor = auth.authenticate("reply_cifrada_b");
		String conversa = criarConversa(remetente, leitor.userId());
		String ciphertext = "QUJD".repeat(2000);
		String iv = "AAAAAAAAAAAAAAAA";
		String chaves = objectMapper.writeValueAsString(Map.of(
			remetente.userId(), "chave-cifrada-ficticia-a", leitor.userId(), "chave-cifrada-ficticia-b"));
		JsonNode original = enviar(remetente, conversa, Map.of("text", ciphertext, "e2e", true,
			"iv", iv, "encryptedKeys", chaves, "mediaIds", List.of("media_reply_ficticia"), "mediaSpoiler", true));
		JsonNode resposta = enviar(leitor, conversa, Map.of("text", "Resposta de teste", "replyToId", original.path("id").asString()));

		JsonNode pagina = mensagens(leitor, conversa, 1);
		assertThat(pagina.path("items").size()).isEqualTo(1);
		assertThat(pagina.path("items").get(0).path("id").asString()).isEqualTo(resposta.path("id").asString());
		JsonNode citada = pagina.path("items").get(0).path("replyTo");
		assertThat(citada.path("id").asString()).isEqualTo(original.path("id").asString());
		assertThat(citada.path("from").asString()).isEqualTo(remetente.userId());
		assertThat(citada.path("e2e").asBoolean()).isTrue();
		assertThat(citada.path("text").asString()).isEqualTo(ciphertext);
		assertThat(citada.path("iv").asString()).isEqualTo(iv);
		assertThat(citada.path("encryptedKeys").isString()).isTrue();
		assertThat(citada.path("encryptedKeys").asString()).isEqualTo(chaves);
		assertThat(citada.path("mediaIds").get(0).asString()).isEqualTo("media_reply_ficticia");
		assertThat(citada.path("mediaSpoiler").asBoolean()).isTrue();
	}

	@Test
	void mensagemApagadaParaMimNaoReapareceNaCitacao() throws Exception {
		var remetente = auth.authenticate("reply_oculta_a");
		var leitor = auth.authenticate("reply_oculta_b");
		String conversa = criarConversa(remetente, leitor.userId());
		JsonNode original = enviar(remetente, conversa, Map.of("text", "QUJD".repeat(30), "e2e", true,
			"iv", "AAAAAAAAAAAAAAAA", "encryptedKeys", "{\"ficticia\":\"chave-cifrada-ficticia\"}"));
		String originalId = original.path("id").asString();
		enviar(remetente, conversa, Map.of("text", "Resposta ainda visivel", "replyToId", originalId));
		mockMvc.perform(post("/conversations/{id}/messages/hide", conversa)
			.header("X-Session-Token", leitor.token()).header("Idempotency-Key", "reply-hide-" + originalId)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("ids", List.of(originalId)))))
			.andExpect(status().isOk());

		JsonNode itens = mensagens(leitor, conversa, 50).path("items");
		assertThat(itens.size()).isEqualTo(1);
		assertThat(itens.get(0).path("replyToId").asString()).isEqualTo(originalId);
		assertThat(itens.get(0).path("replyTo").isNull()).isTrue();
		assertThat(mensagens(remetente, conversa, 1).path("items").get(0).path("replyTo").path("id").asString())
			.isEqualTo(originalId);
	}

	@Test
	void referenciaArmazenadaDeOutraConversaNaoExpoeMensagem() throws Exception {
		var autor = auth.authenticate("reply_escopo_a");
		var leitor = auth.authenticate("reply_escopo_b");
		var terceiro = auth.authenticate("reply_escopo_c");
		String conversa = criarConversa(autor, leitor.userId());
		String outraConversa = criarConversa(autor, terceiro.userId());
		String alheiaId = enviar(terceiro, outraConversa, Map.of("text", "Conteudo ficticio de outra conversa"))
			.path("id").asString();
		String respostaId = enviar(autor, conversa, Map.of("text", "Resposta com referencia armazenada"))
			.path("id").asString();
		// Simula referencia legada inconsistente sem afrouxar a validacao do envio HTTP.
		assertThat(banco.update("update messages set reply_to_id = ? where id = ?", alheiaId, respostaId)).isEqualTo(1);

		JsonNode resposta = mensagens(leitor, conversa, 1).path("items").get(0);
		assertThat(resposta.path("id").asString()).isEqualTo(respostaId);
		assertThat(resposta.path("replyToId").asString()).isEqualTo(alheiaId);
		assertThat(resposta.path("replyTo").isNull()).isTrue();
	}

	@Test
	void textoLegadoMantemResumoDeCemCaracteres() throws Exception {
		var autor = auth.authenticate("reply_texto_a");
		var leitor = auth.authenticate("reply_texto_b");
		String conversa = criarConversa(autor, leitor.userId());
		String originalId = enviar(autor, conversa, Map.of("text", "L".repeat(101))).path("id").asString();
		enviar(leitor, conversa, Map.of("text", "Resposta ao texto legado", "replyToId", originalId));

		JsonNode citada = mensagens(leitor, conversa, 1).path("items").get(0).path("replyTo");
		assertThat(citada.path("text").asString()).isEqualTo("L".repeat(97) + "...");
		assertThat(citada.path("e2e").asBoolean()).isFalse();
		assertThat(citada.path("iv").isNull()).isTrue();
		assertThat(citada.path("encryptedKeys").isNull()).isTrue();
		assertThat(citada.path("mediaIds").isArray()).isTrue();
		assertThat(citada.path("mediaIds").size()).isZero();
		assertThat(citada.path("mediaSpoiler").asBoolean()).isFalse();
	}

	@Test
	void textoNoLimiteDoResumoNaoRecebeReticencias() throws Exception {
		var autor = auth.authenticate("reply_limite_a");
		var leitor = auth.authenticate("reply_limite_b");
		String conversa = criarConversa(autor, leitor.userId());
		String texto = "L".repeat(100);
		String originalId = enviar(autor, conversa, Map.of("text", texto)).path("id").asString();
		enviar(leitor, conversa, Map.of("text", "Resposta ao limite", "replyToId", originalId));

		assertThat(mensagens(leitor, conversa, 1).path("items").get(0).path("replyTo").path("text").asString())
			.isEqualTo(texto);
	}

	@Test
	void cifragemLegadaSemChavesPorMensagemPreservaIv() throws Exception {
		var autor = auth.authenticate("reply_e2e_legado_a");
		var leitor = auth.authenticate("reply_e2e_legado_b");
		String conversa = criarConversa(autor, leitor.userId());
		String ciphertext = "QUJD".repeat(30);
		String originalId = enviar(autor, conversa, Map.of("text", ciphertext, "e2e", true, "iv", "AAAAAAAAAAAAAAAA"))
			.path("id").asString();
		enviar(leitor, conversa, Map.of("text", "Resposta a cifragem legada", "replyToId", originalId));

		JsonNode citada = mensagens(leitor, conversa, 1).path("items").get(0).path("replyTo");
		assertThat(citada.path("text").asString()).isEqualTo(ciphertext);
		assertThat(citada.path("iv").asString()).isEqualTo("AAAAAAAAAAAAAAAA");
		assertThat(citada.path("encryptedKeys").isNull()).isTrue();
	}

	@Test
	void citacaoSoComMidiaPreservaResumoEVazio() throws Exception {
		var autor = auth.authenticate("reply_midia_a");
		var leitor = auth.authenticate("reply_midia_b");
		String conversa = criarConversa(autor, leitor.userId());
		String originalId = enviar(autor, conversa, Map.of("mediaIds", List.of("media_reply_legada_ficticia")))
			.path("id").asString();
		enviar(leitor, conversa, Map.of("text", "Resposta a midia", "replyToId", originalId));

		JsonNode citada = mensagens(leitor, conversa, 1).path("items").get(0).path("replyTo");
		assertThat(citada.path("text").asString()).isEmpty();
		assertThat(citada.path("mediaIds").get(0).asString()).isEqualTo("media_reply_legada_ficticia");
	}

	@Test
	void citacaoAusenteNaoImpedeLeituraDaResposta() throws Exception {
		var autor = auth.authenticate("reply_ausente_a");
		var leitor = auth.authenticate("reply_ausente_b");
		String conversa = criarConversa(autor, leitor.userId());
		String respostaId = enviar(autor, conversa, Map.of("text", "Resposta sem citacao"))
			.path("id").asString();
		assertThat(mensagens(leitor, conversa, 1).path("items").get(0).path("replyTo").isNull()).isTrue();
		assertThat(banco.update("update messages set reply_to_id = ? where id = ?", "message_reply_inexistente", respostaId))
			.isEqualTo(1);

		JsonNode resposta = mensagens(leitor, conversa, 1).path("items").get(0);
		assertThat(resposta.path("id").asString()).isEqualTo(respostaId);
		assertThat(resposta.path("replyTo").isNull()).isTrue();
	}

	@Test
	void leituraExigeSessaoEParticipacao() throws Exception {
		var autor = auth.authenticate("reply_acesso_a");
		var leitor = auth.authenticate("reply_acesso_b");
		var terceiro = auth.authenticate("reply_acesso_c");
		String conversa = criarConversa(autor, leitor.userId());
		String originalId = enviar(autor, conversa, Map.of("text", "Citacao privada ficticia"))
			.path("id").asString();
		enviar(leitor, conversa, Map.of("text", "Resposta privada ficticia", "replyToId", originalId));

		mockMvc.perform(get("/conversations/{id}/messages", conversa)).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/conversations/{id}/messages", conversa).header("X-Session-Token", terceiro.token()))
			.andExpect(status().isForbidden());
	}

	private String criarConversa(TestAuthHelper.AuthSession autor, String destinatario) throws Exception {
		return escrever(autor, "/conversations", Map.of("kind", "DIRECT", "participantIds", List.of(destinatario)))
			.path("id").asString();
	}

	private JsonNode enviar(TestAuthHelper.AuthSession autor, String conversa, Map<String, Object> corpo) throws Exception {
		return escrever(autor, "/conversations/" + conversa + "/messages", corpo);
	}

	private JsonNode escrever(TestAuthHelper.AuthSession autor, String caminho, Map<String, Object> corpo) throws Exception {
		var resultado = mockMvc.perform(post(caminho)
			.header("X-Session-Token", autor.token())
			.header("Idempotency-Key", "reply-" + UUID.randomUUID())
			.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(corpo)))
			.andExpect(status().isCreated()).andReturn();
		return objectMapper.readTree(resultado.getResponse().getContentAsString());
	}

	private JsonNode mensagens(TestAuthHelper.AuthSession leitor, String conversa, int limite) throws Exception {
		var resultado = mockMvc.perform(get("/conversations/{id}/messages", conversa)
			.header("X-Session-Token", leitor.token()).param("limit", Integer.toString(limite)))
			.andExpect(status().isOk()).andReturn();
		return objectMapper.readTree(resultado.getResponse().getContentAsString());
	}
}
