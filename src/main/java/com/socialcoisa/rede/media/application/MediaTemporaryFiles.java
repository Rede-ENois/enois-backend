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
 * <p>`Files.createTempFile` sozinho usa `java.io.tmpdir`, que pode ser um diretório que não existe,
 * não é gravável ou não tem espaço para um vídeo de centenas de MB. Quando isso acontece o upload
 * morre com erro genérico, sem dizer que o problema é o temporário.</p>
 *
 * <p>A ordem é: `rede.media.temp-dir` (quando configurado — ele precisa **existir**, para que um erro
 * de deploy não vire uma pasta nova dentro do container), a pasta de dados da aplicação e, por
 * último, o `java.io.tmpdir` do processo. As pastas são resolvidas uma vez, na criação do bean; a
 * primeira que aceitar o arquivo vence. Se nenhuma aceitar, o erro diz quais foram tentadas.</p>
 */
@Component
public class MediaTemporaryFiles {

	private static final Logger LOG = LoggerFactory.getLogger(MediaTemporaryFiles.class);

	/** Pasta de dados da aplicação: o volume gravável do pod (o mesmo que guarda o modelo ONNX). */
	static final String DEFAULT_DATA_DIR = "/app/data";

	private final String configuredDir;
	private final List<String> candidates;

	public MediaTemporaryFiles(@Value("${rede.media.temp-dir:}") String configuredDir) {
		this(configuredDir, DEFAULT_DATA_DIR, System.getProperty("java.io.tmpdir"), System.getProperty("user.dir"));
	}

	/**
	 * Monta o resolvedor com as pastas explícitas, sem ler o sistema. É o que os testes usam para
	 * exercitar cada caso sem mexer em `java.io.tmpdir`, que é estado global da JVM.
	 */
	public MediaTemporaryFiles(String configuredDir, String dataDir, String systemTmpDir, String workingDir) {
		this.configuredDir = configuredDir == null ? "" : configuredDir.trim();
		this.candidates = candidatos(this.configuredDir, dataDir, systemTmpDir, workingDir);
	}

	/**
	 * Nenhum diretório aceitou o arquivo. É separado de um `IOException` qualquer porque a causa não
	 * é o arquivo que a pessoa enviou: é o disco do servidor, e a resposta não pode ser "arquivo
	 * inválido".
	 */
	public static class UnavailableException extends IOException {

		private final List<String> dirs;

		UnavailableException(List<String> dirs, Throwable cause) {
			super("Nenhum diretorio temporario gravavel: " + String.join(", ", dirs), cause);
			this.dirs = List.copyOf(dirs);
		}

		/** Os diretórios tentados, na ordem, para o log e para o teste. */
		List<String> dirs() {
			return dirs;
		}
	}

	/** Cria o temporário no primeiro diretório gravável; o chamador é dono do arquivo. */
	public Path create(String prefix, String suffix) throws IOException {
		List<String> tentados = new ArrayList<>();
		IOException ultima = null;
		for (String candidato : candidates) {
			tentados.add(candidato);
			try {
				return Files.createTempFile(directory(candidato), prefix, suffix);
			}
			catch (IOException | RuntimeException failure) {
				ultima = failure instanceof IOException io ? io
					: new IOException(failure.getClass().getSimpleName(), failure);
				LOG.warn("media_temp_dir_unusable dir={} error={}", candidato, failure.getClass().getSimpleName());
			}
		}
		throw new UnavailableException(tentados, ultima);
	}

	private Path directory(String candidato) throws IOException {
		// `File` (e não `Path.of`) tolera caminho relativo, que é como o JDK resolve `user.dir`.
		Path dir = new java.io.File(candidato).toPath();
		if (candidato.equals(configuredDir)) {
			// Diretório explícito: existir é pré-requisito. Criar aqui transformaria um caminho
			// errado em volume novo, e ninguém o acharia depois.
			if (!Files.isDirectory(dir)) {
				throw new IOException("diretorio configurado nao existe: " + candidato);
			}
		}
		else {
			Files.createDirectories(dir);
		}
		return dir;
	}

	private static List<String> candidatos(String configured, String dataDir, String systemTmpDir, String workingDir) {
		// Todos trimados: um espaço sobrando vira caminho inválido e some a comparação de duplicata.
		List<String> dirs = new ArrayList<>();
		String configurado = trim(configured);
		if (!configurado.isEmpty()) {
			dirs.add(configurado);
		}
		String dados = trim(dataDir);
		if (!dados.isEmpty() && !dirs.contains(dados)) {
			dirs.add(dados);
		}
		String tmp = trim(systemTmpDir);
		if (tmp.isEmpty()) {
			tmp = trim(workingDir);
		}
		if (tmp.isEmpty()) {
			tmp = "/tmp";
		}
		if (!dirs.contains(tmp)) {
			dirs.add(tmp);
		}
		return List.copyOf(dirs);
	}

	private static String trim(String value) {
		return value == null ? "" : value.trim();
	}
}
