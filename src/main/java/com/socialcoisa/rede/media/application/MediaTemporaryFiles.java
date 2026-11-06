package com.socialcoisa.rede.media.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Onde a limpeza de metadados grava o arquivo temporário de vídeo/áudio grande.
 *
 * <p>`Files.createTempFile` sozinho usa `java.io.tmpdir`, que no pod é o `/tmp` da imagem: um
 * diretório que pode não existir, não ser gravável ou não ter espaço para um vídeo de centenas de
 * MB. Quando isso acontece o upload morre com erro genérico, sem dizer que o problema é o
 * temporário.</p>
 *
 * <p>A ordem é: `rede.media.temp-dir` (quando configurado), a pasta de dados do pod (a mesma do
 * monitoramento, que já é gravável em produção) e, por último, o `java.io.tmpdir` do processo, que
 * entra como `File(System.getProperty("java.io.tmpdir", "/tmp"))` — `Path.of` (e `Files.*`) recusam
 * caminho relativo, e o `createTempFile` do JDK trata valor ausente como `/tmp`. A primeira pasta
 * que aceitar a criação do arquivo vence; se nenhuma aceitar, o erro diz quais foram tentadas.</p>
 */
@Component
public class MediaTemporaryFiles {

	private static final Logger LOG = LoggerFactory.getLogger(MediaTemporaryFiles.class);

	private final String configuredDir;
	private final String dataDir;

	public MediaTemporaryFiles(@Value("${rede.media.temp-dir:}") String configuredDir,
			@Value("${rede.monitoring.file-dir:/app/data/monitoring}") String monitoringDir) {
		this.configuredDir = configuredDir == null ? "" : configuredDir.trim();
		this.dataDir = parentOf(monitoringDir);
	}

	/**
	 * Nenhum diretório aceitou o arquivo. É separado de um `IOException` qualquer porque a causa não
	 * é o arquivo que a pessoa enviou: é o disco do servidor, e a resposta não pode ser "arquivo
	 * inválido".
	 */
	public static class UnavailableException extends IOException {

		UnavailableException(String message, Throwable cause) {
			super(message, cause);
		}
	}

	/** Cria o temporário no primeiro diretório gravável; o chamador é dono do arquivo. */
	public Path create(String prefix, String suffix) throws IOException {
		List<String> tentados = new ArrayList<>();
		IOException ultima = null;
		for (String candidato : candidates()) {
			tentados.add(candidato);
			try {
				// Mantém a mesma tolerância do `createTempFile` do JDK: valor ausente cai em `/tmp`.
				Path dir = new java.io.File(candidato).toPath();
				Files.createDirectories(dir);
				return Files.createTempFile(dir, prefix, suffix);
			}
			catch (IOException | RuntimeException failure) {
				ultima = failure instanceof IOException io ? io
					: new IOException(failure.getClass().getSimpleName(), failure);
				LOG.warn("media_temp_dir_unusable dir={} error={}", candidato, failure.getClass().getSimpleName());
			}
		}
		throw new UnavailableException("Nenhum diretorio temporario gravavel: " + String.join(", ", tentados),
			ultima);
	}

	private List<String> candidates() {
		List<String> dirs = new ArrayList<>();
		if (!configuredDir.isEmpty()) {
			dirs.add(configuredDir);
		}
		if (!dataDir.isEmpty() && !dirs.contains(dataDir)) {
			dirs.add(dataDir);
		}
		// Lido a cada chamada: o valor do processo pode mudar (e o teste aponta para um caminho ruim).
		String tmp = System.getProperty("java.io.tmpdir");
		if (tmp == null || tmp.isBlank()) {
			tmp = "/tmp";
		}
		if (!dirs.contains(tmp)) {
			dirs.add(tmp);
		}
		return dirs;
	}

	private static String parentOf(String monitoringDir) {
		if (monitoringDir == null || monitoringDir.isBlank()) {
			return "";
		}
		Path parent = new java.io.File(monitoringDir.trim()).toPath().getParent();
		return parent == null ? "" : parent.toString();
	}
}
