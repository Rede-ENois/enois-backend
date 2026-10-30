package com.socialcoisa.rede.moderation.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;

import org.junit.jupiter.api.Test;

class LinkPolicyTest {

	@Test
	void reconheceHostDePlataformaAdulta() {
		assertThat(LinkPolicy.isKnownAdultHost(URI.create("https://www.pornhub.com/view_video.php?id=1"))).isTrue();
		assertThat(LinkPolicy.isKnownAdultHost(URI.create("https://xvideos.com/video123"))).isTrue();
		assertThat(LinkPolicy.isKnownAdultHost(URI.create("https://pt.xvideos.com/video123"))).isTrue();
		assertThat(LinkPolicy.isKnownAdultHost(URI.create("https://onlyfans.com/algum_perfil"))).isTrue();
	}

	@Test
	void naoConfundeHostParecidoComAdulto() {
		// Só o próprio domínio ou subdomínio dele; "naopornhub.com" não é pornhub.com.
		assertThat(LinkPolicy.isKnownAdultHost(URI.create("https://naopornhub.com/x"))).isFalse();
		assertThat(LinkPolicy.isKnownAdultHost(URI.create("https://youtube.com/watch?v=abc"))).isFalse();
		assertThat(LinkPolicy.isKnownAdultHost(URI.create("https://enois.ia.br/blog"))).isFalse();
	}

	@Test
	void palavraSexualNoCaminhoESoParaSinalizar() {
		assertThat(LinkPolicy.hasSexualToken(URI.create("https://exemplo.com/porno-caseiro"))).isTrue();
		assertThat(LinkPolicy.hasSexualToken(URI.create("https://exemplo.com/video/xxx"))).isTrue();
		assertThat(LinkPolicy.hasSexualToken(URI.create("https://exemplo.com/onlyfans-da-beltrana"))).isTrue();
		// Materia sobre educacao sexual também casa o termo — por isso isto é só suspeita, não punição.
		assertThat(LinkPolicy.hasSexualToken(URI.create("https://jornal.com/educacao-sexual-nas-escolas"))).isTrue();
	}

	@Test
	void caminhoInnocuoNaoSinaliza() {
		assertThat(LinkPolicy.hasSexualToken(URI.create("https://youtube.com/watch?v=abc123"))).isFalse();
		assertThat(LinkPolicy.hasSexualToken(URI.create("https://github.com/melmonfre/rede"))).isFalse();
	}

	@Test
	void reconheceEncurtador() {
		assertThat(LinkPolicy.isShortener(URI.create("https://bit.ly/abc"))).isTrue();
		assertThat(LinkPolicy.isShortener(URI.create("https://youtube.com/watch?v=abc"))).isFalse();
	}

}
