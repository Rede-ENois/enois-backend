package com.socialcoisa.rede.termometro.adapters.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.termometro.application.port.out.ManifestacaoReader;
import com.socialcoisa.rede.termometro.application.port.out.TermometroRepository.Fatia;

/**
 * A gravação das fatias contra o banco de verdade (H2 + Flyway): o upsert da chave natural e a
 * adoção da linha gravada antes da coluna `publicacao_id`.
 */
@SpringBootTest
@Transactional
class TermometroPersistenceAdapterTests {

	private static final Instant HORA = Instant.parse("2026-11-20T10:00:00Z");

	@Autowired
	private TermometroPersistenceAdapter adaptador;

	@Autowired
	private SpringDataTermometroFatiaJpaRepository fatias;

	@Test
	void relerAPublicacaoAtualizaAFatiaEmVezDeCriarOutra() {
		adaptador.salvarFatias(List.of(new Fatia(HORA, "POST", "CELEBRANDO", "autor_1", "post_1",
				HORA.plusSeconds(60))));

		adaptador.salvarFatias(List.of(new Fatia(HORA, "POST", "CELEBRANDO", "autor_1", "post_1",
				HORA.plusSeconds(3600))));

		assertThat(fatias.fatiasEntre(HORA, HORA.plusSeconds(3600))).hasSize(1);
		assertThat(adaptador.leituraDaFatia("POST", "post_1")).contains(HORA.plusSeconds(3600));
	}

	@Test
	void releituraAdotaALinhaGravadaAntesDaColuna() {
		fatias.save(new TermometroFatiaJpaEntity("termo_f_legado", HORA, "POST", "CELEBRANDO", "autor_1", null,
				HORA));

		adaptador.salvarFatias(List.of(new Fatia(HORA, "POST", "CELEBRANDO", "autor_1", "post_1",
				HORA.plusSeconds(60))));

		List<TermometroFatiaJpaEntity> linhas = fatias.fatiasEntre(HORA, HORA.plusSeconds(3600));
		assertThat(linhas).singleElement().satisfies(linha -> {
			assertThat(linha.id()).isEqualTo("termo_f_legado");
			assertThat(linha.publicacaoId()).isEqualTo("post_1");
		});
		assertThat(adaptador.leituraDaFatia("POST", "post_1")).contains(HORA.plusSeconds(60));
	}

	@Test
	void publicacaoRelidaSemTextoPerdeAFatia() {
		adaptador.salvarFatias(List.of(new Fatia(HORA, "POST", "RAIVA", "autor_1", "post_1",
				HORA.plusSeconds(60))));

		adaptador.removerFatiasDaJanela(HORA.minusSeconds(3600), HORA.plusSeconds(3600),
				List.of(new ManifestacaoReader.Manifestacao("", "autor_1", HORA, "post_1")));

		assertThat(fatias.fatiasEntre(HORA.minusSeconds(3600), HORA.plusSeconds(3600))).isEmpty();
	}

	@Test
	void publicacaoRelidaComTextoMantemAFatia() {
		adaptador.salvarFatias(List.of(new Fatia(HORA, "POST", "RAIVA", "autor_1", "post_1",
				HORA.plusSeconds(60))));

		adaptador.removerFatiasDaJanela(HORA.minusSeconds(3600), HORA.plusSeconds(3600),
				List.of(new ManifestacaoReader.Manifestacao("que raiva", "autor_1", HORA, "post_1")));

		assertThat(fatias.fatiasEntre(HORA.minusSeconds(3600), HORA.plusSeconds(3600))).hasSize(1);
	}

}
