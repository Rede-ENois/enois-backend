package com.socialcoisa.rede.wrapped.adapters.out.persistence;

import java.io.Serializable;
import java.util.Objects;

/** A chave composta (conta, ano) de {@code wrapped_retrospectivas}. */
class WrappedRetrospectivaId implements Serializable {

	private String userId;
	private int ano;

	protected WrappedRetrospectivaId() {
	}

	WrappedRetrospectivaId(String userId, int ano) {
		this.userId = userId;
		this.ano = ano;
	}

	String getUserId() {
		return userId;
	}

	void setUserId(String userId) {
		this.userId = userId;
	}

	int getAno() {
		return ano;
	}

	void setAno(int ano) {
		this.ano = ano;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof WrappedRetrospectivaId outro)) {
			return false;
		}
		return ano == outro.ano && Objects.equals(userId, outro.userId);
	}

	@Override
	public int hashCode() {
		return Objects.hash(userId, ano);
	}
}
