package com.socialcoisa.rede.moderation.domain;

/** O que pode ser denunciado. Cada alvo tem um leitor próprio (conteúdo e autor) no módulo. */
public enum TargetType {
	POST,
	COMMENT,
	USER,
	JOB,
	/** Jogo do Énóis Games (página do jogo e do criador). */
	GAME,
	/** Publicação do feed privado de um grupo. */
	GROUP_POST,
	/** Comentário de uma publicação de grupo. */
	GROUP_COMMENT,
	/** Mensagem privada (DM ou grupo). */
	MESSAGE,
	/** Pagina empresarial (a revisao a REMOVE do diretorio; nao bane o dono). */
	COMPANY,
	/**
	 * Chamada de audio/video. O alvo é a SALA; quem foi denunciado é um participante apontado por
	 * quem denuncia (a denúncia guarda quem estava dentro e o print do momento).
	 */
	CALL
}
