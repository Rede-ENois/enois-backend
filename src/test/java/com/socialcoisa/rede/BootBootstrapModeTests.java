package com.socialcoisa.rede;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.repository.config.BootstrapMode;

/**
 * O valor de `spring.data.jpa.repositories.bootstrap-mode` e um enumerado: texto que nao existe no
 * `BootstrapMode` nao cai em nenhum default — vai direto para `Enum.valueOf` e derruba o contexto no
 * boot. Foi o que aconteceu na QA de 2026-11-26 com `none`:
 *
 * <pre>BeanDefinitionStoreException: Failed to process import candidates for configuration class
 * [DataJpaRepositoriesAutoConfiguration]: No enum constant ...BootstrapMode.NONE</pre>
 *
 * <p>O build nao pegava porque propriedade de arquivo nao e checada por compilador, e o
 * `RedeApplicationTests` usa o `application.properties` do <em>teste</em>, que nao tem a linha. Este
 * teste le o arquivo de producao e confere o valor contra o enum de verdade — trocar por um nome
 * invalido falha aqui, antes do deploy.
 */
@Tag("critico")
class BootBootstrapModeTests {

	private static final String CHAVE = "spring.data.jpa.repositories.bootstrap-mode";

	@Test
	void oBootstrapModeDeclaradoExisteNoEnum() throws IOException {
		String valor = valorDeclarado();
		assertThat(valor)
			.as(CHAVE + " tem de ser um dos " + Arrays.toString(BootstrapMode.values()))
			.isNotBlank();
		assertThat(Arrays.toString(BootstrapMode.values()))
			.as("'%s' tem de existir no BootstrapMode; valor invalido derruba o boot", valor)
			.contains(valor.toUpperCase(java.util.Locale.ROOT));
	}

	/**
	 * O que moradia em `deferred` travava o boot de producao (2026-11-26): os repositorios eram
	 * criados no evento de `finishRefresh`, depois de o Tomcat aceitar conexao. Deixado como
	 * regressao explicita para a troca nao voltar sem querer.
	 */
	@Test
	void oBootstrapModeNaoEDeferred() throws IOException {
		assertThat(valorDeclarado()).isNotEqualToIgnoringCase("deferred");
	}

	private static String valorDeclarado() throws IOException {
		Path arquivo = Path.of("src", "main", "resources", "application.properties");
		return Files.readAllLines(arquivo).stream()
			.map(String::trim)
			.filter(linha -> linha.startsWith(CHAVE + "="))
			.map(linha -> linha.substring(CHAVE.length() + 1).trim())
			.findFirst()
			.orElseThrow(() -> new AssertionError(CHAVE + " nao declarado em " + arquivo));
	}
}
