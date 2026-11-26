package com.socialcoisa.rede.figurinhas.domain;

import java.time.Instant;

/**
 * Uma figurinha cadastrada pela comunidade.
 *
 * <p>A linha é imutável no que identifica a figurinha (código, imagem, autor, data de cadastro) e
 * só muda ao sair do ar. Desativar NÃO apaga: a linha fica com quem desativou, quando e por quê —
 * é o histórico que a auditoria precisa e o que prova a autoria.</p>
 */
public record FigurinhaComunidade(
		String id,
		String codigo,
		String codigoNormalizado,
		String nome,
		String autorId,
		String mediaId,
		String imageUrl,
		String mimeType,
		int largura,
		int altura,
		int bytes,
		boolean ativa,
		Instant criadoEm,
		Instant atualizadoEm,
		Instant desativadoEm,
		String desativadoPor,
		String motivoDesativacao) {

	public static final int NOME_MINIMO = 2;
	public static final int NOME_MAXIMO = 80;

	public static FigurinhaComunidade criar(String id, String codigo, String nome, String autorId, String mediaId,
			String imageUrl, String mimeType, int largura, int altura, int bytes, Instant agora) {
		return new FigurinhaComunidade(id, codigo, FigurinhasCodigoRules.normalizar(codigo), nome, autorId, mediaId,
			imageUrl, mimeType, largura, altura, bytes, true, agora, agora, null, null, null);
	}

	public FigurinhaComunidade desativar(String atorId, String motivo, Instant quando) {
		if (!ativa) {
			return this;
		}
		return new FigurinhaComunidade(id, codigo, codigoNormalizado, nome, autorId, mediaId, imageUrl, mimeType,
			largura, altura, bytes, false, criadoEm, quando, quando, atorId, motivo);
	}

	public boolean pertenceA(String userId) {
		return autorId.equals(userId);
	}
}
