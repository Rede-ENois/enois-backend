package com.socialcoisa.rede.murais.adapters.in.web;

/** Edição do mural: só o dono. Mesmas regras de título e descrição da criação. */
public record EditMuralRequest(String title, String description) {
}
