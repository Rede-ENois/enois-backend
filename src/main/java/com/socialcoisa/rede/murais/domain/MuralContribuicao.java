package com.socialcoisa.rede.murais.domain;

import java.time.Instant;

import com.socialcoisa.rede.feed.domain.UserId;

/**
 * O elo entre mural e publicação: alguém ({@code addedBy}) colou um post que já existe no feed.
 * O id é determinístico (mural:post) — o par é a própria identidade, então recolocar a mesma
 * publicação no mesmo mural nunca cria linha nova.
 */
public class MuralContribuicao {

	private final String id;
	private final String muralId;
	private final String postId;
	private final UserId addedBy;
	private final Instant createdAt;

	public MuralContribuicao(String id, String muralId, String postId, UserId addedBy, Instant createdAt) {
		this.id = id;
		this.muralId = muralId;
		this.postId = postId;
		this.addedBy = addedBy;
		this.createdAt = createdAt;
	}

	public static MuralContribuicao novo(String muralId, String postId, UserId addedBy, Instant createdAt) {
		return new MuralContribuicao(muralId + ":" + postId, muralId, postId, addedBy, createdAt);
	}

	public static String idDe(String muralId, String postId) {
		return muralId + ":" + postId;
	}

	public String id() {
		return id;
	}

	public String muralId() {
		return muralId;
	}

	public String postId() {
		return postId;
	}

	public UserId addedBy() {
		return addedBy;
	}

	public Instant createdAt() {
		return createdAt;
	}
}
