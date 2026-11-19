package com.socialcoisa.rede.media.application;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/** Dublê nativo com JVM própria: o runner continua criando, aguardando e matando processos reais. */
public final class FfmpegProcessoDeTeste {

	private final Path executavel;
	private final Path configuracao;

	private FfmpegProcessoDeTeste(Path executavel, Path configuracao) {
		this.executavel = executavel;
		this.configuracao = configuracao;
	}

	static FfmpegProcessoDeTeste criar(Path pasta) throws Exception {
		Path entrada = Files.createDirectory(pasta.resolve("entrada"));
		String recurso = FfmpegProcessoDeTeste.class.getName().replace('.', '/') + ".class";
		try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(entrada.resolve("duble.jar")));
				InputStream classe = FfmpegProcessoDeTeste.class.getClassLoader().getResourceAsStream(recurso)) {
			jar.putNextEntry(new JarEntry(recurso));
			classe.transferTo(jar);
			jar.closeEntry();
		}
		boolean windows = Files.exists(Path.of(System.getProperty("java.home"), "bin", "java.exe"));
		Path jpackage = Path.of(System.getProperty("java.home"), "bin", windows ? "jpackage.exe" : "jpackage");
		List<String> comando = new ArrayList<>(List.of(jpackage.toString(), "--type", "app-image", "--name",
			"ffmpeg", "--input", entrada.toString(), "--main-jar", "duble.jar", "--main-class",
			FfmpegProcessoDeTeste.class.getName(), "--add-modules", "java.base", "--dest", pasta.toString(),
			"--temp", pasta.resolve("empacotamento").toString()));
		if (windows) {
			comando.add("--win-console");
		}
		Path log = pasta.resolve("jpackage.log");
		Process processo = new ProcessBuilder(comando).redirectErrorStream(true).redirectOutput(log.toFile()).start();
		if (!processo.waitFor(120, TimeUnit.SECONDS)) {
			processo.destroyForcibly().waitFor();
			throw new AssertionError("jpackage excedeu o prazo: " + Files.readString(log));
		}
		if (processo.exitValue() != 0) {
			throw new AssertionError("jpackage falhou: " + Files.readString(log));
		}
		Path imagem = pasta.resolve("ffmpeg");
		return new FfmpegProcessoDeTeste(imagem.resolve(windows ? "ffmpeg.exe" : "bin/ffmpeg"),
			imagem.resolve(windows ? "app/ffmpeg.cfg" : "lib/app/ffmpeg.cfg"));
	}

	Path ffmpeg(Path pasta, Path argumentos, int transcodeBytes, int remuxBytes) throws Exception {
		return preparar(pasta, argumentos, transcodeBytes, remuxBytes, 0, null, null);
	}

	Path demorado(Path pasta, Path argumentos, Path pids, boolean somenteTranscode) throws Exception {
		return preparar(pasta, argumentos, 1_000_000, 7_000_000, somenteTranscode ? 1 : 2, pids, null);
	}

	Path probe(Path pasta, Path argumentos, Path resultado) throws Exception {
		return preparar(pasta, argumentos, 0, 0, 0, null, resultado);
	}

	private Path preparar(Path pasta, Path argumentos, int transcodeBytes, int remuxBytes, int demora,
			Path pids, Path resultado) throws Exception {
		Properties opcoes = new Properties();
		opcoes.setProperty("transcodeBytes", Integer.toString(transcodeBytes));
		opcoes.setProperty("remuxBytes", Integer.toString(remuxBytes));
		opcoes.setProperty("demora", Integer.toString(demora));
		if (argumentos != null) {
			opcoes.setProperty("argumentos", argumentos.toString());
		}
		if (pids != null) {
			opcoes.setProperty("pids", pids.toString());
		}
		if (resultado != null) {
			opcoes.setProperty("probe", resultado.toString());
		}
		Path cenario = pasta.resolve("cenario-" + UUID.randomUUID() + ".properties");
		try (OutputStream arquivo = Files.newOutputStream(cenario)) {
			opcoes.store(arquivo, null);
		}
		String nome = "cenario-" + UUID.randomUUID();
		Path launcher = executavel.resolveSibling(nome + (executavel.toString().endsWith(".exe") ? ".exe" : ""));
		Files.copy(executavel, launcher, StandardCopyOption.COPY_ATTRIBUTES);
		// Cada linha java-options é um argumento único, inclusive quando o caminho contém espaços.
		Files.writeString(configuracao.resolveSibling(nome + ".cfg"), Files.readString(configuracao)
			.replace("[JavaOptions]", "[JavaOptions]\njava-options=-Dcenario=" + cenario.toAbsolutePath()));
		return launcher;
	}

	public static void main(String[] args) throws Exception {
		Properties opcoes = new Properties();
		try (InputStream arquivo = Files.newInputStream(Path.of(System.getProperty("cenario")))) {
			opcoes.load(arquivo);
		}
		if (opcoes.containsKey("argumentos")) {
			Files.write(Path.of(opcoes.getProperty("argumentos")), Arrays.asList(args),
				StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		}
		if (opcoes.containsKey("probe")) {
			System.out.print(Files.readString(Path.of(opcoes.getProperty("probe"))));
			return;
		}
		boolean transcode = Arrays.asList(args).contains("-vf");
		int demora = Integer.parseInt(opcoes.getProperty("demora"));
		if (opcoes.containsKey("pids")) {
			Files.writeString(Path.of(opcoes.getProperty("pids")), ProcessHandle.current().pid() + "\n",
				StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		}
		if (demora == 2 || (demora == 1 && transcode)) {
			Thread.sleep(30_000);
		}
		int tamanho = Integer.parseInt(opcoes.getProperty(transcode ? "transcodeBytes" : "remuxBytes"));
		if (tamanho < 0) {
			System.exit(1);
		}
		Path saida = Path.of(args[args.length - 1].substring("file:".length()));
		Files.write(saida, tamanho == 5 ? "clean".getBytes(java.nio.charset.StandardCharsets.UTF_8) : new byte[tamanho]);
	}
}
