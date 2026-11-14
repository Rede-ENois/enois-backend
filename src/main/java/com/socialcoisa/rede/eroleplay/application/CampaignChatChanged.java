package com.socialcoisa.rede.eroleplay.application;

import java.util.Collection;

/**
 * O chat de uma campanha mudou (mensagem nova, fixação). Publicado dentro da escrita e entregue
 * aos streams abertos só depois do commit — quem recarrega ao ouvir o sinal nunca lê o estado de
 * antes da mudança.
 *
 * <p>O corpo só leva ids: o conteúdo real vem dos endpoints do chat, que autorizam de novo por
 * leitor. O stream nunca transporta mensagem.</p>
 *
 * @param campaignId a campanha cujo chat mudou
 * @param userIds os participantes que têm stream aberto e devem recarregar
 */
public record CampaignChatChanged(String campaignId, Collection<String> userIds) {
}
