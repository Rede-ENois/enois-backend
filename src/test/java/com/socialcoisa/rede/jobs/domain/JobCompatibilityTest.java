package com.socialcoisa.rede.jobs.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regras do score: palavra inteira (java nao pega javascript), remote vale localidade cheia,
 * cidade pesa mais que estado, sinal ausente redistribui peso e zero sinal = 0 (nao exibido).
 */
class JobCompatibilityTest {

	private final JobCompatibility perfil = JobCompatibility.of(
		List.of("Java", "Spring"), "São Paulo", "SP");

	@Test
	void vagaComSkillsListadasContaProporcaoDasQueBatem() {
		// 1 de 2 batem = 30 + cidade cheia = 40
		assertThat(perfil.scoreOf(List.of("Java", "Python"), "vaga de backend",
				"São Paulo", "SP", "HYBRID")).isEqualTo(70);
		// Todas batem + cidade => 100
		assertThat(perfil.scoreOf(List.of("java", "spring boot"), "backend",
				"São Paulo", "SP", "ONSITE")).isEqualTo(100);
		// Nenhuma bate: skills zeram, mas remoto serve de qualquer lugar => 40
		assertThat(perfil.scoreOf(List.of("Python", "Go"), "backend", null, null, "REMOTE")).isEqualTo(40);
	}

	@Test
	void matchDeSkillEhPalavraInteira() {
		// Pelo TEXTO: "java" dentro de "javascript" NAO conta, "spring" dentro de "springfield" tambem nao
		assertThat(perfil.scoreOf(List.of(), "vagas de javascript", null, null, null)).isZero();
		assertThat(perfil.scoreOf(List.of(), "cidade Springfield", null, null, null)).isZero();
		// Controle positivo: a mesma rota de texto casa com a palavra inteira (30 de skills + 20 do
		// estado SP vindo da localidade, já que sem workModel a comparação de lugar segue ativa)
		assertThat(perfil.scoreOf(List.of(), "vagas para java", "Curitiba", "SP", null)).isEqualTo(50);
	}

	@Test
	void textoDaVagaSubstituiSkillsQuandoAFonteNaoLista() {
		// 1 das 2 habilidades do perfil achada no anuncio = metade do peso de skills = 30 + remoto 40
		assertThat(perfil.scoreOf(List.of(), "precisamos de java e sql", null, null, "REMOTE"))
			.isEqualTo(70);
	}

	@Test
	void localidadePorPalavraInteiraComCidadePesandoMaisQueEstado() {
		// Skill listada e batendo vale 60 fixos; a localidade decide o resto.
		// Nada bate: 60. So a UF: 60 + 0.5*40 = 80. Cidade: 60 + 40 = 100. Lugar distante: 60.
		assertThat(perfil.scoreOf(List.of("Java"), "vaga", "Curitiba", "PR", "ONSITE")).isEqualTo(60);
		assertThat(perfil.scoreOf(List.of("Java"), "vaga", "Curitiba", "SP", "ONSITE")).isEqualTo(80);
		assertThat(perfil.scoreOf(List.of("Java"), "vaga", "São Paulo", "SP", "ONSITE")).isEqualTo(100);
		assertThat(perfil.scoreOf(List.of("Java"), "vaga", "Recife", "PE", "ONSITE")).isEqualTo(60);
	}

	@Test
	void remotoIgnoraLocalidadeDaVaga() {
		// Skill bate + remoto => 100, mesmo com a vaga "em Recife"
		assertThat(perfil.scoreOf(List.of("Java"), "backend", "Recife", "PE", "REMOTE")).isEqualTo(100);
		// Skills de outro mundo: o remoto ainda vale os 40 de localidade
		assertThat(perfil.scoreOf(List.of("Go"), "backend", "Recife", "PE", "REMOTE")).isEqualTo(40);
	}

	@Test
	void semSinalNosDoisLadosScoreZero() {
		assertThat(JobCompatibility.of(List.of(), null, null)
			.scoreOf(List.of("Java"), "java spring", "São Paulo", "SP", "ONSITE")).isZero();
		assertThat(perfil.scoreOf(List.of(), "", null, null, null)).isZero();
	}

	@Test
	void acentoECaseNaoImportam() {
		var perfil = JobCompatibility.of(List.of("Débito"), "São Paulo", "SP");
		assertThat(perfil.scoreOf(List.of("debito"), "vaga debitó", "sao paulo", "sp", "ONSITE"))
			.isEqualTo(100);
	}
}
