# Figurinhas de chat (emotes)

Figurinha é o desenho que aparece no meio do texto quando alguém escreve o código dele (`KEKW`,
`catJAM`). O código continua gravado no texto como texto puro; quem troca código por imagem é a
leitura do front, com o índice que este backend publica.

## Por que o catálogo mora no backend

O catálogo já foi montado no navegador, a cada visita, com três chamadas a provedores de terceiro.
Três problemas medidos em 2026-10-29:

| Sintoma | Causa medida |
|---|---|
| Seletor preso em "Carregando figurinhas..." | `api.frankerfacez.com` respondeu **522 depois de 19,9s**; o front esperava as três chamadas |
| 737 figurinhas de uma vez, sem paginação | o catálogo inteiro (119 KB) vinha num JSON só, montado no cliente |
| Metade da grade em quadrado vazio | `cdn.frankerfacez.com` falhou em **18 de 60** imagens (30%), com timeout de 12s |

Agora o backend monta o catálogo em memória, serve uma página por vez e passa a imagem por um cache
próprio. O navegador não fala com `api.betterttv.net` nem com `cdn.frankerfacez.com`.

## Fontes e ordem

`EmoteCatalogService` junta, nesta ordem de prioridade de arte:

| Fonte | O que traz | Ordem |
|---|---|---|
| `BttvEmoteSource` | coleção global do BTTV (65, ~0,3s) | 1ª — o código repetido fica com o BTTV, como sempre foi |
| `FfzGlobalEmoteSource` | coleção global do FFZ (13) | 2ª |
| `FfzListingEmoteSource` (`animated=true`) | as mais usadas do FFZ **com versão em movimento** (400) | 3ª — arte animada vence a estática de mesmo código |
| `FfzListingEmoteSource` (`sort=count-desc`) | as mais usadas do FFZ (600) | 4ª — dá a posição de uso |
| `EmbeddedCatalogSource` | retrato embarcado (`emotes/catalogo.json`: 1018 figurinhas — 622 populares, 372 animadas, 53 do BTTV) | última — só preenche o que ninguém trouxe |

Regras da junção:

- mesma figurinha (mesmo provedor + id) vinda de duas listagens entra uma vez, somando a posição
  popular de uma com a marcação de animada da outra;
- mesmo código com envios diferentes (o FFZ permite) fica com a arte de quem veio primeiro na
  ordem acima e recebe a posição de uso da outra;
- fonte que falha não remove nada: cada fonte guarda a última resposta boa (`lastGood`), e o
  catálogo só cresce.

## Atualização

- no boot, o catálogo nasce do retrato embarcado (ninguém encontra seletor vazio) e a montagem
  completa roda em segundo plano (`ApplicationReadyEvent`) numa **thread própria**, fora do caminho
  do readiness. Início e fim da montagem ficam no log (`Catalogo de figurinhas: montagem no boot …`):
  em QA o catálogo ficou no retrato embarcado até a rodada do cron sem que o log dissesse se a
  montagem começou — o `catch` agora pega `Throwable`, então nem erro de carregamento de classe
  termina a thread em silêncio;
- depois, `@Scheduled` de **3 em 3 horas** (`rede.emotes.refresh-cron`);
- o refresh é serializado (`synchronized`) e fora do caminho da requisição: a leitura sempre pega o
  catálogo anterior, inteiro.

## Rotas (públicas, sem sessão)

| Rota | Para quê |
|---|---|
| `GET /emotes?page=&limit=&q=&provider=` | seletor: 40 por página (máx. 100), busca por código, filtro `ALL`/`POPULAR`/`BTTV`/`FFZ` |
| `GET /emotes/index` | texto: mapa `código → endereço da imagem` (~1000 códigos ≈ 60 KB) |
| `GET /emotes/image/{provider}/{id}?variant=thumb\|full` | a imagem, servida do cache do backend |

`/emotes` e `/emotes/index` respondem com `ETag` e `Cache-Control` público (10 min e 1 h). A imagem
sai com `max-age=604800, immutable` e `nosniff`.

A imagem é isenta do gate de origem (`RequestOriginFilter`), como `/media` e `/storage`: o
`<img>` do navegador **não manda `Origin`**, e sem a isenção toda figurinha voltava 403 e a
grade e o texto ficavam sem desenho. As rotas de dados (`/emotes`, `/emotes/index`) continuam
exigindo origem cadastrada.

## Proxy de imagem

`EmoteImageCache` guarda até **48 MB** em memória, 7 dias, com o tamanho de cada imagem como peso.

- O endereço de origem é montado **só com constantes + identificador validado** (`[A-Za-z0-9]{1,40}`);
  nada que o chamador mande vira host de destino.
- Só resposta `image/*` de até **1 MB** é guardada; 404/410 são resposta definitiva, erro de rede
  ganha uma segunda tentativa.
- **Animação vem do catálogo, não de tentativa.** No FFZ, pedir o arquivo `animated/` de uma
  figurinha estática **não devolve 404 — a conexão fica pendurada até o timeout**. Tentar "para ver
  se existe" custava 12s por figurinha. Por isso o catálogo marca o que é animado (listagem
  `animated=true`) e o endereço sai certo de primeira. A queda para o estático continua como rede de
  segurança, para o caso de o CDN recusar um arquivo que o provedor diz existir.
- O endereço público de uma animada carrega `&a=1`: quando um refresh descobre a animação, a URL
  muda e o navegador não continua servindo o estático que guardou por uma semana.

Endereços de origem:

| Provedor | Grade (`thumb`) | Texto estático (`full`) | Texto animado (`full`) |
|---|---|---|---|
| BTTV | `1x.png` | `2x.png` | `1x.gif` (queda: `2x.png`) |
| FFZ | `1` | `2` | `animated/2` (queda: `2`) |

## Figurinha de nome barrado

`EmoteNamePolicy` aplica a política de conteúdo ao **código** de cada figurinha que chega das
fontes: nome que remete a conteúdo sexual **não entra no catálogo** — e o que não entra no catálogo
também não é baixado, porque `GET /emotes/image/{provider}/{id}` recusa a arte de nome barrado antes
de falar com o CDN (`EmoteCatalogService.isBlocked`). A normalização de código (minúsculas, sem
acento, sem separador, com letra trocada por número desfeita) faz o nome "parecido" cair na mesma
regra que o exato; termos fracos só casam como palavra inteira, para não barrar figurinha inocente.
Sem chave de modelo e sem denúncia: é bloqueio por nome, na entrada.

## Configuração

| Propriedade | Default | Para quê |
|---|---|---|
| `rede.emotes.refresh-enabled` | `true` | liga/desliga a montagem (teste desliga) |
| `rede.emotes.refresh-cron` | `0 0 */3 * * *` | quando a montagem roda |
| `rede.emotes.popular-pages` | `3` | páginas de 200 da listagem popular (1..20) |
| `rede.emotes.animated-pages` | `2` | páginas de 200 da listagem animada (1..20) |

Sem rede, o catálogo é o retrato embarcado — o seletor continua abrindo, com popular, animada e
BTTV, não só a lista de populares.

## Retrato embarcado (`emotes/catalogo.json`)

O arquivo no jar é o piso do catálogo: um pod que sobe sem rede serve figurinha de verdade em vez de
seletor vazio. Ele **não** é só a lista de populares — traz a coleção global do BTTV e do FFZ, as
mais usadas e as animadas, com provedor, identificador e a marcação de animação, para o seletor
nascer com comum **e** animada.

É gerado por `python3 scripts/emotes-snapshot.py`, que consulta as mesmas quatro fontes ao vivo e
aplica a mesma junção de `EmoteCatalogService.rebuild()` (na mesma ordem de prioridade de
`EmotesConfig`). O refresh agendado soma por cima; nada do arquivo é apagado. Mudou a junção no
código? Muda no script também, e o teste `EmoteSourcesTest` guarda o formato.

## Limites conhecidos

- A listagem popular do FFZ **não publica** o campo `animated`; a marcação vem da listagem
  `animated=true` (400 mais usadas). Uma figurinha animada fora desse recorte aparece estática.
- Três páginas de popular por refresh: o resto do catálogo do FFZ (334 mil) não é espelhado — o que
  vale é "as mais usadas" mais o retrato embarcado.
- O catálogo vive na memória do pod: cada réplica monta o seu. Como a montagem é de 3 em 3 horas e
  cai no retrato embarcado quando falha, réplicas podem diferir por uma rodada — não é problema de
  leitura, só de minúscula diferença de catálogo entre pods.