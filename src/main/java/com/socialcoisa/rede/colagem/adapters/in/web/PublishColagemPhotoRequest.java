package com.socialcoisa.rede.colagem.adapters.in.web;

/** Corpo do POST /colagem/posts: exatamente uma fotografia e legenda opcional. */
public record PublishColagemPhotoRequest(String mediaId, String description, Boolean mediaSpoiler) {
}
