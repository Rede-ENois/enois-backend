package com.socialcoisa.rede.colecoes.domain;

import java.time.Instant;

/**
 * O elo entre coleção e publicação salva: o id é determinístico (colecao:post) e o par é a
 * própria identidade — guardar a mesma publicação na mesma coleção nunca cria linha nova.
 * Só o dono da coleção guarda e tira; não há "adicionadoPor".
 */
public class ColecaoPost {

	private final String id;
	private final String colecaoId;
	private final String postId;
	private final Instant createdAt;

	public ColecaoPost(String id, String colecaoId, String postId, Instant createdAt) {
		this.id = id;
		this.colecaoId = colecaoId;
		this.postId = postId;
		this.createdAt = createdAt;
	}

	public static ColecaoPost novo(String colecaoId, String postId, Instant createdAt) {
		return new ColecaoPost(colecaoId + ":" + postId, colecaoId, postId, createdAt);
	}

	public static String idDe(String colecaoId, String postId) {
		return colecaoId + ":" + postId;
	}

	public String id() {
		return id;
	}

	public String colecaoId() {
		return colecaoId;
	}

	public String postId() {
		return postId;
	}

	public Instant createdAt() {
		return createdAt;
	}
}
