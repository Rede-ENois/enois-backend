package com.socialcoisa.rede.murais.domain;

/** Ciclo de vida do mural. Remoção é macia: o quadro sai do ar, os elos ficam no histórico. */
public enum MuralStatus {
	ACTIVE,
	REMOVED
}
