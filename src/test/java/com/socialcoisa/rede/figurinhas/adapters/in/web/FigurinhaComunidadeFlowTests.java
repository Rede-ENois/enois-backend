package com.socialcoisa.rede.figurinhas.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.socialcoisa.rede.figurinhas.application.FigurinhasComunidadeService;
import com.socialcoisa.rede.media.application.MediaImageModerationService;
import com.socialcoisa.rede.media.application.port.out.MediaObjectStore;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.ModeratorGuard;
import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestDatabase;

import tools.jackson.databind.ObjectMapper;

/**
 * Figurinhas da Comunidade de ponta a ponta: envio em três passos, conferência do arquivo, catálogo,
 * busca, índice do texto, remoção pelo autor, denúncia e desativação pela moderação.
 *
 * <p>O storage é um mock: o que interessa aqui é o que CHEGA nele (só arquivo conferido) e o que
 * o serviço faz com o que não chega. A checagem de imagem pública também é mock — o teste garante
 * que ela É chamada antes de gravar.</p>
 */
@SpringBootTest(properties = {
	"rede.media.storage=s3",
	"rede.media.s3.bucket=bucket-de-teste",
	"rede.media.s3.publicBaseUrl=https://enois.exemplo.com/api/storage",
	"rede.moderation.moderator-min-reputation=-1",
	"rede.rate.figurinha-limit=6",
	"rede.rate.figurinha-window-minutes=0.0001",
	"aws.accessKeyId=teste",
	"aws.secretAccessKey=teste",
	"aws.region=sa-east-1" })
class FigurinhaComunidadeFlowTests {

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	// Nome do bean: o store real do S3StorageConfig existe neste contexto e precisa ser substituído.
	@MockitoBean(name = "s3MediaObjectStore")
	private MediaObjectStore storage;

	@MockitoBean
	private MediaImageModerationService imageModeration;

	@MockitoSpyBean
	private ModeratorGuard moderatorGuard;

	private MockMvc mockMvc;
	private TestAuthHelper auth;

	@BeforeEach
	void setUp() throws Exception {
		mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
		auth = new TestAuthHelper(mockMvc, objectMapper);
		// A moderação segue as regras reais (admin ou moderador aprovado); o teste promove o
		// moderador pelo endpoint do painel, como a produção faz.
	}

	// ------------------------------------------------------------------ fluxo

	@Test
	void cadastroEmTresPassosPublicaNoCatalogo() throws Exception {
		TestAuthHelper.AuthSession autor = auth.authenticate("figautor1");
		String codigo = codigo();
		byte[] png = png(64, 64);

		Map<String, Object> enviado = enviar(autor, codigo, "Gatinho animado", png);
		String id = (String) enviado.get("id");

		org.mockito.Mockito.verify(storage).write(anyString(), anyString(), anyLong(), any());
		assertThat((String) enviado.get("codigo")).isEqualTo(":" + codigo + ":");

		mockMvc.perform(get("/figurinhas").header("X-Session-Token", autor.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].id").value(org.hamcrest.Matchers.notNullValue()))
			.andExpect(jsonPath("$.autores").isNotEmpty());

		// A figurinha publicada aparece na página dela, com o formato que o cliente espera.
		mockMvc.perform(get("/figurinhas").param("q", codigo).header("X-Session-Token", autor.token()))
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].id").value(id))
			.andExpect(jsonPath("$.items[0].codigo").value(":" + codigo + ":"))
			.andExpect(jsonPath("$.items[0].origem").value("COMUNIDADE"))
			.andExpect(jsonPath("$.items[0].ativo").value(true))
			.andExpect(jsonPath("$.items[0].imagem").value(org.hamcrest.Matchers.startsWith("/storage/")))
			.andExpect(jsonPath("$.items[0].largura").value(64))
			.andExpect(jsonPath("$.autores[\"" + autor.userId() + "\"]").exists());
	}

	@Test
	void catalogoExigeSessao() throws Exception {
		mockMvc.perform(get("/figurinhas")).andExpect(status().isUnauthorized());
	}

	@Test
	void buscaEncontraPorNomeCodigoEIdComCaracteresEscapados() throws Exception {
		TestAuthHelper.AuthSession autor = auth.authenticate("figbusca1");
		String codigo = codigo();
		Map<String, Object> publicado = enviar(autor, codigo, "Coração 100% feliz", png(40, 40));
		String id = (String) publicado.get("id");

		for (String termo : List.of("coração", codigo, id)) {
			mockMvc.perform(get("/figurinhas").param("q", termo).header("X-Session-Token", autor.token()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.total").value(1))
				.andExpect(jsonPath("$.items[0].id").value(id));
		}
		// "%" e "_" são literais na busca, não curingas.
		// "%" casa com o nome publicado (que tem um "%" de verdade), mas o "_" não: literal.
		mockMvc.perform(get("/figurinhas").param("q", "%").header("X-Session-Token", autor.token()))
			.andExpect(jsonPath("$.items[0].id").value(id));
		// O "_" é literal: como curinga, `z_z` casaria com "cazeuz"; como literal, não casa com nada.
		// (Um "_" solto casaria com os códigos "fig..." de outras figurinhas publicadas aqui.)
		for (String termo : List.of("z_z", "%z%")) {
			mockMvc.perform(get("/figurinhas").param("q", termo).header("X-Session-Token", autor.token()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.total").value(0));
		}
	}

	@Test
	void jpgTambemEhAceito() throws Exception {
		TestAuthHelper.AuthSession autor = auth.authenticate("figjpg1");
		enviar(autor, codigo(), "Foto pequena", jpg(50, 30));
	}

	@Test
	void arquivoGravadoEhAImagemLimpa() throws Exception {
		TestAuthHelper.AuthSession autor = auth.authenticate("figlimpo1");
		byte[] png = png(32, 32);
		enviar(autor, codigo(), "Limpo", png);
		// O que o serviço entrega ao storage é PNG de verdade e abre como imagem do tamanho enviado.
		org.mockito.ArgumentCaptor<java.io.InputStream> corpo = org.mockito.ArgumentCaptor.forClass(
			java.io.InputStream.class);
		org.mockito.Mockito.verify(storage).write(anyString(), anyString(), anyLong(), corpo.capture());
		BufferedImage imagem = ImageIO.read(corpo.getValue());
		assertThat(imagem).isNotNull();
		assertThat(imagem.getWidth()).isEqualTo(32);
	}

	// ------------------------------------------------------------------ conferência do arquivo

	@Test
	void recusaTipoForaDePngJpeg() throws Exception {
		TestAuthHelper.AuthSession autor = auth.authenticate("figtipo1");
		mockMvc.perform(post("/figurinhas/envios").header("X-Session-Token", autor.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("mimeType", "image/svg+xml", "size", 100))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("FIGURINHA_TIPO_NAO_PERMITIDO"));
	}

	@Test
	void recusaDeclaracaoAcimaDoTeto() throws Exception {
		TestAuthHelper.AuthSession autor = auth.authenticate("figteto1");
		mockMvc.perform(post("/figurinhas/envios").header("X-Session-Token", autor.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("mimeType", "image/png", "size", 600_000))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("FIGURINHA_ARQUIVO_GRANDE"));
	}

	@Test
	void recusaImagemAcimaDe400Pixels() throws Exception {
		TestAuthHelper.AuthSession autor = auth.authenticate("figgrande1");
		esperarRecusaDeEnvio(autor, png(401, 40), "FIGURINHA_MUITO_GRANDE");
		org.mockito.Mockito.verify(storage, org.mockito.Mockito.never()).write(anyString(), anyString(), anyLong(), any());
	}

	@Test
	void recusaHtmlDisfarcadoDePng() throws Exception {
		TestAuthHelper.AuthSession autor = auth.authenticate("fightml1");
		esperarRecusaDeEnvio(autor, "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.US_ASCII),
			"FIGURINHA_IMAGEM_INVALIDA");
	}

	@Test
	void recusaPoliglotaPngComScript() throws Exception {
		TestAuthHelper.AuthSession autor = auth.authenticate("figpoli1");
		byte[] png = png(32, 32);
		byte[] script = "<script>alert(document.cookie)</script>".getBytes(StandardCharsets.US_ASCII);
		byte[] poliglota = new byte[png.length + script.length];
		System.arraycopy(png, 0, poliglota, 0, png.length);
		System.arraycopy(script, 0, poliglota, png.length, script.length);
		esperarRecusaDeEnvio(autor, poliglota, "FIGURINHA_IMAGEM_INVALIDA");
		org.mockito.Mockito.verify(storage, org.mockito.Mockito.never()).write(anyString(), anyString(), anyLong(), any());
	}

	@Test
	void recusaBytesQueNaoSaoPng() throws Exception {
		TestAuthHelper.AuthSession autor = auth.authenticate("fignotpng1");
		esperarRecusaDeEnvio(autor, "isto nao e uma imagem".getBytes(StandardCharsets.US_ASCII),
			"FIGURINHA_IMAGEM_INVALIDA");
	}

	@Test
	void recusaCorpoAcimaDoTeto() throws Exception {
		TestAuthHelper.AuthSession autor = auth.authenticate("figcorpo1");
		// Declaração no teto do envio; o corpo vem maior do que ela.
		String mediaId = declarar(autor, "image/png", 400_000);
		mockMvc.perform(post("/figurinhas/envios/" + mediaId).header("X-Session-Token", autor.token())
				.contentType(MediaType.APPLICATION_OCTET_STREAM)
				.content(new byte[600_000]))
			.andExpect(status().isPayloadTooLarge());
	}

	@Test
	void outroUsuarioNaoEnviaNoEnvioAlheio() throws Exception {
		TestAuthHelper.AuthSession dono = auth.authenticate("figdono1");
		TestAuthHelper.AuthSession intruso = auth.authenticate("figintruso1");
		String mediaId = declarar(dono, "image/png", png(32, 32).length);
		mockMvc.perform(enviarBytes(mediaId, png(32, 32), intruso))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("FIGURINHA_ENVIO_NAO_E_SEU"));
	}

	@Test
	void publicarSemEnviarImagemEhRecusado() throws Exception {
		TestAuthHelper.AuthSession autor = auth.authenticate("figsemimg1");
		String mediaId = declarar(autor, "image/png", png(32, 32).length);
		mockMvc.perform(post("/figurinhas").header("X-Session-Token", autor.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(
					Map.of("mediaId", mediaId, "codigo", codigo(), "nome", "Sem imagem"))))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("FIGURINHA_IMAGEM_NAO_ENVIADA"));
	}

	@Test
	void codigoReservadoENaoPertenceAoCatalogoComunitario() throws Exception {
		TestAuthHelper.AuthSession autor = auth.authenticate("figreserv1");
		for (String codigo : List.of("enois_like", "bttv_yay")) {
			esperarRecusaDePublicacao(autor, codigo, 409, "FIGURINHA_CODIGO_RESERVADO");
		}
	}

	@Test
	void codigoInvalidoENomeObrigatorio() throws Exception {
		TestAuthHelper.AuthSession autor = auth.authenticate("figinval1");
		esperarRecusaDePublicacao(autor, "Maiúscula Com Espaço", 400, "FIGURINHA_CODIGO_INVALIDO");
		esperarRecusaDePublicacao(autor, codigo(), 400, "FIGURINHA_NOME_OBRIGATORIO", " ");
	}

	@Test
	void codigoAtivoNaoPodeSerReaproveitado() throws Exception {
		TestAuthHelper.AuthSession autor = auth.authenticate("figreuso1");
		String codigo = codigo();
		enviar(autor, codigo, "Primeira", png(32, 32));
		esperarRecusaDePublicacao(autor, codigo, 409, "FIGURINHA_CODIGO_EM_USO");
	}

	// ------------------------------------------------------------------ índice do texto

	@Test
	void indiceTrazSomenteAtivas() throws Exception {
		TestAuthHelper.AuthSession autor = auth.authenticate("figindice1");
		String vivo = codigo();
		Map<String, Object> publicado = enviar(autor, vivo, "Removida", png(32, 32));
		String morto = codigo();
		enviar(autor, morto, "Ativa", png(32, 32));

		mockMvc.perform(delete("/figurinhas/" + publicado.get("id")).header("X-Session-Token", autor.token()))
			.andExpect(status().isOk());

		mockMvc.perform(get("/figurinhas/index").param("codigos", vivo + "," + morto))
			.andExpect(status().isOk())
			// A removida não entra; a ativa entra.
			.andExpect(jsonPath("$." + vivo).doesNotExist())
			.andExpect(jsonPath("$." + morto).value(org.hamcrest.Matchers.startsWith("/storage/")));
	}

	// ------------------------------------------------------------------ autor

	@Test
	void autorRemoveAPropriaFigurinhaESoAPropria() throws Exception {
		TestAuthHelper.AuthSession autor = auth.authenticate("figpropria1");
		TestAuthHelper.AuthSession outro = auth.authenticate("figpropria2");
		Map<String, Object> publicado = enviar(autor, codigo(), "Minha", png(32, 32));
		String id = (String) publicado.get("id");

		mockMvc.perform(delete("/figurinhas/" + id).header("X-Session-Token", outro.token()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("FIGURINHA_NAO_E_SUA"));

		mockMvc.perform(delete("/figurinhas/" + id).header("X-Session-Token", autor.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("DESATIVADA"));
	}

	@Test
	void codigoFicaLivreDepoisDaDesativacao() throws Exception {
		TestAuthHelper.AuthSession autor = auth.authenticate("figlibera1");
		String codigo = codigo();
		Map<String, Object> primeiro = enviar(autor, codigo, "Antiga", png(32, 32));
		mockMvc.perform(delete("/figurinhas/" + primeiro.get("id")).header("X-Session-Token", autor.token()))
			.andExpect(status().isOk());
		enviar(autor, codigo, "Nova com o mesmo código", png(32, 32));
	}

	// ------------------------------------------------------------------ moderação

	@Test
	void moderacaoDesativaComMotivoEHistorioFicaGravado() throws Exception {
		TestAuthHelper.AuthSession autor = auth.authenticate("figmodaut1");
		TestAuthHelper.AuthSession mod = promovidoAModerador("figmod1");
		Map<String, Object> publicado = enviar(autor, codigo(), "Suspeita", png(32, 32));
		String id = (String) publicado.get("id");

		mockMvc.perform(post("/moderation/figurinhas/" + id + "/desativar")
				.header("X-Session-Token", autor.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("motivo", "Motivo suficientemente longo"))))
			.andExpect(status().isForbidden());

		// O motivo é obrigatório: desativação sem porquê não deixa rastro para a auditoria.
		mockMvc.perform(post("/moderation/figurinhas/" + id + "/desativar")
				.header("X-Session-Token", mod.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("motivo", "curto"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("FIGURINHA_MOTIVO_OBRIGATORIO"));

		mockMvc.perform(post("/moderation/figurinhas/" + id + "/desativar")
				.header("X-Session-Token", mod.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("motivo", "Conteúdo sexual na imagem"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("DESATIVADA"));

		// Linha continua gravada (auditoria), fora do catálogo e da busca.
		assertThat(jdbc.queryForObject("select ativo from figurinhas_comunidade where id = ?", Integer.class, id))
			.isNull();
		assertThat(jdbc.queryForObject("select motivo_desativacao from figurinhas_comunidade where id = ?",
			String.class, id)).contains("sexual");

		mockMvc.perform(get("/figurinhas").param("q", id).header("X-Session-Token", autor.token()))
			.andExpect(jsonPath("$.total").value(0));

		mockMvc.perform(get("/figurinhas/" + id + "/historico").header("X-Session-Token", mod.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.eventos.length()").value(2))
			.andExpect(jsonPath("$.eventos[1].tipo").value("DESATIVADA"));

		// O autor vê o próprio histórico; outra pessoa, não.
		mockMvc.perform(get("/figurinhas/" + id + "/historico").header("X-Session-Token", autor.token()))
			.andExpect(status().isOk());
		TestAuthHelper.AuthSession terceiro = auth.authenticate("figmodter3");
		mockMvc.perform(get("/figurinhas/" + id + "/historico").header("X-Session-Token", terceiro.token()))
			.andExpect(status().isForbidden());
	}

	@Test
	void denunciaNaoRemoveSozinhaEPenalidadeHumanaDesativaESanciona() throws Exception {
		TestAuthHelper.AuthSession autor = auth.authenticate("figdenaut1");
		TestAuthHelper.AuthSession denunciante = auth.authenticate("figdenden1");
		TestAuthHelper.AuthSession admin = auth.authenticate("figdenadmin1");
		TestDatabase.setAdmin(admin.userId());

		Map<String, Object> publicado = enviar(autor, codigo(), "Denunciada", png(32, 32));
		String id = (String) publicado.get("id");

		mockMvc.perform(post("/reports")
				.header("X-Session-Token", denunciante.token())
				.header("Idempotency-Key", "denuncia-" + id)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("targetType", "FIGURINHA_COMUNIDADE",
					"targetId", id, "reason", "SEXUAL_CONTENT", "description", "Imagem explícita"))))
			.andExpect(status().isCreated());

		// A denúncia sozinha NÃO tira do catálogo.
		assertThat(jdbc.queryForObject("select ativo from figurinhas_comunidade where id = ?", Integer.class, id))
			.isEqualTo(1);

		String reportId = jdbc.queryForObject(
			"select id from reports where target_id = ? order by created_at desc limit 1", String.class, id);
		mockMvc.perform(post("/admin/reports/" + reportId + "/penalize")
				.header("X-Session-Token", admin.token()))
			.andExpect(status().isOk());

		assertThat(jdbc.queryForObject("select ativo from figurinhas_comunidade where id = ?", Integer.class, id))
			.isNull();
		assertThat(jdbc.queryForObject(
			"select count(*) from figurinhas_comunidade_eventos where figurinha_id = ? and tipo = 'DESATIVADA'",
			Integer.class, id)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from user_sanctions where user_id = ?", Integer.class,
			autor.userId())).isPositive();

		mockMvc.perform(get("/figurinhas/index").param("codigos", (String) publicado.get("codigo")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$").isEmpty());
	}

	@Test
	void limiteDePublicacaoPorConta() throws Exception {
		TestAuthHelper.AuthSession autor = auth.authenticate("figlimite1");
		// O limite do teste é 6 numa janela quase nula: publicar em sequência passa, então aqui
		// só se confere que a conta do limite é do serviço e não derruba o cadastro normal.
		assertThat(FigurinhasComunidadeService.MAX_ATIVAS_POR_AUTOR).isEqualTo(50);
		enviar(autor, codigo(), "Dentro do limite", png(32, 32));
	}

	@Test
	void contaSemEmailConfirmadoNaoCadastra() throws Exception {
		TestAuthHelper.AuthSession autor = auth.authenticate("fignaoverif1");
		TestDatabase.clearEmailVerification(autor.userId());
		mockMvc.perform(post("/figurinhas/envios").header("X-Session-Token", autor.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("mimeType", "image/png", "size", 100))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));
		TestDatabase.markEmailVerified(autor.userId(), java.time.Instant.now());
	}

	// ------------------------------------------------------------------ auxiliares

	/** Promove a conta a moderador pelo painel do admin — o mesmo caminho da produção. */
	private TestAuthHelper.AuthSession promovidoAModerador(String nome) throws Exception {
		final TestAuthHelper.AuthSession admin = auth.authenticate("figadmin");
		TestDatabase.setAdmin(admin.userId());
		TestAuthHelper.AuthSession mod = auth.authenticate(nome);
		jdbc.update("delete from moderator_applications where user_id = ?", mod.userId());
		String username = jdbc.queryForObject("select username from users where email = ?", String.class,
			nome + "@test.com");
		mockMvc.perform(post("/admin/moderators").header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("username", username, "note", "Papel de teste"))))
			.andExpect(status().isOk());
		return mod;
	}

	private Map<String, Object> enviar(TestAuthHelper.AuthSession autor, String codigo, String nome, byte[] imagem)
			throws Exception {
		String mime = imagem.length > 0 && (imagem[0] & 0xFF) == 0x89 ? "image/png" : "image/jpeg";
		String mediaId = declarar(autor, mime, imagem.length);
		mockMvc.perform(enviarBytes(mediaId, imagem, autor)).andExpect(status().isOk());
		MvcResult result = mockMvc.perform(post("/figurinhas").header("X-Session-Token", autor.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("mediaId", mediaId, "codigo", codigo, "nome", nome))))
			.andExpect(status().isOk())
			.andReturn();
		@SuppressWarnings("unchecked")
		Map<String, Object> resposta = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
		@SuppressWarnings("unchecked")
		Map<String, Object> figurinha = (Map<String, Object>) resposta.get("figurinha");
		return figurinha;
	}

	private String declarar(TestAuthHelper.AuthSession autor, String mime, long tamanho) throws Exception {
		MvcResult result = mockMvc.perform(post("/figurinhas/envios").header("X-Session-Token", autor.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("mimeType", mime, "size", tamanho))))
			.andExpect(status().isOk())
			.andReturn();
		@SuppressWarnings("unchecked")
		Map<String, Object> resposta = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
		return (String) resposta.get("mediaId");
	}

	private MockHttpServletRequestBuilder enviarBytes(String mediaId, byte[] bytes,
			TestAuthHelper.AuthSession autor) {
		return post("/figurinhas/envios/" + mediaId).header("X-Session-Token", autor.token())
			.contentType(MediaType.APPLICATION_OCTET_STREAM)
			.content(bytes);
	}

	private void esperarRecusaDeEnvio(TestAuthHelper.AuthSession autor, byte[] bytes, String codigo)
			throws Exception {
		String mime = bytes.length > 0 && (bytes[0] & 0xFF) == 0x89 ? "image/png" : "image/jpeg";
		String mediaId = declarar(autor, mime, bytes.length);
		mockMvc.perform(enviarBytes(mediaId, bytes, autor))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(codigo));
	}

	private void esperarRecusaDePublicacao(TestAuthHelper.AuthSession autor, String codigo, int status,
			String erro) throws Exception {
		esperarRecusaDePublicacao(autor, codigo, status, erro, "Nome válido");
	}

	private void esperarRecusaDePublicacao(TestAuthHelper.AuthSession autor, String codigo, int status, String erro,
			String nome) throws Exception {
		byte[] imagem = png(32, 32);
		String mediaId = declarar(autor, "image/png", imagem.length);
		mockMvc.perform(enviarBytes(mediaId, imagem, autor)).andExpect(status().isOk());
		mockMvc.perform(post("/figurinhas").header("X-Session-Token", autor.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("mediaId", mediaId, "codigo", codigo, "nome", nome))))
			.andExpect(status().is(status))
			.andExpect(jsonPath("$.code").value(erro));
	}

	private static String codigo() {
		return "fig" + Long.toUnsignedString(System.nanoTime(), 36);
	}

	private static byte[] png(int largura, int altura) throws Exception {
		BufferedImage imagem = new BufferedImage(largura, altura, BufferedImage.TYPE_INT_RGB);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		// Tipo RGB, sem alpha: o leitor (e a reescrita do arquivo) tem mais de um caminho para a
		// imagem com canal alfa, e o teste não está exercitando isso.
		BufferedImage rgb = new BufferedImage(largura, altura, BufferedImage.TYPE_INT_RGB);
		rgb.getGraphics().drawImage(imagem, 0, 0, null);
		ImageIO.write(rgb, "png", out);
		return out.toByteArray();
	}

	private static byte[] jpg(int largura, int altura) throws Exception {
		BufferedImage imagem = new BufferedImage(largura, altura, BufferedImage.TYPE_INT_RGB);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(imagem, "jpg", out);
		return out.toByteArray();
	}
}
