package com.socialcoisa.rede.media.adapters.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.media.application.port.out.MediaAssetRepository;
import com.socialcoisa.rede.media.application.port.out.MediaPageCatalog;
import com.socialcoisa.rede.media.application.port.out.MediaPageCatalog.PageRef;
import com.socialcoisa.rede.media.application.port.out.MediaPageSets;
import com.socialcoisa.rede.media.domain.MediaAsset;
import com.socialcoisa.rede.media.domain.MediaStorageProvider;
import com.socialcoisa.rede.moderation.application.SafetyPorts;
import com.socialcoisa.rede.moderation.domain.MediaSafetyVerdict;

/**
 * A ponte entre páginas renderizadas e o ledger de conteúdo, contra o banco de verdade: é o que
 * faz o PDF do portfólio só abrir depois de cada folha ter sido vista — e ficar fechado quando
 * alguma não foi.
 */
@SpringBootTest
class MediaPageSafetyTests {

	private static final String PDF_MIME = "application/pdf";

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private MediaAssetRepository assets;

	@Autowired
	private MediaPageCatalog pages;

	@Autowired
	private MediaPageSets pageSets;

	@Autowired
	private SafetyPorts.SafetyLedger ledger;

	@Autowired
	private SafetyPorts.DocumentPieces retirement;

	@Test
	void paginaSemVereditoMantemOPdfFechadoEAposOParecerSafelicbera() {
		String owner = "user_paginas_" + shortId();
		MediaAsset pdf = seedPdf(assets, owner, "curriculo.pdf");
		List<MediaAsset> rendered = seedRenderedPages(assets, pdf, 2);

		assertThat(pages.pagesOf(pdf.id())).extracting(PageRef::mediaId)
			.containsExactlyElementsOf(rendered.stream().map(MediaAsset::id).toList());
		assertThat(pageSets.uncheckedOf(pdf.id())).hasSize(2);

		check(rendered.get(0), owner, MediaSafetyVerdict.SAFE);
		assertThat(pageSets.uncheckedOf(pdf.id())).containsExactly(rendered.get(1).id());

		check(rendered.get(1), owner, MediaSafetyVerdict.SAFE);
		assertThat(pageSets.uncheckedOf(pdf.id())).isEmpty();
	}

	@Test
	void paginaIncertaNuncaLibera() {
		String owner = "user_paginas_" + shortId();
		MediaAsset pdf = seedPdf(assets, owner, "portfolio.pdf");
		List<MediaAsset> rendered = seedRenderedPages(assets, pdf, 2);

		check(rendered.get(0), owner, MediaSafetyVerdict.SAFE);
		check(rendered.get(1), owner, MediaSafetyVerdict.UNSURE);

		assertThat(pageSets.uncheckedOf(pdf.id())).containsExactly(rendered.get(1).id());
	}

	@Test
	void pdfSemRenderNaoTemPaginaParaLiberar() {
		String owner = "user_paginas_" + shortId();
		MediaAsset pdf = seedPdf(assets, owner, "sem-render.pdf");

		assertThat(pages.pagesOf(pdf.id())).isEmpty();
		assertThat(pageSets.uncheckedOf(pdf.id())).containsExactly(pdf.id());
	}

	@Test
	void imagemContinuaSendoSuaPropriaPagina() {
		String owner = "user_paginas_" + shortId();
		MediaAsset foto = asset("image/png", owner, "uploads/x/foto.png");
		assets.save(foto);

		assertThat(pages.pagesOf(foto.id())).containsExactly(new PageRef(foto.id(), "image/png"));
		assertThat(pageSets.uncheckedOf(foto.id())).containsExactly(foto.id());
	}

	@Test
	void paginasSemChecagemAparecemNaVarreduraComOIdadeDoAsset() {
		String owner = "user_paginas_" + shortId();
		MediaAsset pdf = seedPdf(assets, owner, "varredura.pdf");
		MediaAsset page = seedRenderedPages(assets, pdf, 1).get(0);

		// A varredura pergunta por PÁGINA, não pelo documento: é a página que o classificador olha.
		assertThat(ledger.findUncheckedPieces(5000)).contains(page.id());
		// E só uma vez por documento: o check da página já decide o documento inteiro.
		assertThat(ledger.findUncheckedPieces(5000).stream().filter(page.id()::equals)).hasSize(1);

		check(page, owner, MediaSafetyVerdict.SAFE);
		assertThat(ledger.findUncheckedPieces(5000)).doesNotContain(page.id());
	}

	@Test
	void paginaVistaNaoVoltaNaVarredura() {
		String owner = "user_paginas_" + shortId();
		MediaAsset pdf = seedPdf(assets, owner, "barrado.pdf");
		List<MediaAsset> paginas = seedRenderedPages(assets, pdf, 2);
		// Só a primeira folha tem veredito: a última ainda não foi olhada.
		check(paginas.get(0), owner, MediaSafetyVerdict.SAFE);

		List<String> varredura = ledger.findUncheckedPieces(5000);

		// Folha vista não volta; a que falta olhar volta pelo documento (é ela que decide o PDF).
		assertThat(varredura).doesNotContain(paginas.get(0).id());
		assertThat(varredura).contains(paginas.get(1).id());
	}

	@Test
	void documentoRetiradoLevaAsFolhasJuntoNaRetirada() {
		String owner = "user_paginas_" + shortId();
		MediaAsset pdf = seedPdf(assets, owner, "retirado.pdf");
		List<MediaAsset> paginas = seedRenderedPages(assets, pdf, 2);

		retirement.retire(pdf.id());

		// Documento e folhas saem do ar no mesmo pedido: quem apaga o objeto é o worker de remoção.
		assertThat(assets.findById(pdf.id()).orElseThrow().isVisible()).isFalse();
		for (MediaAsset pagina : paginas) {
			assertThat(assets.findById(pagina.id()).orElseThrow().isVisible()).as("página %s", pagina.id()).isFalse();
		}
	}

	/**
	 * O ledger é histórico de auditoria e não se apaga em produção. A base da suíte é compartilhada,
	 * e a folha barrada de um cenário arrastaria a varredura do seguinte para uma mídia alheia.
	 */
	@AfterEach
	void limparViolacoesDosCenarios() {
		jdbc.update("delete from media_safety_checks where verdict in ('EXPLICIT','SEXUAL_MINOR')");
	}

	private void check(MediaAsset page, String owner, MediaSafetyVerdict verdict) {
		jdbc.update("""
			insert into media_safety_checks
				(media_id, owner_id, verdict, severity, confidence, reason, model, checked_at)
			values (?, ?, ?, 'LOW', 0.9, 'teste', 'stub', ?)
			on duplicate key update verdict = values(verdict)
			""", page.id(), owner, verdict.name(), java.sql.Timestamp.from(Instant.now()));
	}

	private static MediaAsset seedPdf(MediaAssetRepository repository, String owner, String name) {
		return repository.save(asset(PDF_MIME, owner, "uploads/media_" + shortId() + "/file.pdf"));
	}

	private List<MediaAsset> seedRenderedPages(MediaAssetRepository repository, MediaAsset pdf, int howMany) {
		String batch = "media_" + shortId();
		// A chave guarda o lote e o número: é por ela que o catálogo acha a página do documento.
		// O lote da página é o diretório da chave (`paginas/<lote>/pagina-<n>.png`): é assim que a
		// varredura liga a folha ao pedido que a gravou.
		List<MediaAsset> folhas = new java.util.ArrayList<>();
		for (int page = 1; page <= howMany; page++) {
			String key = "paginas/" + batch + "/pagina-" + page + ".png";
			folhas.add(repository.save(MediaAsset.pending(batch + "_pagina-" + page, new UserId(pdf.ownerId().value()),
				"pagina", "image/png", 100, null, key, "https://cdn.exemplo.com/" + key, Instant.now(),
				Instant.now().plusSeconds(3600), MediaStorageProvider.S3)));
		}
		jdbc.update("""
			insert into media_render_jobs
				(media_id, owner_id, status, attempt, pages, page_keys, created_at, updated_at)
			values (?, ?, 'RENDERED', 1, ?, ?, ?, ?)
			""", pdf.id(), pdf.ownerId().value(), howMany,
			folhas.stream().map(MediaAsset::objectKey).collect(java.util.stream.Collectors.joining(",")),
			java.sql.Timestamp.from(Instant.now()), java.sql.Timestamp.from(Instant.now()));
		return folhas;
	}

	private static MediaAsset asset(String mime, String owner, String objectKey) {
		return MediaAsset.pending("media_" + shortId(), new UserId(owner), "arquivo", mime, 100, null, objectKey,
			"https://cdn.exemplo.com/" + objectKey, Instant.now(), Instant.now().plusSeconds(3600),
			MediaStorageProvider.S3);
	}

	private static String shortId() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
	}
}
