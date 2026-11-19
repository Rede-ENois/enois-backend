package com.socialcoisa.rede.termometro;

/**
 * O Termômetro: o clima coletivo do Explorar, medido — nunca perguntado.
 *
 * O módulo lê o que já é público (posts e comentários do feed, desabafos, confissões do
 * Confessionário), classifica com um léxico determinístico local e agrega por janela de tempo.
 * O que o painel mostra é só o conjunto: porcentagens por emoção, quantas manifestações
 * entraram na conta e quantas pessoas distintas participaram. Nada individual: sem ranking de
 * membros, sem dado de mensagem privada, sem identificar quem escreveu o quê — e, com menos de
 * vinte pessoas distintas na janela, o painel fica em silêncio em vez de inventar clima.
 *
 * Não é enquete e não usa LLM nem serviço externo: mesma entrada, mesmo número, sempre.
 */
public interface TermometroModule {

}
