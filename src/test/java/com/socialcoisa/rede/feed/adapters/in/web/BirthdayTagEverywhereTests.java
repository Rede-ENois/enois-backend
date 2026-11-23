package com.socialcoisa.rede.feed.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.shared.birthday.DiaDoNascimento;
import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestDatabase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A tag "Aniversariante" acompanha o nome na publicação, na leitura do post e nos comentários.
 * A data vem do relógio do contexto: quem faz aniversário HOJE (em Brasília) recebe a tag; quem
 * não faz, não.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BirthdayTagEverywhereTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private UserProfileRepository profiles;

	@Autowired
	private Clock clock;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	/** Nascimento no dia de hoje (Brasília), com a idade pedida. */
	private String nascimentoNoDiaDeHoje(int anos) {
		return DiaDoNascimento.hojeNaZona(clock).minusYears(anos).toString();
	}

	private void declararNascimento(TestAuthHelper.AuthSession conta, String birthDate) throws Exception {
		String token = conta.token();
		mockMvc.perform(post("/me/birth-date")
				.header("X-Session-Token", token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("birthDate", birthDate))))
			.andExpect(status().isOk());
	}

	private String usernameDe(String userId) {
		return profiles.findById(userId).map(UserProfile::username).orElseThrow();
	}

	private String publicar(String token, String key, String texto) throws Exception {
		MvcResult publicado = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", token)
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA",
					"type", "INSIGHT", "content", texto, "tags", List.of(), "mediaIds", List.of()))))
			.andReturn();
		return objectMapper.readTree(publicado.getResponse().getContentAsString()).get("id").asString();
	}

	@Test
	void publicacaoLeituraEComentarioCarregamATagAniversariante() throws Exception {
		var autor = auth.authenticate("bday_autor");
		declararNascimento(autor, nascimentoNoDiaDeHoje(20));

		// Prova que a data gravada é a declarada (o comando de escrita não devolve a data).
		assertThat(profiles.findById(autor.userId()).orElseThrow().birthDate())
			.isEqualTo(DiaDoNascimento.hojeNaZona(clock).minusYears(20));

		String postId = publicar(autor.token(), "bday-pub-1", "Post de aniversariante.");

		mockMvc.perform(get("/feed/posts/" + postId).header("X-Session-Token", autor.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.authorBirthdayToday").value(true));

		MvcResult comentario = mockMvc.perform(post("/feed/posts/" + postId + "/comments")
				.header("X-Session-Token", autor.token())
				.header("Idempotency-Key", "bday-comment-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"content", "Comentário de aniversariante.", "mediaIds", List.of(),
					"mentionedUserIds", List.of()))))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.authorBirthdayToday").value(true))
			.andReturn();
		String commentId = objectMapper.readTree(comentario.getResponse().getContentAsString()).get("id").asString();

		MvcResult listados = mockMvc.perform(get("/feed/posts/" + postId + "/comments")
				.header("X-Session-Token", autor.token()))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode proprio = null;
		for (JsonNode item : objectMapper.readTree(listados.getResponse().getContentAsString()).get("items")) {
			if (commentId.equals(item.path("id").asString())) {
				proprio = item;
				break;
			}
		}
		assertThat(proprio).as("comentário na listagem").isNotNull();
		assertThat(proprio.path("authorBirthdayToday").asBoolean()).isTrue();

		// O feed do perfil mostra o mesmo selo do post.
		mockMvc.perform(get("/users/" + usernameDe(autor.userId()) + "/posts")
				.header("X-Session-Token", autor.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].id").value(postId))
			.andExpect(jsonPath("$.items[0].authorBirthdayToday").value(true));
	}

	@Test
	void quemNaoFazAniversarioHojeNaoRecebeATag() throws Exception {
		var autor = auth.authenticate("bday_sem");
		// Aniversário amanhã (em Brasília): hoje a tag não aparece.
		declararNascimento(autor, DiaDoNascimento.hojeNaZona(clock).plusDays(1).minusYears(28).toString());

		mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", autor.token())
				.header("Idempotency-Key", "bday-sem-pub")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA",
					"type", "INSIGHT", "content", "Post sem aniversário.", "tags", List.of(),
					"mediaIds", List.of()))))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.authorBirthdayToday").value(false));
	}

	@Test
	void contaSemDataDeNascimentoNaoAnunciaAniversario() throws Exception {
		var autor = auth.authenticate("bday_sem_data");
		String postId = publicar(autor.token(), "bday-sem-data-pub", "Post antes de apagar a data.");
		TestDatabase.clearBirthDate(autor.userId());

		mockMvc.perform(get("/feed/posts/" + postId).header("X-Session-Token", autor.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.authorBirthdayToday").value(false));
	}

	@Test
	void menorDeIdadeNaoRecebeATag() throws Exception {
		var autor = auth.authenticate("bday_menor");
		// 17 anos: faz aniversário hoje, mas o perfil é de menor — a tag não pode expor isso.
		String nascimento = DiaDoNascimento.hojeNaZona(clock).minusYears(17).toString();
		declararNascimento(autor, nascimento);

		mockMvc.perform(get("/users/" + usernameDe(autor.userId())).header("X-Session-Token", autor.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.birthdayToday").value(false));
	}

	/**
	 * O cadastro antigo gravava a data do próprio cadastro como data de nascimento, e isso se
	 * repete em conta nova criada por quem digitou a data de hoje: dia/mês igual ao cadastro com
	 * idade zero nele não é aniversário. (A conta nasce com 1990-05-10 declarado; a data de
	 * cadastro vai para 1990 para o fluxo de escrita aceitar a troca — a idade zero é o que pesa.)
	 */
	@Test
	void dataIgualAoDiaDoCadastroNaoContaComoAniversario() throws Exception {
		var autor = auth.authenticate("bday_copia_cadastro");
		TestDatabase.setCreatedAt(autor.userId(), LocalDate.of(1990, 5, 10));
		declararNascimento(autor, "1990-05-10");

		mockMvc.perform(get("/users/" + usernameDe(autor.userId())).header("X-Session-Token", autor.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.birthdayToday").value(false));
	}
}
