package com.socialcoisa.rede.documentos.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Uma versão de um documento legal. Imutável: publicar texto novo é criar outra versão.
 *
 * <p>O ciclo é explícito: a versão nasce como rascunho ({@code aprovado=false}, sem vigência),
 * vira aprovada (passa a ser o texto que o app mostra) e só então entra em vigor
 * ({@code vigenteDesde}). "Em vigor" é o que obriga o novo aceite de quem já é usuário — enquanto
 * não está em vigor, o texto é lido, mas a pessoa não é bloqueada por ele.</p>
 */
public record DocumentoLegal(String id, DocumentoTipo tipo, int versao, String titulo, String resumo,
		String conteudo, String conteudoHash, boolean exigeAceite, boolean aprovado, Instant vigenteDesde,
		Instant criadoEm, String criadoPor, Instant aprovadoEm, String aprovadoPor) {

	public static final int CONTEUDO_MINIMO = 200;
	public static final int CONTEUDO_MAXIMO = 200_000;
	public static final int TITULO_MAXIMO = 160;
	public static final int RESUMO_MAXIMO = 1000;

	public boolean vigente() {
		return aprovado && vigenteDesde != null;
	}

	public static String hash(String conteudo) {
		try {
			MessageDigest sha = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(sha.digest(conteudo.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException impossivel) {
			throw new IllegalStateException("SHA-256 indisponível", impossivel);
		}
	}
}
