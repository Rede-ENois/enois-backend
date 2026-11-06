package com.socialcoisa.rede.media.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * O temporário do vídeo grande não pode depender do `/tmp` da imagem: o pod só tem a pasta de dados
 * gravável, e um `createTempFile` recusado virava "erro interno inesperado" na publicação.
 */
class MediaTemporaryFilesTests {

	private final String tmpAnterior = System.getProperty("java.io.tmpdir");

	@AfterEach
	void restaurarTmp() {
		if (tmpAnterior == null) {
			System.clearProperty("java.io.tmpdir");
		}
		else {
			System.setProperty("java.io.tmpdir", tmpAnterior);
		}
	}

	@Test
	void gravaNaPastaDeDadosMesmoComOTmpDoProcessoInutilizavel() throws Exception {
		Path dados = Files.createTempDirectory("dados-do-pod-");
		Path semTmp = Files.createTempDirectory("pasta-sem-tmp-");
		Files.createDirectory(semTmp.resolve("arquivo"));
		System.setProperty("java.io.tmpdir", semTmp.resolve("arquivo").resolve("nao-existe").toString());

		var arquivos = new MediaTemporaryFiles("", dados.toString());
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

		var arquivos = new MediaTemporaryFiles(configurada.toString(), dados.toString());
		Path criado = arquivos.create("media-clean-", ".mp4");
		try {
			assertThat(criado.getParent()).isEqualTo(configurada);
		}
		finally {
			Files.deleteIfExists(criado);
		}
	}

	@Test
	void semNenhumaPastaGravavelOErroDizQuaisForamTentadas() throws Exception {
		// O último recurso é o `java.io.tmpdir`, que aqui vai para um arquivo comum (não `os.devNull`:
		// escrever em `/dev/null` funciona, e o teste passaria pelo motivo errado).
		Path semTmp = Files.createTempFile("tmp-que-nao-e-pasta-", ".txt");
		System.setProperty("java.io.tmpdir", semTmp.toString());
		var arquivos = new MediaTemporaryFiles("/dev/null/nao-e-pasta", "/dev/null/nao-e-monitoramento");
		try {
			assertThatThrownBy(() -> arquivos.create("media-raw-", ".mp4"))
				.isInstanceOf(MediaTemporaryFiles.UnavailableException.class)
				.hasMessageContaining("/dev/null/nao-e-pasta")
				.hasMessageContaining(semTmp.toString());
		}
		finally {
			Files.deleteIfExists(semTmp);
		}
	}
}
