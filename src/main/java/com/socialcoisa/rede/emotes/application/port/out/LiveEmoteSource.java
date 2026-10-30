package com.socialcoisa.rede.emotes.application.port.out;

/**
 * Fonte ao vivo de figurinhas: consulta o provedor pela rede e, por isso, so roda no refresh
 * agendado. A fonte local (retrato embarcado) nao e uma destas — ela e lida a cada montagem, na hora.
 */
public interface LiveEmoteSource extends EmoteSource {
}