package com.socialcoisa.rede.identity.domain;

/** Estados de uma rodada de liberacao da verificacao de identidade. */
public enum ReleaseRoundState {
	PENDING, RUNNING, COMPLETED, PAUSED, CANCELLED
}