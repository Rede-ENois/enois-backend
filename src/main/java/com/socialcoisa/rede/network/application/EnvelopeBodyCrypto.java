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
 * Criptografia <b>envelope</b> do corpo de objeto (brief §10: "criptografia envelope", "chaves
 * protegidas fora dos storage nodes", "Não distribuir chaves mestras aos participantes").
 *
 * <p><b>Por que não bastava a cifra em repouso do {@code NodeBodyStore}:</b> lá a chave é única da
 * instalação. Quem tem a chave — e quem opera o nó tem — lê todos os corpos do disco. O envelope muda
 * isso: cada objeto tem a <b>sua própria chave de dados</b>, e o disco guarda essa chave <b>cifrada</b>
 * pela chave mestra. Sem a mestra, um objeto cifrado é ruído — <b>mesmo para quem tem o arquivo
 * inteiro</b>.</p>
 *
 * <p><b>Modelo (DEK/KEK):</b></p>
 * <ul>
 * <li><b>DEK</b> — chave de dados, AES-256 aleatória, uma por objeto;</li>
 * <li><b>KEK</b> — chave mestra da instalação ({@code REDE_SECURITY_ENCRYPTION_KEY}), que <b>não
 * vaza</b>: a DEK é gravada embrulhada por ela, e a DEK em claro só existe em memória durante a
 * leitura/escrita daquele objeto;</li>
 * <li><b>header</b> do arquivo: {@code ENOISENV1 | versão | iv | dek_embrulhada} — tudo o que um
 * processo com a mestra precisa para abrir o objeto, e nada que um processo sem ela consiga usar.</li>
 * </ul>
 *
 * <p><b>Ainda não é uma KMS externa.</b> Ainda é o mesmo processo detendo a mestra; o que muda é a
 * granularidade: perder a chave de um objeto (revogação por objeto) não derruba o resto, e um backup
 * de disco sem a mestra não abre nada. Mover a mestra para uma KMS/serviço de segredos é o passo
 * seguinte e é o que separa "o operador não lê o disco" de "ninguém além da KMS lê".</p>
 */
final class EnvelopeBodyCrypto {

	/** Identificador do formato no começo do arquivo. */
	private static final byte[] MAGIC = "ENOISENV1".getBytes(StandardCharsets.US_ASCII);
	private static final byte VERSION = 1;
	private static final int IV_BYTES = 12;
	private static final int TAG_BITS = 128;
	/** DEK de 32 bytes (AES-256): 12 de IV + 32 de chave + 16 de tag = 60 bytes embrulhados. */
	private static final int DEK_BYTES = 32;
	private static final int WRAPPED_DEK_BYTES = IV_BYTES + DEK_BYTES + 16;
	private static final int HEADER_BYTES = MAGIC.length + 1 + IV_BYTES + WRAPPED_DEK_BYTES;

	private final SecretKeySpec kek;
	private final SecureRandom random = new SecureRandom();

	EnvelopeBodyCrypto(byte[] masterKey) {
		if (masterKey == null || masterKey.length != 32) {
			throw new IllegalArgumentException("chave mestra precisa ter 32 bytes (AES-256)");
		}
		this.kek = new SecretKeySpec(masterKey, "AES");
	}

	/**
	 * Grava {@code source} cifrado com uma DEK nova em {@code destination}, atomicamente.
	 *
	 * <p>O temporário tem o mesmo ciclo do resto do Storage: aparece com o nome final só depois de
	 * inteiro, então uma interrupção no meio deixa um {@code .partial}, nunca um objeto truncado.</p>
	 */
	void encryptTo(Path source, Path destination, Path tempDirectory) throws IOException {
		Path temp = Files.createTempFile(tempDirectory, destination.getFileName().toString() + ".", ".envpartial");
		try {
			byte[] dek = new byte[DEK_BYTES];
			random.nextBytes(dek);
			byte[] iv = new byte[IV_BYTES];
			random.nextBytes(iv);
			byte[] wrappedDek = wrap(dek);
			try (OutputStream out = Files.newOutputStream(temp)) {
				out.write(MAGIC);
				out.write(VERSION);
				out.write(iv);
				out.write(wrappedDek);
				try (InputStream in = Files.newInputStream(source);
						CipherOutputStream encrypted = new CipherOutputStream(out, cipher(Cipher.ENCRYPT_MODE, dek, iv))) {
					in.transferTo(encrypted);
				}
			}
			Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		catch (IOException | GeneralSecurityException falha) {
			Files.deleteIfExists(temp);
			if (falha instanceof IOException io) {
				throw io;
			}
			throw new IOException("falha ao cifrar o corpo com envelope", falha);
		}
	}

	/**
	 * Decifra um arquivo já gravado no formato envelope.
	 *
	 * <p>O arquivo <b>não</b> é carregado na memória: lê-se o cabeçalho, desembrulha-se a DEK com a
	 * mestra e o resto é decifrado em fluxo. A tag do GCM só é conferida ao fim — quem consome tem de
	 * consumir até o fim para ter a garantia de integridade.</p>
	 */
	InputStream decrypt(Path source) throws IOException {
		byte[] header;
		try (InputStream probe = Files.newInputStream(source)) {
			header = probe.readNBytes(HEADER_BYTES);
		}
		if (header.length != HEADER_BYTES) {
			throw new IOException("arquivo de corpo menor que o cabecalho do envelope");
		}
		if (!Arrays.equals(Arrays.copyOfRange(header, 0, MAGIC.length), MAGIC)) {
			throw new IOException("arquivo de corpo nao esta no formato envelope");
		}
		if (header[MAGIC.length] != VERSION) {
			throw new IOException("versao de envelope desconhecida: " + header[MAGIC.length]);
		}
		int ivFrom = MAGIC.length + 1;
		byte[] iv = Arrays.copyOfRange(header, ivFrom, ivFrom + IV_BYTES);
		byte[] wrapped = Arrays.copyOfRange(header, ivFrom + IV_BYTES, HEADER_BYTES);
		byte[] dek;
		try {
			dek = unwrap(wrapped);
		}
		catch (GeneralSecurityException falha) {
			// A mestra não abre esta DEK: chave trocada ou arquivo de outra instalação. Erro claro,
			// nunca bytes lixo entregues como se fossem o objeto.
			throw new IOException("a chave mestra nao desembrulha a chave deste objeto", falha);
		}

		InputStream raw = Files.newInputStream(source);
		if (raw.skip(HEADER_BYTES) != HEADER_BYTES) {
			raw.close();
			throw new IOException("nao foi possivel pular o cabecalho do envelope");
		}
		try {
			return new CipherInputStream(raw, cipher(Cipher.DECRYPT_MODE, dek, iv));
		}
		catch (GeneralSecurityException falha) {
			raw.close();
			throw new IOException("falha ao preparar a decifra do envelope", falha);
		}
	}

	/** O arquivo está no formato envelope? */
	boolean isEnvelope(Path source) throws IOException {
		byte[] head = new byte[MAGIC.length];
		try (InputStream in = Files.newInputStream(source)) {
			return in.readNBytes(head, 0, MAGIC.length) == MAGIC.length && Arrays.equals(head, MAGIC);
		}
	}

	/**
	 * Embrulha a DEK com a mestra (AES-GCM, IV próprio). Devolve {@code iv | ciphertext+tag}.
	 *
	 * <p>É a operação que a KMS faria quando existir: aqui é local, mas a DEK nunca é gravada em
	 * claro — só a versão embrulhada entra no arquivo e, se preciso, no banco.</p>
	 */
	byte[] wrap(byte[] dek) {
		try {
			byte[] wrapIv = new byte[IV_BYTES];
			random.nextBytes(wrapIv);
			Cipher wrapper = cipher(Cipher.ENCRYPT_MODE, kek.getEncoded(), wrapIv);
			byte[] wrapped = wrapper.doFinal(dek);
			byte[] out = new byte[wrapIv.length + wrapped.length];
			System.arraycopy(wrapIv, 0, out, 0, wrapIv.length);
			System.arraycopy(wrapped, 0, out, wrapIv.length, wrapped.length);
			return out;
		}
		catch (GeneralSecurityException falha) {
			throw new IllegalStateException("falha ao embrulhar a chave de dados", falha);
		}
	}

	private byte[] unwrap(byte[] wrapped) throws GeneralSecurityException {
		byte[] wrapIv = Arrays.copyOfRange(wrapped, 0, IV_BYTES);
		byte[] body = Arrays.copyOfRange(wrapped, IV_BYTES, wrapped.length);
		return cipher(Cipher.DECRYPT_MODE, kek.getEncoded(), wrapIv).doFinal(body);
	}

	private static Cipher cipher(int mode, byte[] keyBytes, byte[] iv) throws GeneralSecurityException {
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(mode, new SecretKeySpec(keyBytes, "AES"), new GCMParameterSpec(TAG_BITS, iv));
		return cipher;
	}

	/** Tamanho do cabeçalho do envelope, para diagnóstico. */
	static int headerBytes() {
		return HEADER_BYTES;
	}

	/** A DEK embrulhada de um arquivo já cifrado — o que o catálogo guarda ao lado do objeto. */
	byte[] wrappedDekOf(Path source) throws IOException {
		byte[] header;
		try (InputStream probe = Files.newInputStream(source)) {
			header = probe.readNBytes(HEADER_BYTES);
		}
		if (header.length != HEADER_BYTES || !Arrays.equals(Arrays.copyOfRange(header, 0, MAGIC.length), MAGIC)) {
			throw new IOException("arquivo de corpo nao esta no formato envelope");
		}
		return Arrays.copyOfRange(header, MAGIC.length + 1 + IV_BYTES, HEADER_BYTES);
	}
}
