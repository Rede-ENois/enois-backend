package com.socialcoisa.rede.paporeto.domain;

import java.time.Instant;

/**
 * A fala pura do Papo Reto: a expressão extraída com a sua fonte. Sem autor e sem instante —
 * quem carrega isso é o repositório, e o extrator não precisa saber.
 */
public record PapoTemaFala(String expressao, String fonte) {

}
