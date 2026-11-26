package com.socialcoisa.rede.wrapped.domain;

/** Uma comunidade em que a conta participou no ano (membro ou publicou) e o quanto participou. */
public record WrappedComunidade(String communityId, String nome, int publicacoes, int participacoes) {
}
