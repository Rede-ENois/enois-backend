package com.socialcoisa.rede.murais.adapters.in.web;

/** Criação de mural: título obrigatório, descrição opcional. */
public record CreateMuralRequest(String title, String description) {
}
