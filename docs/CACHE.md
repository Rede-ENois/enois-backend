# Cache de leitura

Política de cache das leituras públicas do backend: o que é guardado, por quanto tempo, quando é
descartado e o que **não** é cacheado. Tudo em memória do processo (Caffeine) — sem Redis, sem
infraestrutura nova.

## Regra que vale para tudo

**TTL é o tempo máximo de validade de uma entrada, não um intervalo de consulta.** Nada aqui agenda
`SELECT`: a entrada vence e a próxima requisição que precisar dela paga a consulta e repõe o cache.
Recurso que ninguém abre não gera consulta nenhuma. Quando a entrada vence e várias requisições
chegam juntas, `@Cacheable(sync = true)` faz uma consultar e as outras esperarem o resultado dela —
sem avalanche.

## Categorias

Os valores ficam em `application.properties` (`rede.cache.*`), lidos por
[`CacheProperties`](../src/main/java/com/socialcoisa/rede/shared/application/CacheProperties.java).
As categorias dinâmicas herdam `REDE_CACHE_DYNAMIC_TTL` (padrão **20s**).

| Cache | O que guarda | Chave | TTL padrão | Teto |
| --- | --- | --- | --- | --- |
| `publicProfiles` | projeção pública do perfil (sem e-mail, sem dado de sessão) | `u:<username>` / `i:<id>` | 20 s | 5000 |
| `publicRanking` | vitrine "quem está chegando agora" | `limit` | 20 s | 64 |
| `communitiesList` | vitrine de comunidades (filtros + paginação) | filtros + cursor + limit | 20 s | 500 |
| `eventsList` | vitrine de eventos | filtros + cursor + limit | 20 s | 500 |
| `jobsList` | vitrine de vagas internas | filtros + cursor + limit | 20 s | 500 |
| `communityStats` | contador da landing | — | 10 min | 4 |
| `externalJobs` | vagas importadas | consulta | 5 min | 16 |
| `pagePreviewCards` | cartão de compartilhamento (PNG) | post | 1 h | 16 |
| `gifSearch` | busca de GIF | termo + limit | 10 min | 200 |
| `githubRepositories` / `githubReadme` | importação do perfil | login | 3 min | 200 |

**Só entra no cache o que é igual para todo mundo.** O que é do leitor — `joined` (sou membro),
`attending` (vou participar), `applied`/`saved` (já me candidatei, salvei), contagem de candidaturas
do dono — é calculado no controller, depois do cache, em consulta em lote. O cache guarda o recorte
público; a visão de cada leitor é montada por requisição. Prova em
[`PublicReadCacheHttpTests`](../src/test/java/com/socialcoisa/rede/shared/web/PublicReadCacheHttpTests.java).

## Invalidação

Escrita que muda o que está em cache derruba a entrada (`CacheInvalidator`), sempre **depois do
commit**: se a transação falhar, o cache continua como estava; um dado que não commitou nunca é
servido.

| Escrita | Descarta |
| --- | --- |
| criar/editar/entrar/sair/apagar comunidade | `communitiesList` |
| criar/editar/recorrência/apagar/confirmar presença em evento | `eventsList` |
| publicar/editar/fechar/apagar vaga | `jobsList` |
| editar perfil, trocar de usuário, ligar/desligar ranking, declarar idade | as entradas **daquela pessoa** em `publicProfiles` (id, nome antigo e nome novo) + `publicRanking` |

Perfil é invalidado por chave, não limpando o cache inteiro: uma edição não custa a leitura de todo
mundo.

**O cache é por pod.** Limpar aqui limpa o pod que atendeu a escrita; os outros servem a versão
anterior até o TTL vencer. O TTL é a garantia de convergência; a invalidação é a aceleração dentro do
pod que escreveu. Para o dado de vitrine, 20 s de atraso no pior caso é o contrato, não um defeito.
Cache compartilhado (Redis) só se um dia o atraso de 20 s incomodar de verdade — hoje não incomoda e
não vale a dependência.

## Camada HTTP

`PublicReadCacheConfig` fecha o caminho navegador ↔ API para as leituras públicas:

- `ShallowEtagHeaderFilter` calcula um ETag do corpo; o navegador manda `If-None-Match` e leva
  **304 sem corpo** quando nada mudou.
- `Cache-Control: public, max-age=0, must-revalidate` — o corpo pode ficar guardado, mas nunca é
  servido sem conferir. A economia é o 304, não servir dado velho.
- **Requisição com sessão não entra nessa política**: `SecurityHeadersFilter` põe `no-store` em toda
  resposta autenticada, o interceptor não sobrescreve quem já decidiu, e o filtro de ETag ignora
  resposta `no-store`. Visão de leitor logado nunca vira cache público.
- Fora da lista: streaming, upload, mídia e qualquer escrita.

No front, o HTML da aplicação sai `no-store` (é a porta do app e carrega nonce de CSP de uso único) e
o `sw.js` sai `no-cache`, com `updateViaCache: "none"` no registro do service worker.

## Consultas que eram o gargalo

O cache não substitui consulta ruim. O que foi corrigido junto:

- **Vitrine de comunidades**: duas consultas por comunidade (contagem de membros e "sou membro")
  viraram duas por página, com `group by` — de ~40 consultas por página de 20 para 3.
- **Vitrine de eventos**: uma contagem de presenças por card virou uma consulta por página.
- **Leitura de perfil**: `populateCounts` chamava `repository.save(...)` numa transação `readOnly` —
  o `save` virava `merge` (SELECT da linha) em toda abertura de perfil, sem nunca persistir nada.
  Removido.
- **Índices** (`V232__read_list_indexes.sql`): `users(created_at)`, `communities(created_at)` e
  `events(created_at)` para as vitrines que paginam por `created_at DESC`. Sem eles a primeira página
  varria e ordenava a tabela inteira.

## Como medir e ajustar

- Cada cache tem `recordStats()`: `requestCount`, `hitCount` e `evictionCount` do pod.
- Para mudar o frescor de uma categoria sem deploy de código: `REDE_CACHE_DYNAMIC_TTL=5s` (todas as
  dinâmicas) ou `rede.cache.public-profiles.ttl=5s` (uma só).
- Teste de comportamento (hit, expiração sob demanda, renovação, concorrência, falha de escrita sem
  sujeira): [`IntelligentCacheTests`](../src/test/java/com/socialcoisa/rede/shared/application/IntelligentCacheTests.java).
- Teste de invalidação cirúrgica do perfil:
  [`ProfilePublicCacheTests`](../src/test/java/com/socialcoisa/rede/profile/application/ProfilePublicCacheTests.java).

## O que não é cacheado

Feed, notificações, mensagens, conteúdo de post, reações, perfil próprio (`/me`) e todo dado que
depende de sessão. Continuam sendo leitura direta, com as regras de paginação por cursor que já
existiam. Respostas de escrita nunca passam pelo cache.
