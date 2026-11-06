# Miniatura de link — como funciona

`GET /link-preview?url=...` devolve o que desenhar no cartão de um link publicado num post ou
comentário. A leitura é **pública** (o post é público, inclusive aberto por quem não tem sessão); a
sessão, quando existe, só muda o que uma página interna privada mostra.

Resposta: `200` com `preview` preenchido, ou `200` com `preview: null` quando o endereço não tem
nada para mostrar. Site fora do ar é resultado normal, não erro.

## Dois caminhos, e a ordem importa

1. **Página do próprio Énóis** — o endereço é comparado com `rede.site-url` (host, não prefixo:
   `enois.ia.br.evil.com` não é da casa). O resumo vem do banco e **nada sai para a internet**.
   Tipos atendidos: `/post/{id}`, `/perfil/{usuario}`, `/blog/{usuario}[/{postId}]`, `/vagas/{id}`,
   `/comunidades/{id}`, `/games/{usuario}[/{slug}]`, `/grupos/{slug}`, `/eventos/{id}`.
2. **Site de fora** — lê o que a página declara para compartilhamento (`og:*`, `twitter:image`) com
   `description`/`<title>` como reserva.

O cartão de página interna segue a mesma regra de visibilidade da página: perfil de conta banida,
vaga encerrada, comunidade com texto sinalizado, grupo fora da descoberta e evento de conta banida
não viram cartão. Post de canal privado só aparece para quem é membro.

## Leitura de site de fora

- Só `http`/`https`, só porta padrão, e **todos** os IPs para os quais o nome resolve precisam ser
  públicos. Basta um registro interno para o nome inteiro ser recusado.
- O DNS é resolvido **uma vez**, na guarda (`OutboundUrlGuard.checked`), e a conexão vai para o IP
  que ela aprovou — o nome nunca é resolvido de novo na hora de conectar. TLS usa SNI e verificação
  de nome do destino.
- Redirecionamento é seguido à mão, no máximo 3 saltos, com a guarda aplicada em cada salto.
- Teto de 96 KB de corpo, 32 KB de cabeçalho, 6 s por leitura, `Connection: close`.
- `Transfer-Encoding: chunked` é desmontado; `Content-Type` precisa ser `text/html`.

## Cache

`linkPreviews` (Caffeine, 30 min, 2.000 entradas), com três chaves:

| Chave | O que guarda |
|---|---|
| `x:{url}` | site de fora — igual para todo mundo |
| `i:{path}\|anon` | página interna na leitura anônima |
| `i:{path}\|{viewerId}` | página interna na leitura de quem tem sessão |

A leitura anônima vem primeiro; a leitura com sessão só acontece quando a anônima não achou nada, e
o vazio dessa segunda leitura **não** é guardado. Resultado vazio de site de fora também não é
guardado.

## Limites

- `LinkPreviewRateLimitFilter` — teto por sessão/IP (`rede.rate.link-preview-per-minute`, padrão 40
  por minuto), janela deslizante em memória. Conta toda chamada, servida do cache ou não.
- `resilience4j.bulkhead.linkPreviewOutbound` — no máximo 8 conexões de saída simultâneas. Endereço
  em cache não passa por aqui.

## Configuração

| Propriedade | Padrão | Para quê |
|---|---|---|
| `rede.site-url` | `https://enois.ia.br` | endereço público do site, para reconhecer página da casa |
| `rede.cache.link-previews.ttl` | `30m` | frescor do cartão |
| `rede.cache.link-previews.max-entries` | `2000` | teto de entradas |
| `rede.rate.link-preview-per-minute` | `40` | teto por sessão/IP |
| `resilience4j.bulkhead.instances.linkPreviewOutbound.max-concurrent-calls` | `8` | conexões de saída simultâneas |

A resposta leva `Cache-Control: private, no-store`: o resultado depende de quem lê e não pode ser
servido a outra pessoa por cache compartilhado.
