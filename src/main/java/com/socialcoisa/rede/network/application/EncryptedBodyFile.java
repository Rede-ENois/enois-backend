package com.socialcoisa.rede.network.application;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Cifra em repouso dos corpos de objeto que o nó grava em disco (brief §10: "a criptografia em
 * repouso deverá impedir que a retirada física do disco revele os arquivos armazenados").
 *
 * <p><b>Por que não o {@code SecretCipher} direto:</b> ele cifra e decifra <em>String</em> — serve
 * para o segredo do TOTP e da credencial S3, não para um vídeo de centenas de MB. Aqui o mesmo
 * AES-256-GCM roda <b>em fluxo</b>: o arquivo nunca é carregado inteiro na memória, e o GCM autentica
 * o conteúdo — um disco adulterado falha ao decifrar, em vez de servir bytes trocados.</p>
 *
 * <p><b>A chave é a mesma da instalação</b> ({@code REDE_SECURITY_ENCRYPTION_KEY}, AES-256). Um segundo
 * material de chave seria um segundo segredo para o operador proteger, e o brief é explícito em não
 * criar sistemas paralelos para o que já existe.</p>
 *
 * <p><b>Layout do arquivo:</b> {@code magic "ENOISENC1" (9) | versão (1) | iv (12) | GCM(corpo)}. O
 * {@code magic} existe para o nó distinguir arquivo cifrado de arquivo em claro — durante a migração
 * os dois coexistem no mesmo diretório, e ler um claro como cifrado daria erro em todo corpo antigo.</p>
 */
final class EncryptedBodyFile {

	/** Identificador do formato. Curto de propósito: 10 bytes por arquivo, não por KB. */
	private static final byte[] MAGIC = "ENOISENC1".getBytes(StandardCharsets.US_ASCII);
	private static final byte VERSION = 1;
	private static final int IV_BYTES = 12;
	private static final int TAG_BITS = 128;
	private static final int HEADER_BYTES = MAGIC.length + 1 + IV_BYTES;

	private final SecretKeySpec key;
	private final SecureRandom random = new SecureRandom();

	EncryptedBodyFile(byte[] keyBytes) {
		if (keyBytes == null || keyBytes.length != 32) {
			throw new IllegalArgumentException("chave de cifra precisa ter 32 bytes (AES-256)");
		}
		this.key = new SecretKeySpec(keyBytes, "AES");
	}

	/**
	 * Grava {@code source} cifrado em {@code destination}, de forma atômica.
	 *
	 * <p>Mesma regra do {@link NodeBodyStore}: o arquivo só aparece com o nome final depois de
	 * inteiro. Um processo morto no meio deixa um {@code .partial}, nunca um corpo truncado que o nó
	 * serviria como cópia.</p>
	 *
	 * @return quantos bytes de <b>texto claro</b> foram gravados (o tamanho do objeto, não do arquivo)
	 */
	long write(Path source, Path destination, Path tempDirectory) throws IOException {
		Path temp = Files.createTempFile(tempDirectory, destination.getFileName().toString() + ".", ".partial");
		try {
			byte[] iv = new byte[IV_BYTES];
			random.nextBytes(iv);
			long plainBytes;
			try (OutputStream out = Files.newOutputStream(temp)) {
				out.write(MAGIC);
				out.write(VERSION);
				out.write(iv);
				try (InputStream in = Files.newInputStream(source);
						CipherOutputStream encrypted = new CipherOutputStream(out, cipher(Cipher.ENCRYPT_MODE, iv))) {
					plainBytes = in.transferTo(encrypted);
				}
			}
			// Mesmo diretório: o move é rename atômico, sem cópia entre sistemas de arquivos.
			Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			return plainBytes;
		}
		catch (IOException | GeneralSecurityException falha) {
			Files.deleteIfExists(temp);
			if (falha instanceof IOException io) {
				throw io;
			}
			throw new IOException("falha ao cifrar o corpo em repouso", falha);
		}
	}

	/**
	 * Abre o corpo decifrado. O GCM só autentica a tag ao fim do fluxo: um arquivo adulterado lança
	 * {@link IOException} quando o leitor chega ao fim — por isso quem consome tem de fechar o fluxo
	 * e tratar a falha, não só ler até {@code read() == -1}.
	 */
	InputStream read(Path source) throws IOException {
		InputStream raw;
		byte[] header = new byte[HEADER_BYTES];
		try (InputStream probe = Files.newInputStream(source)) {
			if (probe.readNBytes(header, 0, HEADER_BYTES) != HEADER_BYTES) {
				throw new IOException("arquivo de corpo menor que o cabecalho cifrado");
			}
			if (!Arrays.equals(Arrays.copyOfRange(header, 0, MAGIC.length), MAGIC)) {
				throw new IOException("arquivo de corpo nao esta no formato cifrado");
			}
			if (header[MAGIC.length] != VERSION) {
				throw new IOException("versao de corpo cifrado desconhecida: " + header[MAGIC.length]);
			}
		}
		raw = Files.newInputStream(source);
		long skipped = raw.skip(HEADER_BYTES);
		if (skipped != HEADER_BYTES) {
			raw.close();
			throw new IOException("nao foi possivel pular o cabecalho do corpo cifrado");
		}
		byte[] iv = Arrays.copyOfRange(header, MAGIC.length + 1, HEADER_BYTES);
		try {
			return new CipherInputStream(raw, cipher(Cipher.DECRYPT_MODE, iv));
		}
		catch (GeneralSecurityException falha) {
			raw.close();
			throw new IOException("falha ao preparar a decifra do corpo", falha);
		}
	}

	/**
	 * O arquivo está no formato cifrado? Usado pelo inventário e pela migração dos corpos que já
	 * existem em claro.
	 */
	boolean isEncrypted(Path source) throws IOException {
		byte[] head = new byte[MAGIC.length];
		try (InputStream in = Files.newInputStream(source)) {
			return in.readNBytes(head, 0, MAGIC.length) == MAGIC.length && Arrays.equals(head, MAGIC);
		}
	}

	private Cipher cipher(int mode, byte[] iv) throws GeneralSecurityException {
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, iv));
		return cipher;
	}

	/** Um leitor de corpo em claro, para o caso de instalação sem chave. */
	static InputStream plain(Path source) throws IOException {
		return Files.newInputStream(source);
	}

	/** Tamanho do cabeçalho, para quem precisa descontar do tamanho do arquivo. */
	static int headerBytes() {
		return HEADER_BYTES;
	}
}