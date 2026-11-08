package com.socialcoisa.rede.network.adapters.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.shared.blocks.BlockedUsersReader;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * O conjunto de bloqueios+mutes vale a requisicao inteira: uma rodada do "Para voce" le o conjunto
 * UMA vez, e o enrich da pagina e o enrich dos reposts mesclados reusam a leitura.
 *
 * <p>Antes, cada enrich e a propria mesclagem chamavam {@code hiddenIdsFor} de novo: bloqueios em
 * dois sentidos + mutes ativos sao 3 consultas por chamada, x3 por requisicao. A prova conta as
 * chamadas reais ao adaptador que implementa {@link BlockedUsersReader} (a mesma classe e o
 * {@code UserBlockRepository} do modulo de rede, entao o mock tem de ser dela, e nao da interface)
 * durante uma unica rodada do feed.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ForYouHiddenReadsTests {

	@MockitoBean
	private UserBlockPersistenceAdapter userBlockPersistenceAdapter;

	private BlockedUsersReader blockedUsersReader() {
		return userBlockPersistenceAdapter;
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private TestAuthHelper auth;

	@Test
	void umaRodadaDoParaVoceLeOConjuntoDeBloqueiosUmaUnicaVez() throws Exception {
		when(userBlockPersistenceAdapter.hiddenIdsFor(anyString())).thenReturn(Set.of());

		var author = auth("fyou2_author");
		var reader = auth("fyou2_reader");

		// Amizade leitor->autor: sem isso a pagina do "Para voce" vem vazia e o enrich sai antes
		// de ler o conjunto — o defeito (segunda leitura) nem se manifestaria.
		mockMvc.perform(post("/network/connections/{target}", author.userId())
				.header("X-Session-Token", reader.token())
				.header("Idempotency-Key", "fyou2-req-" + reader.userId()))
			.andExpect(status().isAccepted());
		mockMvc.perform(put("/network/connections/{target}/accept", reader.userId())
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "fyou2-acc-" + author.userId()))
			.andExpect(status().isOk());

		publish(author, "Post do autor para o Para voce 2.");

		org.mockito.Mockito.clearInvocations(userBlockPersistenceAdapter);
		mockMvc.perform(get("/feed")
				.header("X-Session-Token", reader.token())
				.param("tab", "FOR_YOU"))
			.andExpect(status().isOk());

		// A prova: uma leitura do conjunto por rodada (antes eram 3: enrich da pagina,
		// enrich dos reposts e a mesclagem de reposts).
		verify(userBlockPersistenceAdapter, times(1)).hiddenIdsFor(reader.userId());
	}

	private TestAuthHelper.AuthSession auth(String name) throws Exception {
		return new TestAuthHelper(mockMvc, objectMapper).authenticate(name);
	}

	private void publish(TestAuthHelper.AuthSession session, String content) throws Exception {
		MvcResult result = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "fyou2-pub-" + content.hashCode())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA",
					"type", "INSIGHT", "content", content, "tags", List.of(), "mediaIds", List.of()))))
			.andExpect(status().isCreated())
			.andReturn();
		assertThat(result.getResponse().getStatus()).isEqualTo(201);
	}
}
