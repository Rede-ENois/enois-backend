package com.socialcoisa.rede.blog.adapters.in.web;

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

import tools.jackson.databind.ObjectMapper;

import com.socialcoisa.rede.blog.application.BlogNewsletterService;
import com.socialcoisa.rede.blog.application.port.out.BlogRepository;
import com.socialcoisa.rede.blog.application.port.out.BlogSubscriptionRepository;
import com.socialcoisa.rede.blog.domain.Blog;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import jakarta.persistence.EntityManager;

/**
 * Newsletter do blog: e-mail só para ASSINANTES verificados (nunca o autor, nunca não verificado),
 * toggle "avisar assinantes" respeitado, e descadastro num clique por token cifrado.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BlogNewsletterTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private BlogNewsletterService newsletter;

	@Autowired
	private BlogRepository blogs;

	@Autowired
	private BlogSubscriptionRepository subscriptions;

	@Autowired
	private EntityManager em;

	@Autowired
	private com.socialcoisa.rede.profile.application.port.out.UserProfileRepository profiles;

	@Autowired
	private com.socialcoisa.rede.ops.application.port.out.EmailLogRepository emailLogs;

	@Autowired
	private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	/** O log de e-mails guarda a tentativa (SENT ou FAILED) — é a fonte de verdade do envio. */
	private long emailsEnviados(String toEmail) {
		return emailLogs.findRecent(500).stream()
			.filter(log -> "BLOG_POST".equals(log.kind()))
			.filter(log -> toEmail.equals(log.toEmail()))
			.count();
	}

	private void publicar(TestAuthHelper.AuthSession autor, String titulo, boolean avisar) throws Exception {
		mockMvc.perform(post("/blogs/me/posts")
				.header("X-Session-Token", autor.token())
				.header("Idempotency-Key", "blog-news-" + titulo.hashCode() + "-" + avisar)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("title", titulo, "body", "Corpo do post da newsletter.",
					"format", "MARKDOWN", "notifySubscribers", avisar))))
			.andExpect(status().isCreated());
	}

	/** O slug e derivado do username pelo backend — pergunta a ele em vez de presumir a forma. */
	private String slugDoBlog(TestAuthHelper.AuthSession autor) throws Exception {
		var resultado = mockMvc.perform(get("/blogs/me").header("X-Session-Token", autor.token()))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(resultado.getResponse().getContentAsString()).get("slug").asText();
	}

	private void assinar(String slug, TestAuthHelper.AuthSession assinante) throws Exception {
		mockMvc.perform(post("/blogs/" + slug + "/subscription")
				.header("X-Session-Token", assinante.token()))
			.andExpect(status().isOk());
	}

	@Test
	void enviaSoParaAssinanteVerificadoENuncaParaOAutor() throws Exception {
		var autor = auth.authenticate("blognews_autor");
		var assinanteOk = auth.authenticate("blognews_assinante_ok");
		var assinanteNaoVerificado = auth.authenticate("blognews_assinante_nv");

		// Primeiro publish cria o blog com o slug do username.
		publicar(autor, "Primeiro post", false);
		Blog blog = blogs.findBySlug(slugDoBlog(autor)).orElseThrow();
		assinar(blog.slug(), assinanteOk);
		assinar(blog.slug(), assinanteNaoVerificado);
		// O segundo assinante perde a verificacao de e-mail (transacao própria: o fan-out roda
		// em outra thread e precisa VER o commit).
		transactionTemplate.executeWithoutResult(status -> em.createNativeQuery(
			"update users set email_verified_at = null where id = :id")
			.setParameter("id", assinanteNaoVerificado.userId())
			.executeUpdate());

		publicar(autor, "Post que vai por e-mail", true);

		// Fluxo REAL: publish -> evento pós-commit -> executor -> e-mail. O poll cobre o async.
		long enviados = 0;
		for (int i = 0; i < 100 && enviados == 0; i++) {
			enviados = emailsEnviados("blognews_assinante_ok@test.com");
			if (enviados == 0) {
				Thread.sleep(100);
			}
		}
		assertThat(enviados).isEqualTo(1);
		assertThat(emailsEnviados("blognews_assinante_nv@test.com")).isZero();
		assertThat(emailsEnviados("blognews_autor@test.com")).isZero();
	}

	@Test
	void toggleDesligadoNaoEnviaEmail() throws Exception {
		var autor = auth.authenticate("blognews_toggle_autor");
		var assinante = auth.authenticate("blognews_toggle_assinante");
		publicar(autor, "Primeiro post toggle", false);
		Blog blog = blogs.findBySlug(slugDoBlog(autor)).orElseThrow();
		assinar(blog.slug(), assinante);

		publicar(autor, "Post silencioso", false);

		assertThat(emailsEnviados("blognews_toggle_assinante@test.com")).isZero();
	}

	@Test
	void descadastroPorTokenRemoveAInscricao() throws Exception {
		var autor = auth.authenticate("blognews_unsub_autor");
		var assinante = auth.authenticate("blognews_unsub_assinante");
		publicar(autor, "Primeiro post unsub", false);
		Blog blog = blogs.findBySlug(slugDoBlog(autor)).orElseThrow();
		assinar(blog.slug(), assinante);
		assertThat(subscriptions.exists(blog.id(), assinante.userId())).isTrue();

		String token = newsletter.unsubscribeToken(blog.id(), assinante.userId());
		mockMvc.perform(get("/blogs/descadastrar").param("token", token))
			.andExpect(status().isOk());

		assertThat(subscriptions.exists(blog.id(), assinante.userId())).isFalse();
		// Token forjado/estranho nao derruba inscricao de ninguem.
		mockMvc.perform(get("/blogs/descadastrar").param("token", "nao-e-um-token"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void descadastroUmCliquePorPostDoClienteDeEmail() throws Exception {
		var autor = auth.authenticate("blognews_oneclick_autor");
		var assinante = auth.authenticate("blognews_oneclick_assinante");
		publicar(autor, "Primeiro post um clique", false);
		Blog blog = blogs.findBySlug(slugDoBlog(autor)).orElseThrow();
		assinar(blog.slug(), assinante);

		String token = newsletter.unsubscribeToken(blog.id(), assinante.userId());
		char ultimo = token.charAt(token.length() - 1);
		String adulterado = token.substring(0, token.length() - 1) + (ultimo == 'A' ? 'B' : 'A');

		// RFC 8058: sem sessao e sem Origin, corpo "List-Unsubscribe=One-Click", token na URL.
		mockMvc.perform(post("/blogs/descadastrar").queryParam("token", adulterado)
				.contentType(MediaType.APPLICATION_FORM_URLENCODED).content("List-Unsubscribe=One-Click"))
			.andExpect(status().isBadRequest());
		assertThat(subscriptions.exists(blog.id(), assinante.userId())).isTrue();

		mockMvc.perform(post("/blogs/descadastrar").queryParam("token", token)
				.contentType(MediaType.APPLICATION_FORM_URLENCODED).content("List-Unsubscribe=One-Click"))
			.andExpect(status().isOk());
		assertThat(subscriptions.exists(blog.id(), assinante.userId())).isFalse();
	}
}
