package com.socialcoisa.rede.profile.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.ObjectMapper;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

/**
 * Perfil lido no QA não devolve endereço do ambiente de produção.
 *
 * <p>A URL de mídia é gravada no asset e é estável (URL assinada apodreceria), então um ambiente que
 * nasceu de uma cópia entrega, no campo bruto ({@code avatar}), o endereço do ambiente de origem. O
 * campo {@code avatarUrl} é o que a tela abre: ele é reescrito para o host DESTE ambiente (ver
 * {@code MediaPublicHost}), e é isso que este teste protege.</p>
 */
@SpringBootTest(properties = { "rede.media.s3.public-base-url=https://cdn-qa.enois-teste.example/media",
		"rede.backend.url=https://api.enois-qa.enois-teste.example",
		"rede.frontend.url=https://enois-qa.enois-teste.example",
		"rede.cors.allowed-origins=https://enois-qa.enois-teste.example" })
@AutoConfigureMockMvc
class ProfileMediaHostTests {

	private static final String PRODUCTION_AVATAR = "https://api.enois.ia.br/api/storage/uploads/media_x/foto.jpg";
	private static final String QA_MEDIA = "https://cdn-qa.enois-teste.example";

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
	void theAvatarUrlPointsAtThisEnvironmentWhileTheStoredValueStays() throws Exception {
		var user = auth.authenticate("user_media_host");

		var salvo = mockMvc.perform(patch("/me/profile")
				.header("X-Session-Token", user.token())
				.header("Idempotency-Key", "media-host-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of("avatar", PRODUCTION_AVATAR))))
			.andExpect(status().isOk())
			.andReturn();

		var corpo = objectMapper.readTree(salvo.getResponse().getContentAsString());
		assertThat(corpo.path("avatar").asText()).isEqualTo(PRODUCTION_AVATAR);
		// Só o HOST muda: esquema, caminho e consulta continuam os do endereço gravado.
		assertThat(corpo.path("avatarUrl").asText()).isEqualTo(QA_MEDIA + "/api/storage/uploads/media_x/foto.jpg");

		var publico = objectMapper.readTree(mockMvc.perform(get("/me").header("X-Session-Token", user.token()))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString());
		assertThat(publico.path("avatarUrl").asText()).doesNotContain("enois.ia.br");
		assertThat(publico.path("avatarUrl").asText()).startsWith(QA_MEDIA);
	}
}