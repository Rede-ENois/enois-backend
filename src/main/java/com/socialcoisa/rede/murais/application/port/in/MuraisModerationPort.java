package com.socialcoisa.rede.murais.application.port.in;

/**
 * Moderação dos murais: derruba o quadro (título/descrição são conteúdo do dono). As publicações
 * coladas continuam no feed — quem precisa sair é o mural, não os posts alheios.
 */
public interface MuraisModerationPort {

	/** @return true se o mural existia e foi removido agora. */
	boolean removeMuralAsModerator(String actorId, String muralId, String reason);
}
