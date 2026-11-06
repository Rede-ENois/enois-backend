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
 * <p>A ordem é: `rede.media.temp-dir` (quando configurado), a pasta de dados da aplicação (o volume
 * gravável em produção) e, por último, o `java.io.tmpdir` do processo. A primeira que aceitar a
 * criação do arquivo vence; se nenhuma aceitar, o erro diz quais foram tentadas.</p>
 *
 * <p>O `java.io.tmpdir` é lido do sistema a cada chamada, e não por `@Value`: o valor do processo
 * pode mudar em tempo de execução, e um placeholder que não resolve derrubaria o contexto inteiro.</p>
 */
@Component
public class MediaTemporaryFiles {

	private static final Logger LOG = LoggerFactory.getLogger(MediaTemporaryFiles.class);

	/** Pasta de dados da aplicação: o volume gravável do pod (o mesmo que guarda o modelo ONNX). */
	static final String DEFAULT_DATA_DIR = "/app/data";

	private final String configuredDir;
	private final String dataDir;

	public MediaTemporaryFiles(@Value("${rede.media.temp-dir:}") String configuredDir) {
		this(configuredDir, DEFAULT_DATA_DIR);
	}

	MediaTemporaryFiles(String configuredDir, String dataDir) {
		this.configuredDir = configuredDir == null ? "" : configuredDir.trim();
		this.dataDir = dataDir == null ? "" : dataDir.trim();
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
				// `File` (e não `Path.of`) tolera caminho relativo, como o `createTempFile` do JDK.
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
		String tmp = System.getProperty("java.io.tmpdir");
		if (tmp == null || tmp.isBlank()) {
			tmp = "/tmp";
		}
		if (!dirs.contains(tmp)) {
			dirs.add(tmp);
		}
		return dirs;
	}
}
