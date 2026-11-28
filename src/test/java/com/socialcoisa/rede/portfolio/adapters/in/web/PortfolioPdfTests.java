package com.socialcoisa.rede.portfolio.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.socialcoisa.rede.media.adapters.out.render.PdfBoxPageRendererTests;
import com.socialcoisa.rede.media.application.port.out.MediaAssetRepository;
import com.socialcoisa.rede.media.application.port.out.MediaObjectStore;
import com.socialcoisa.rede.media.application.port.out.MediaStorageGateway;
import com.socialcoisa.rede.media.application.port.out.MediaPageSets;
import com.socialcoisa.rede.media.domain.MediaAsset;
import com.socialcoisa.rede.moderation.application.MediaSafetyService;
import com.socialcoisa.rede.moderation.application.MediaSafetySweepWorker;
import com.socialcoisa.rede.moderation.domain.MediaSafetyVerdict;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * A regra que o portfólio não pode furar: PDF só entra em peça depois que cada página foi conferida
 * pela checagem de conteúdo — e o documento com página explícita sai do portfólio E do storage. O
 * arquivo inteiro nunca é olhado pelo classificador; o que vale é o que está desenhado nas folhas.
 *
 * <p>O PDF sobe pelo caminho de verdade (`/media/uploads` + `PUT /storage`). O render acontece fora
 * da resposta, então cada teste espera o pedido de páginas chegar ao fim antes de olhar o resultado.
 * O classificador está desligado na suíte: onde a folha precisa ser barrada, o veredito entra pelo
 * ledger e passa pelo serviço e pela varredura de verdade.</p>
 */
@SpringBootTest(properties = { "rede.media.storage=s3", "rede.media.s3.bucket=bucket-de-teste",
		"rede.media.s3.publicBaseUrl=https://enois.exemplo.com/api/storage" })
@AutoConfigureMockMvc
class PortfolioPdfTests {

	static {
		System.setProperty("aws.accessKeyId", "test");
		System.setProperty("aws.secretAccessKey", "test");
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private MediaAssetRepository assets;

	@Autowired
	private MediaSafetySweepWorker safetySweep;

	@Autowired
	private MemoriaObjectStore memoria;

	@Autowired
	private MediaPageSets analise;

	@Autowired
	private MediaSafetyService seguranca;

	@Autowired
	private PlatformTransactionManager transactionManager;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
		// Cada cenário é um mundo: o objeto que um cenário largou no bucket não é lido pelo outro.
		memoria.novoCenario("uploads");
	}

	@TestConfiguration
	static class FakeStorage {

		@Bean
		@Primary
		MemoriaObjectStore memoriaObjectStore() {
			return new MemoriaObjectStore();
		}

		@Bean
		@Primary
		BucketGateway bucketGateway(MemoriaObjectStore store) {
			return new BucketGateway(store);
		}
	}

	/**
	 * Bucket em memória: o proxy de storage grava e o render de páginas relê o mesmo objeto. Cada
	 * cenário tem o seu prefixo, e a leitura de outro prefixo não acha nada — o bucket de produção
	 * não serve o objeto de antes do cenário, e o teste não pode servir.
	 */
	static class MemoriaObjectStore implements MediaObjectStore {

		private final Map<String, byte[]> objetos = new ConcurrentHashMap<>();

		private final Map<String, String> tipos = new ConcurrentHashMap<>();

		private String cenario = "";

		/** O prefixo das chaves deste cenário; trocar o prefixo larga o que o cenário anterior gravou. */
		synchronized void novoCenario(String nome) {
			objetos.keySet().removeIf(chave -> !chave.startsWith(cenario));
			tipos.keySet().removeIf(chave -> !chave.startsWith(cenario));
			cenario = nome + "/";
		}



		boolean has(String key) {
			return objetos.containsKey(key);
		}

		void apagar(String key) {
			objetos.remove(key);
		}

		@Override
		public StoredObject read(String key, String range) {
			if (!key.startsWith(cenario)) {
				throw new IllegalStateException("objeto ausente: " + key);
			}
			byte[] conteudo = objetos.get(key);
			if (conteudo == null) {
				throw new IllegalStateException("objeto ausente: " + key);
			}
			return new StoredObject(new ByteArrayInputStream(conteudo), conteudo.length,
				tipos.getOrDefault(key, MediaType.APPLICATION_PDF_VALUE), null, null);
		}

		@Override
		public void write(String key, String contentType, long length, InputStream body) {
			try {
				objetos.put(key, body.readAllBytes());
				tipos.put(key, contentType);
			}
			catch (java.io.IOException failure) {
				throw new IllegalStateException(failure);
			}
		}
	}

	/**
	 * O gateway de storage de produção fala com o CDN e o bucket de verdade; aqui os dois são o
	 * mesmo mapa em memória, e é ele que mostra que o apagamento chegou ao arquivo.
	 */
	static class BucketGateway implements MediaStorageGateway {

		private final MemoriaObjectStore store;

		BucketGateway(MemoriaObjectStore store) {
			this.store = store;
		}

		@Override
		public void purgeCdn(MediaAsset asset) {
		}

		@Override
		public void deleteObject(MediaAsset asset) {
			store.apagar(asset.objectKey());
		}
	}

	@Test
	void paginaBarradaNaSubidaMantemOPdfForaDoPortfolio() throws Exception {
		var dono = auth.authenticate("pf_pdf_" + unico());
		MediaAsset pdf = subirPdf(dono, 2);
		Paginas desenho = esperarDesenho(pdf);

		// O que a subida barrou nunca chega a ser página servível: a peça fica sem as folhas e não
		// libera documento nenhum.
		conferir(desenho.folhas().subList(0, 1), dono.userId(), MediaSafetyVerdict.EXPLICIT);

		mockMvc.perform(post("/me/portfolio/blocks")
			.header("X-Session-Token", dono.token())
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(bloco("PDF", pdf.id()))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("PORTFOLIO_BLOCK_MEDIA_INVALID"));
	}

	@Test
	void documentoComPaginasLiberadasEntraNaPecaEOLinkAbre() throws Exception {
		var dono = auth.authenticate("pf_pdf_" + unico());
		MediaAsset pdf = subirPdf(dono, 2);
		Paginas desenho = esperarDesenho(pdf);
		assertThat(desenho.estado()).isEqualTo("RENDERED");
		conferir(desenho.folhas(), dono.userId(), MediaSafetyVerdict.SAFE);
		// A conta do cenário não carrega banimento de outra rodada: banido, o login é recusado e ela nem
		// chega a publicar.
		semBanimentoAnterior(dono);

		mockMvc.perform(post("/me/portfolio/blocks")
			.header("X-Session-Token", dono.token())
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(bloco("PDF", pdf.id()))))
			.andExpect(status().isCreated());
		String username = publicar(dono);
		assertThat(countBlocks(username)).isEqualTo(1);
		mockMvc.perform(get("/storage/" + pdf.objectKey()).header("X-Session-Token", dono.token()))
			.andExpect(status().isOk());
	}

	@Test
	void documentoComPaginaLiberadaAindaBarradoNoLedgerNaoEntra() throws Exception {
		var dono = auth.authenticate("pf_pdf_" + unico());
		MediaAsset pdf = subirPdf(dono, 2);
		Paginas desenho = esperarDesenho(pdf);
		assertThat(desenho.estado()).isEqualTo("RENDERED");
		conferir(desenho.folhas().subList(0, 1), dono.userId(), MediaSafetyVerdict.SAFE);
		conferir(desenho.folhas().subList(1, 2), dono.userId(), MediaSafetyVerdict.EXPLICIT);
		semBanimentoAnterior(dono);

		mockMvc.perform(post("/me/portfolio/blocks")
			.header("X-Session-Token", dono.token())
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(bloco("PDF", pdf.id()))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("PORTFOLIO_BLOCK_MEDIA_INVALID"));
	}

	@Test
	void paginaExplicitaTiraODocumentoDoPortfolioEDoStorage() throws Exception {
		var dono = auth.authenticate("pf_pdf_" + unico());
		MediaAsset pdf = subirPdf(dono, 2);
		Paginas desenho = esperarDesenho(pdf);
		assertThat(desenho.estado()).isEqualTo("RENDERED");
		List<MediaAsset> paginas = desenho.folhas();
		// As páginas saem do ar com SAFE: a peça é publicada antes de a página explícita aparecer no
		// ledger. A fila não é esvaziada — a análise de uma folha que já está no ledger devolve o
		// veredito gravado —, e a varredura é a primeira a olhar o documento.
		conferir(paginas, dono.userId(), MediaSafetyVerdict.SAFE);
		semBanimentoAnterior(dono);

		mockMvc.perform(post("/me/portfolio/blocks")
			.header("X-Session-Token", dono.token())
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(bloco("PDF", pdf.id()))))
			.andExpect(status().isCreated());
		String username = publicar(dono);
		assertThat(countBlocks(username)).isEqualTo(1);
		conferir(paginas.subList(1, 2), dono.userId(), MediaSafetyVerdict.EXPLICIT);

		// A varredura relê o veredito já gravado da folha e, achando-a barrada e ainda no ar, faz o
		// takedown do documento inteiro.
		assertThat(safetySweep.sweep()).isPositive();
		esperarBarrado(username);

		// O documento some do portfólio: a peça publicada não aponta mais para ele.
		assertThat(countBlocks(username)).isZero();

		// E some do storage: o PDF e as páginas saem do ar na hora, e a limpeza de objeto fica pedida
		// para o worker de remoção — que a suíte não agenda, então o pedido é o que se prova aqui.
		assertThat(assets.findById(pdf.id()).orElseThrow().isVisible()).isFalse();
		assertThat(remocaoPedida(pdf.id())).as("PDF %s", pdf.id()).isTrue();
		for (MediaAsset pagina : paginas) {
			assertThat(assets.findById(pagina.id()).orElseThrow().isVisible()).as("página %s", pagina.id()).isFalse();
			assertThat(remocaoPedida(pagina.id())).as("página %s", pagina.id()).isTrue();
		}
	}

	@Test
	void pdfQueNaoAbreNaoTemPaginaEFicaBloqueado() throws Exception {
		var dono = auth.authenticate("pf_pdf_" + unico());
		// PDF de verdade que não abre sem senha: a limpeza de metadados não consegue separar a senha
		// do resto, então o arquivo é recusado na subida e nunca chega ao bucket.
		byte[] trancado = pdfDe(2, true);
		String criado = mockMvc.perform(post("/media/uploads")
				.header("X-Session-Token", dono.token())
				.header("Idempotency-Key", "pf-pdf-" + unico())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("fileName", "trancado.pdf", "mimeType",
					"application/pdf", "size", trancado.length, "purpose", "POST_FILE"))))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		String mediaId = objectMapper.readTree(criado).path("id").asText();
		mockMvc.perform(put("/storage/uploads/" + mediaId + "/file.pdf")
				.header("X-Session-Token", dono.token())
				.contentType("application/pdf")
				.content(trancado))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.code").value("MEDIA_PROCESSING_FAILED"));

		MediaAsset pdf = assets.findById(mediaId).orElseThrow();
		assertThat(memoria.has(pdf.objectKey())).isFalse();
		assertThat(esperarDesenho(pdf).folhas()).isEmpty();
		mockMvc.perform(post("/me/portfolio/blocks")
			.header("X-Session-Token", dono.token())
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(bloco("PDF", pdf.id()))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("PORTFOLIO_BLOCK_MEDIA_INVALID"));
	}

	@Test
	void pdfEmAnaliseNaoEAbreParaQuemNaoEODono() throws Exception {
		var dono = auth.authenticate("pf_pdf_" + unico());
		MediaAsset pdf = subirPdf(dono, 1);
		assertThat(esperarDesenho(pdf).estado()).isEqualTo("RENDERED");
		String url = "/storage/" + pdf.objectKey();

		// Quem não enviou não vê o documento antes de as páginas serem liberadas...
		mockMvc.perform(get(url)).andExpect(status().isNotFound());
		var outra = auth.authenticate("pf_pdf_" + unico());
		mockMvc.perform(get(url).header("X-Session-Token", outra.token())).andExpect(status().isNotFound());

		// ...mas o dono continua abrindo o próprio arquivo.
		mockMvc.perform(get(url).header("X-Session-Token", dono.token())).andExpect(status().isOk());
	}

	private static byte[] pdfDe(int paginas) {
		return pdfDe(paginas, false);
	}

	/**
	 * PDF de verdade, diferente a cada cenário: a limpeza de metadados reconhece o arquivo repetido e
	 * devolveria o da subida anterior, que já saiu do ar.
	 */
	private static byte[] pdfDe(int paginas, boolean trancado) {
		try {
			byte[] pdf = PdfBoxPageRendererTests.pdfCom(paginas, trancado);
			byte[] unico = Arrays.copyOf(pdf, pdf.length + 1);
			unico[pdf.length] = 0x0A;
			return unico;
		}
		catch (java.io.IOException impossivel) {
			throw new IllegalStateException(impossivel);
		}
	}

	private MediaAsset subirPdf(TestAuthHelper.AuthSession dono, int paginas) throws Exception {
		byte[] pdf = pdfDe(paginas);
		String criado = mockMvc.perform(post("/media/uploads")
				.header("X-Session-Token", dono.token())
				.header("Idempotency-Key", "pf-pdf-" + unico())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("fileName", "portfolio.pdf", "mimeType",
					"application/pdf", "size", pdf.length, "purpose", "POST_FILE"))))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		String mediaId = objectMapper.readTree(criado).path("id").asText();
		mockMvc.perform(put("/storage/uploads/" + mediaId + "/file.pdf")
				.header("X-Session-Token", dono.token())
				.contentType("application/pdf")
				.content(pdf))
			.andExpect(status().isOk());
		return assets.findById(mediaId).orElseThrow();
	}

	/**
	 * O desenho acabou de verdade, com as folhas na base e pedidas para análise. O pedido sai de
	 * PENDING antes das folhas, então o esperado é as duas coisas. As folhas que voltarem são as que o
	 * cenário viu; quem precisa delas depois usa o que esta espera achou.
	 */
	private Paginas esperarDesenho(MediaAsset pdf) throws InterruptedException {
		// A fila é vista uma vez por espera: resolvida antes do desenho terminar, ela não diz nada
		// sobre a análise que o fim do desenho pede, e a espera sairia antes de as folhas existirem.
		java.util.Set<String> naFilaAntes = analise.waitingNow();
		long limite = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
		String estado = null;
		List<MediaAsset> ultimas = List.of();
		while (System.nanoTime() < limite) {
			estado = jdbc.query("select status from media_render_jobs where media_id = ?",
				rs -> rs.next() ? rs.getString(1) : null, pdf.id());
			List<MediaAsset> paginas = paginasDaChave(pdf);
			if (paginas.size() > ultimas.size()) {
				ultimas = paginas;
			}
			boolean pedidas = paginas.stream().allMatch(pagina -> naFilaAntes.contains(pagina.id()));
			if ("RENDERED".equals(estado) && !paginas.isEmpty() && pedidas
					&& paginas.stream().noneMatch(pagina -> analise.waiting(pagina.id()))) {
				break;
			}
			Thread.sleep(50);
		}
		return new Paginas(estado, ultimas);
	}

	/** O que a espera do desenho viu: o estado do pedido e as folhas que ele achou. */
	private record Paginas(String estado, List<MediaAsset> folhas) {
	}

	/** Páginas já gravadas daquele PDF, em ordem de folha; vazio enquanto o render não as gravou. */
	private List<MediaAsset> paginasDaChave(MediaAsset pdf) {
		String keys = jdbc.query("select page_keys from media_render_jobs where media_id = ?",
			rs -> rs.next() ? rs.getString(1) : null, pdf.id());
		if (keys == null || keys.isBlank()) {
			return List.of();
		}
		List<MediaAsset> paginas = new ArrayList<>();
		for (String chave : Arrays.asList(keys.split(","))) {
			assets.findByObjectKey(chave).ifPresent(paginas::add);
		}
		paginas.sort(Comparator.comparing(MediaAsset::objectKey));
		return paginas;
	}

	/**
	 * A leitura pública guarda a publicação por alguns segundos: a peça sai da resposta depois disso,
	 * e a própria leitura pública é o que prova o efeito.
	 */
	private void esperarBarrado(String username) throws Exception {
		long limite = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
		while (System.nanoTime() < limite) {
			if (countBlocks(username) == 0) {
				return;
			}
			Thread.sleep(250);
		}
	}

	/** Publica a página e devolve o nome público; a leitura pública é quantas peças restaram. */
	private String publicar(TestAuthHelper.AuthSession dono) throws Exception {
		return objectMapper.readTree(mockMvc.perform(post("/me/portfolio/publish")
				.header("X-Session-Token", dono.token()))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString()).path("username").asText();
	}

	/**
	 * Quantas peças a leitura pública devolve. Portfólio que saiu do ar (dono banido, página retirada)
	 * responde ausente: zero peças, o mesmo que a peça barrada.
	 */
	private int countBlocks(String username) throws Exception {
		org.springframework.test.web.servlet.MvcResult resultado = mockMvc.perform(get("/portfolio/" + username))
			.andReturn();
		if (resultado.getResponse().getStatus() != 200) {
			return 0;
		}
		return objectMapper.readTree(resultado.getResponse().getContentAsString()).path("blocks").size();
	}

	/**
	 * A limpeza de objeto do worker de remoção já está pedida para esta mídia: o objeto ainda está no
	 * bucket (a suíte não agenda o worker), mas o caminho que o apaga foi aberto.
	 */
	private boolean remocaoPedida(String mediaId) {
		Integer pedidos = jdbc.queryForObject(
			"select count(*) from media_deletion_jobs where media_id = ? and status = 'REQUESTED'",
			Integer.class, mediaId);
		return pedidos != null && pedidos > 0;
	}

	/**
	 * O veredito entra na mesma transação que a gravação real: quando devolve, a varredura já enxerga a
	 * linha.
	 */
	private void conferir(List<MediaAsset> paginas, String owner, MediaSafetyVerdict verdict) {
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			for (MediaAsset page : paginas) {
				jdbc.update("""
					insert into media_safety_checks
						(media_id, owner_id, verdict, severity, confidence, reason, model, checked_at)
					values (?, ?, ?, 'LOW', 0.9, 'teste', 'stub', ?)
					on duplicate key update verdict = values(verdict)
					""", page.id(), owner, verdict.name(), java.sql.Timestamp.from(Instant.now()));
			}
		});
	}

	/** Encerra banimento ativo da conta do cenário, deixado por uma execução anterior. */
	private void semBanimentoAnterior(TestAuthHelper.AuthSession dono) {
		jdbc.update("""
			update user_sanctions
			set status = 'REMOVED', removed_at = current_timestamp, removed_by = 'teste'
			where user_id = ? and status = 'ACTIVE' and type in ('BAN', 'PERMABAN')
			""", dono.userId());
	}

	private static Map<String, Object> bloco(String tipo, String mediaId) {
		Map<String, Object> body = new HashMap<>();
		body.put("type", tipo);
		body.put("mediaId", mediaId);
		return body;
	}

	private static String unico() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}
}
