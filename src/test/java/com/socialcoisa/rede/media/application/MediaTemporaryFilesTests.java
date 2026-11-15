package com.socialcoisa.rede.media.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * O temporário do vídeo grande não pode depender do `/tmp` da imagem: o pod só tem a pasta de dados
 * gravável, e um `createTempFile` recusado virava "erro interno inesperado" na publicação.
 *
 * <p>As pastas entram pelo construtor de pacote — nada aqui mexe em `java.io.tmpdir`, que é estado
 * global da JVM e derrubava os outros `@SpringBootTest` da suíte.</p>
 */
class MediaTemporaryFilesTests {

	@TempDir
	Path dir;

	@Test
	void gravaNaPastaDeDadosQuandoOTmpDoProcessoNaoServe() throws Exception {
		Path dados = Files.createTempDirectory("dados-do-pod-");
		Path semTmp = Files.createTempDirectory("pasta-sem-tmp-");
		Files.createDirectory(semTmp.resolve("arquivo"));

		var arquivos = new MediaTemporaryFiles("", dados.toString(),
			semTmp.resolve("arquivo").resolve("nao-existe").toString(), null);
		Path criado = arquivos.create("media-raw-", ".mp4");
		try {
			assertThat(criado.getParent()).isEqualTo(dados);
			Files.write(criado, new byte[] { 1 });
			assertThat(Files.size(criado)).isEqualTo(1);
		}
		finally {
			Files.deleteIfExists(criado);
		}
	}

	@Test
	void pastaConfiguradaVemAntesDaPastaDeDados() throws Exception {
		Path configurada = Files.createTempDirectory("temp-de-midia-");
		Path dados = Files.createTempDirectory("dados-do-pod-");

		var arquivos = new MediaTemporaryFiles(configurada.toString(), dados.toString(), null, null);
		Path criado = arquivos.create("media-clean-", ".mp4");
		try {
			assertThat(criado.getParent()).isEqualTo(configurada);
		}
		finally {
			Files.deleteIfExists(criado);
		}
	}

	@Test
	void pastaConfiguradaQueNaoExisteNaoEhCriada() throws Exception {
		Path dados = Files.createTempDirectory("dados-do-pod-");
		Path inexistente = dados.resolve("nao-existe");

		var arquivos = new MediaTemporaryFiles(inexistente.toString(), dados.toString(), null, null);
		Path criado = arquivos.create("media-raw-", ".mp4");
		try {
			// Um caminho errado de deploy não vira volume novo: cai para a pasta de dados.
			assertThat(criado.getParent()).isEqualTo(dados);
			assertThat(inexistente).doesNotExist();
		}
		finally {
			Files.deleteIfExists(criado);
		}
	}

	@Test
	void semNenhumaPastaGravavelOErroDizQuaisForamTentadas() throws Exception {
		Path semTmp = Files.createFile(dir.resolve("arquivo-nao-e-pasta"));
		String configurada = semTmp.resolve("configurada").toString();
		String dados = semTmp.resolve("dados").toString();
		var arquivos = new MediaTemporaryFiles(configurada, dados, semTmp.toString(), null);
		try {
			assertThatThrownBy(() -> arquivos.create("media-raw-", ".mp4"))
				.isInstanceOf(MediaTemporaryFiles.UnavailableException.class)
				.hasMessageContaining(configurada)
				.hasMessageContaining(semTmp.toString())
				.satisfies(failure -> assertThat(((MediaTemporaryFiles.UnavailableException) failure).dirs())
					.containsExactly(configurada, dados, semTmp.toString()));
		}
		finally {
			Files.deleteIfExists(semTmp);
		}
	}
}
