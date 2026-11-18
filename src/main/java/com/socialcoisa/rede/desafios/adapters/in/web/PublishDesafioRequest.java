package com.socialcoisa.rede.desafios.adapters.in.web;

import java.time.Instant;

/** Corpo do POST /desafios: título, descrição e a janela opcional (null = sem prazo). */
public record PublishDesafioRequest(String title, String description, Instant endsAt) {
}
