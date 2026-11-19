package com.socialcoisa.rede.desabafos.adapters.in.web;

/**
 * Corpo do POST /desabafos/posts. O desabafo é só texto — sem mídia, sem enquete, sem página.
 */
public record PublishDesabafoRequest(String content) {
}
