package com.socialcoisa.replica;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.URI;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

import jdk.net.ExtendedSocketOptions;
import org.springframework.boot.system.ApplicationHome;

final class ContextoWorkload {

	private static final int MAX_FRAME = 16384;
	private static final int MAGIC = 0x454e4a31;
	private static final long MARGEM_EXPIRACAO_MS = 250;

	record Atribuicao(String versao, String revisao, long geracao, String no, String sessao, long expiraEm,
			URI dados, String credencial) {
		@Override
		public String toString() {
			return "Atribuicao[versao=" + versao + ", geracao=" + geracao + "]";
		}
	}

	private final Path socket;
	private final String artefato;

	ContextoWorkload(String endereco) {
		if (endereco.isBlank()) {
			socket = null;
			artefato = "";
			return;
		}
		socket = Path.of(endereco);
		if (!socket.isAbsolute() || !socket.equals(socket.normalize())) {
			throw new IllegalArgumentException("A réplica exige um caminho absoluto para o socket de workload.");
		}
		var origem = new ApplicationHome(ReplicaApplication.class).getSource();
		if (origem == null || !origem.isFile()) {
			throw new IllegalStateException("O contexto de workload exige a réplica empacotada.");
		}
		artefato = digest(origem.toPath());
	}

	Atribuicao atual() throws IOException {
		if (socket == null) {
			throw new IOException("Réplica sem socket de workload");
		}
		Path diretorio = socket.getParent();
		if (!diretorio.equals(diretorio.toRealPath()) ||
				!Files.getPosixFilePermissions(diretorio).equals(PosixFilePermissions.fromString("rwx------"))) {
			throw new IOException("Diretório de workload inválido");
		}
		var dono = Files.getOwner(diretorio);
		byte[] mensagem;
		try (var canal = SocketChannel.open(StandardProtocolFamily.UNIX); var seletor = Selector.open()) {
			long prazo = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(400);
			canal.configureBlocking(false);
			if (!canal.connect(UnixDomainSocketAddress.of(socket))) {
				var chave = canal.register(seletor, SelectionKey.OP_CONNECT);
				while (!canal.finishConnect()) {
					aguardar(seletor, prazo);
				}
				chave.interestOps(SelectionKey.OP_READ);
			}
			else {
				canal.register(seletor, SelectionKey.OP_READ);
			}
			if (!dono.equals(canal.getOption(ExtendedSocketOptions.SO_PEERCRED).user())) {
				throw new IOException("Identidade do agente de workload inválida");
			}
			var recebidos = new ByteArrayOutputStream();
			var buffer = ByteBuffer.allocate(4096);
			for (int lidos; (lidos = canal.read(buffer)) >= 0; buffer.clear()) {
				if (lidos == 0) {
					aguardar(seletor, prazo);
				}
				if (recebidos.size() + lidos > MAX_FRAME) {
					throw new IOException("Contexto de workload excedeu o limite");
				}
				recebidos.write(buffer.array(), 0, lidos);
			}
			mensagem = recebidos.toByteArray();
		}
		try (var entrada = new DataInputStream(new ByteArrayInputStream(mensagem))) {
			if (entrada.readInt() != MAGIC || !entrada.readUTF().equals("enois-backend")) {
				throw new IOException("Contexto de workload incompatível");
			}
			String versao = entrada.readUTF();
			if (!artefato.equals(entrada.readUTF())) {
				throw new IOException("Artefato Java incompatível");
			}
			var atribuicao = new Atribuicao(versao, entrada.readUTF(), entrada.readLong(), entrada.readUTF(),
				entrada.readUTF(), entrada.readLong(), URI.create(entrada.readUTF()), entrada.readUTF());
			if (entrada.available() != 0 || !versao.matches("[0-9]+\\.[0-9]+\\.[0-9]+") ||
					atribuicao.geracao() < 1 || atribuicao.revisao().isBlank() || atribuicao.no().isBlank() ||
					atribuicao.sessao().isBlank() || atribuicao.credencial().isBlank() ||
					atribuicao.expiraEm() <= System.currentTimeMillis() + MARGEM_EXPIRACAO_MS ||
					!destinoValido(atribuicao.dados())) {
				throw new IOException("Contexto de workload inválido ou expirado");
			}
			return atribuicao;
		} catch (IllegalArgumentException failure) {
			throw new IOException("Contexto de workload inválido");
		}
	}

	private static boolean destinoValido(URI uri) {
		return "https".equals(uri.getScheme()) && uri.getHost() != null && uri.getPort() > 0
			&& uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null
			&& uri.getRawPath().isEmpty();
	}

	private static void aguardar(Selector seletor, long prazo) throws IOException {
		long restante = prazo - System.nanoTime();
		if (restante <= 0) {
			throw new IOException("Tempo esgotado ao ler contexto de workload");
		}
		seletor.select(Math.max(1, TimeUnit.NANOSECONDS.toMillis(restante)));
		seletor.selectedKeys().clear();
	}

	private static String digest(Path arquivo) {
		try (var stream = new DigestInputStream(Files.newInputStream(arquivo), MessageDigest.getInstance("SHA-256"))) {
			stream.transferTo(java.io.OutputStream.nullOutputStream());
			return HexFormat.of().formatHex(stream.getMessageDigest().digest());
		} catch (IOException | NoSuchAlgorithmException failure) {
			throw new IllegalStateException("Não foi possível atestar o artefato Java.", failure);
		}
	}
}
