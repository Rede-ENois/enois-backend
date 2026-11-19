package com.socialcoisa.rede.feed.application;

import java.util.List;

/** Pagina do diario de atividade do perfil; `nextCursor` em base64 quando ainda ha eventos. */
public record DiarioPage(List<DiarioEventoView> itens, String nextCursor) {
}
