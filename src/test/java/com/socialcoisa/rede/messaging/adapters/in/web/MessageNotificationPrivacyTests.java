package com.socialcoisa.rede.messaging.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * Notificação de mensagem não leva conteúdo.
 *
 * <p>Antes ia um recorte de até 80 caracteres, que <strong>copava</strong> o texto para outra tabela
 * — e de lá para push e e-mail. Isso faz a privacidade da conversa depender de quem lê a notificação,
 * que não é o combinado: conteúdo de conversa é privado da conversa (plano de privacidade, §14).</p>
 *
 * <p>Este teste falharia antes da mudança: ele procura o texto secreto no JSON das notificações do
 * destinatário.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class MessageNotificationPrivacyTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	@Test
	void aNotificacaoDeMensagemNaoLevaOConteudo() throws Exception {
		var remetente = auth.authenticate("notif_priv_a");
		var destinatario = auth.authenticate("notif_priv_b");

		MvcResult conversa = mockMvc.perform(post("/conversations/direct")
				.header("X-Session-Token", remetente.token())
				.header("Idempotency-Key", "notif-priv-direct")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("userId", destinatario.userId()))))
			.andExpect(status().is2xxSuccessful())
			.andReturn();
		String conversationId = objectMapper.readTree(conversa.getResponse().getContentAsString())
			.get("id").asText();

		String segredo = "chave-que-nao-pode-vazar-123456";
		mockMvc.perform(post("/conversations/" + conversationId + "/messages")
				.header("X-Session-Token", remetente.token())
				.header("Idempotency-Key", "notif-priv-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("text", "a " + segredo + " vai aqui"))))
			.andExpect(status().is2xxSuccessful());

		String notificacoes = mockMvc.perform(get("/notifications")
				.header("X-Session-Token", destinatario.token()))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();

		// O aviso existe — só não carrega o texto.
		assertThat(notificacoes).contains("Nova mensagem");
		assertThat(notificacoes).doesNotContain(segredo);
	}

	/**
	 * Guarda de configuração: a varredura de conteúdo suspeito é dirigida por lista de tabelas, e
	 * conversa/mensagem não pode entrar nela. Enquanto o conteúdo das DMs fosse legível, a varredura
	 * era superfície de moderação automática — e o E2E que vem depois a remove de vez (plano §14).
	 */
	@Test
	void aVarreduraDeConteudoNaoAlcancaMensagens() throws Exception {
		String properties = java.nio.file.Files.readString(
			java.nio.file.Path.of("src/main/resources/application.properties"), java.nio.charset.StandardCharsets.UTF_8);
		String linha = properties.lines()
			.filter(l -> l.startsWith("rede.moderation.content-sweep.targets"))
			.findFirst()
			.orElseThrow();

		assertThat(linha).doesNotContain("messages");
		assertThat(linha).doesNotContain("conversations");
	}
}
