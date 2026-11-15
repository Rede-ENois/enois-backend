package com.socialcoisa.rede.eroleplay.application;

import java.util.Collection;

/**
 * Algo dentro de uma campanha mudou — o chat (mensagem nova, fixação) e, pelo mesmo sinal, o
 * estado vivo da mesa e o atlas do mundo. Publicado dentro da escrita e entregue aos streams
 * abertos só depois do commit: quem recarrega ao ouvir o sinal nunca lê o estado de antes da
 * mudança.
 *
 * <p>O corpo só leva ids: o conteúdo real vem dos endpoints, que autorizam de novo por leitor.
 * O stream nunca transporta conteúdo.</p>
 *
 * @param campaignId a campanha que mudou
 * @param userIds os participantes que têm stream aberto e devem recarregar
 */
public record CampaignChatChanged(String campaignId, Collection<String> userIds) {
}
