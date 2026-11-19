package com.socialcoisa.dados;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.google.protobuf.CodedInputStream;
import org.springframework.beans.factory.annotation.Value;

import com.socialcoisa.rede.shared.web.ApiException;

final class CredencialDados {

	private static final int MAX_CREDENCIAL = 8192;
	private final SecretKeySpec chave;

	CredencialDados(@Value("${rede.dados.chave-arquivo}") Path arquivo) throws IOException {
		if (!Files.isRegularFile(arquivo, LinkOption.NOFOLLOW_LINKS) ||
				!Files.getPosixFilePermissions(arquivo, LinkOption.NOFOLLOW_LINKS)
					.equals(PosixFilePermissions.fromString("rw-------"))) {
			throw new IOException("A chave do serviço de dados exige um arquivo privado.");
		}
		try (var entrada = Files.newInputStream(arquivo)) {
			byte[] bytes = entrada.readNBytes(4097);
			if (bytes.length < 32 || bytes.length > 4096) {
				throw new IOException("Tamanho inválido para a chave do serviço de dados.");
			}
			chave = new SecretKeySpec(bytes, "HmacSHA256");
		}
	}

	void exigirReplica(String authorization) {
		if (authorization != null && authorization.length() <= MAX_CREDENCIAL
				&& authorization.startsWith("Bearer ")) {
			String[] partes = authorization.substring(7).split("\\.", -1);
			if (partes.length == 2 && valida(partes)) {
				return;
			}
		}
		throw new ApiException(401, "DATA_AUTH", "Credencial do serviço de dados inválida.");
	}

	private boolean valida(String[] partes) {
		try {
			var codec = Base64.getUrlDecoder();
			byte[] payload = codec.decode(partes[0]);
			var mac = Mac.getInstance("HmacSHA256");
			mac.init(chave);
			return MessageDigest.isEqual(mac.doFinal(payload), codec.decode(partes[1]))
				&& autorizada(CodedInputStream.newInstance(payload));
		} catch (IllegalArgumentException | GeneralSecurityException | IOException falha) {
			return false;
		}
	}

	// enois.runtime.v1.AccessLease. Validar somente os campos necessários à fronteira REST de dados.
	private static boolean autorizada(CodedInputStream entrada) throws IOException {
		String audiencia = "", no = "", sessao = "", app = "";
		long epoca = 0, geracao = 0, geracaoSessao = 0, emitida = 0, expira = 0;
		boolean replica = false, confiavel = false, drenando = false;
		for (int tag; (tag = entrada.readTag()) != 0;) {
			switch (tag) {
				case 10 -> audiencia = entrada.readStringRequireUtf8();
				case 18 -> no = entrada.readStringRequireUtf8();
				case 26 -> sessao = entrada.readStringRequireUtf8();
				case 34 -> app = entrada.readStringRequireUtf8();
				case 40 -> replica |= entrada.readEnum() == 1;
				case 42 -> {
					int limite = entrada.pushLimit(entrada.readRawVarint32());
					while (entrada.getBytesUntilLimit() > 0) {
						replica |= entrada.readEnum() == 1;
					}
					entrada.popLimit(limite);
				}
				case 64 -> confiavel = entrada.readBool();
				case 72 -> drenando = entrada.readBool();
				case 80 -> epoca = entrada.readInt64();
				case 88 -> geracao = entrada.readInt64();
				case 96 -> geracaoSessao = entrada.readInt64();
				case 104 -> emitida = entrada.readInt64();
				case 112 -> expira = entrada.readInt64();
				default -> {
					if (!entrada.skipField(tag)) {
						return false;
					}
				}
			}
		}
		long agora = System.currentTimeMillis();
		return audiencia.equals("data") && app.equals("enois-backend") && replica && confiavel && !drenando
			&& !no.isBlank() && !sessao.isBlank() && epoca > 0 && geracao > 0 && geracaoSessao > 0
			&& emitida > 0 && emitida <= agora + 5000 && expira > agora && expira - emitida <= 300000
			&& expira > emitida;
	}
}
