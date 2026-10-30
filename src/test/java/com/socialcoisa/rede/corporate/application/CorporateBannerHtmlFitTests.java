package com.socialcoisa.rede.corporate.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * O corte da capa em HTML para caber no limite do perfil profissional. HTML é markup: cortar no
 * meio de uma tag faz o navegador continuar lendo o atributo e o resto da capa aparece como
 * texto na tela, então o corte cai no fim de uma tag.
 */
class CorporateBannerHtmlFitTests {

	@Test
	void textoQueJaCabePassaInteiro() {
		assertThat(CorporateProfileService.fitHtml("  <div>capa</div>  ", 20000))
			.isEqualTo("<div>capa</div>");
	}

	@Test
	void vazioViraNulo() {
		assertThat(CorporateProfileService.fitHtml(null, 20000)).isNull();
		assertThat(CorporateProfileService.fitHtml("   ", 20000)).isNull();
	}

	@Test
	void corteCaiNoFimDeUmaTag() {
		String bloco = "<div class=\"faixa\">conteudo</div>";
		String grande = bloco.repeat(20);

		String cortado = CorporateProfileService.fitHtml(grande, 100);

		assertThat(cortado).isNotNull();
		assertThat(cortado.length()).isLessThanOrEqualTo(100);
		assertThat(cortado).endsWith("</div>");
	}

	@Test
	void quandoNenhumaTagFechaDentroDoLimiteOCampoFicaVazio() {
		assertThat(CorporateProfileService.fitHtml("<b>capa longa</b>", 5)).isEqualTo("<b>");
		assertThat(CorporateProfileService.fitHtml("<div class=\"faixa\">capa</div>", 5)).isNull();
		assertThat(CorporateProfileService.fitHtml("texto solto sem tag", 5)).isNull();
	}
}