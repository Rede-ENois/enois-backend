package com.socialcoisa.rede.messaging.adapters.in.web;

import java.util.Map;

/**
 * Corpo do sinal de stream que atravessa instâncias: o nome do evento SSE e os dados que ele
 * carrega.
 *
 * <p>É o mesmo par que o {@link MessageStreamHub} monta para o stream local. Só ids viajam aqui —
 * o conteúdo continua vindo dos endpoints autorizados, exatamente como no caminho de uma instância
 * só.</p>
 */
record StreamSignalPayload(String event, Map<String, String> data) {
}
