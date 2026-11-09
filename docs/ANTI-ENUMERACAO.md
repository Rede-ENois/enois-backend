# Proteção contra enumeração e scraping

Camada central que detecta **descoberta de recursos** (pedir um identificador atrás do outro para
descobrir o que existe) e responde de forma proporcional. Vive no backend, num filtro só — nenhum
controller repete a regra.

## Por que não é só contar requisições

O limitador global já conta requisições por IP (300/min). Mesmo assim um script varre dezenas de
rotas antes de bater no teto, e uma pessoa com várias abas abertas produz volume parecido com o de
um scanner lento. O que separa os dois é a **forma** do tráfego:

| Sinal | Navegação | Enumeração |
| --- | --- | --- |
| Identificadores distintos na janela | poucos, repetidos | um novo a cada requisição |
| Ordem dos identificadores | aleatória | `alvo1`, `alvo2`, `alvo3`… |
| Proporção de 404 | baixa | alta (descobrindo o que existe) |
| Rotas usadas | as da tela | várias rotas de descoberta em sequência |

## Peças

| Peça | Onde | Papel |
| --- | --- | --- |
| `EnumerationSurface` | `shared/security/` | Lista declarativa de rotas monitoradas e qual identificador cada uma expõe |
| `EnumerationRiskEngine` | `shared/security/` | Pontua a janela de uma chave e devolve nível + motivos |
| `EnumerationGuardFilter` | `shared/security/` | Liga os dois; decide negar ou só observar |
| `EnumerationAdminController` | `moderation/adapters/in/web/` | Painel: modo, chaves observadas, bloqueios, liberação manual |

## Superfície monitorada

**Descoberta de pessoas** — `/users/{username}`, `/users/{username}/presence`,
`/users/{username}/profile`, `/users/search`, `/users`.

**Recursos com identificador** — `/conversations/{id}`, `/conversations/{id}/messages`,
`/calls/{callId}`, `/events/{eventId}/calls/open`, `/posts/{id}`, `/posts/{id}/comments`,
`/comments/{id}`, `/communities/{id}`, `/jobs/{id}`, `/events/{id}`, `/mentors/{id}`.

**Autenticação** — `/auth/login`, `/auth/password/forgot`, `/auth/signup`,
`/auth/username-available`, `/auth/email-available`.

**Administração** — `/admin/**` (varredura de endpoints é sinal por si).

**Rotas quentes da tela (só volume, nunca enumeração)** — `/conversations`,
`/conversations/unread-count`, `/conversations/stream`, `/me/presence/heartbeat`, `/feed`,
`/actuator/**`. A lista veio do tráfego real: são as mais chamadas da produção, e abrir o app uma
vez dispara várias delas. Contá-las como enumeração puniria todo mundo.

## Pontuação

| Sinal | Pontos |
| --- | --- |
| ≥ 12 identificadores distintos | 20 |
| ≥ 25 identificadores distintos | +25 |
| ≥ 50 identificadores distintos | +25 |
| Sequência previsível (≥ 4 saltos de ±1/±2) | 25 |
| ≥ 60% de 404 (com ≥ 8 amostras) | 20 |
| Consulta distribuída em ≥ 4 rotas | 10 |

Níveis: **SUSPECT** (≥30), **HIGH** (≥55), **CONFIRMED** (≥80), **SEVERE** (confirmado + sequência +
404 alto).

## Modos de operação

`rede.security.enumeration.mode`:

- **`off`** — não observa nada.
- **`shadow`** (padrão) — observa, registra e mostra no painel, mas **nunca nega**. É o modo para
  validar os limiares com tráfego real antes de ligar o bloqueio.
- **`enforce`** — nega conforme o nível.

**Ligar `enforce` é decisão de operação, não de código.** Antes: revisar
`GET /admin/enumeration/observed` por alguns dias e confirmar que nenhuma chave legítima aparece
com nível alto.

## Resposta automática (só em `enforce`)

| Nível | Ação |
| --- | --- |
| SUSPECT | registra `USER_ENUMERATION_SUSPECTED` |
| HIGH | registra `API_SCRAPING_DETECTED` / `AUTOMATED_RESOURCE_DISCOVERY` |
| CONFIRMED | bloqueio temporário progressivo (5 min, 10, 20… até o teto) |
| SEVERE | bloqueio no teto + `AUTOMATED_IP_BAN` |

O bloqueio é **sempre temporário e progressivo** — nunca ban permanente por heurística. A duração
escala na reincidência e expira sozinha.

## Falsos positivos

Rede compartilhada, NAT e VPN fazem muita gente dividir o mesmo IP. As defesas:

1. **Identidade autenticada tem balde próprio** (`u:<userId>`): quem está logado não é punido pelo
   vizinho de NAT. Sem sessão válida, a chave é o IP (`ip:<ip>`).
2. **Rotas quentes da tela nunca contam** como enumeração.
3. **A janela é curta** (120 s) e a pontuação decai.
4. **O sinal de sequência exige ordem**, não só quantidade.
5. **O bloqueio é temporário** e expira sozinho.

## Bypass

- A janela é **por chave, não por rota**: pedir `/users/a`, `/users/a/presence` e `/users/a/profile`
  soma no mesmo balde. Trocar de endpoint não reinicia a contagem.
- O IP vem do `ClientIpResolver`, que só confia em cabeçalho vindo de proxy confiável e lê o
  `X-Forwarded-For` da direita para a esquerda (o item forjado pelo cliente nunca vale).
- `CF-Connecting-IP` só é aceito quando o salto anterior é uma borda do Cloudflare.

## Limitação conhecida: contadores por instância

Os contadores vivem em memória, como os demais limitadores do projeto (`IpRateLimiter`,
`UserRateLimiter`, `AbuseGuardFilter`). Com mais de uma réplica, cada pod tem a sua janela: um
atacante distribuído entre réplicas vê o limiar dividido pelo número de pods.

**Não há Redis no projeto hoje.** Para o bloqueio valer entre instâncias, é preciso um contador
compartilhado — decisão de infraestrutura, registrada aqui como próximo passo. Enquanto isso, o
modo `shadow` é o lugar seguro: a observação por pod já mostra o padrão, e o bloqueio (quando
ligado) ainda reduz muito o custo do ataque.

## Painel

| Rota | O que mostra |
| --- | --- |
| `GET /admin/enumeration/status` | modo, chaves sob observação, bloqueios ativos |
| `GET /admin/enumeration/observed` | chaves pontuadas, nível, pontuação e motivos |
| `GET /admin/enumeration/blocks` | bloqueios ativos com o tempo restante |
| `POST /admin/enumeration/blocks/{key}/release` | libera um bloqueio antes do prazo |

Todas exigem admin (`users.is_admin`).

## Eventos registrados

`USER_ENUMERATION_SUSPECTED`, `USER_ENUMERATION_CONFIRMED`, `API_SCRAPING_DETECTED`,
`AUTOMATED_RESOURCE_DISCOVERY`, `AUTOMATED_IP_BAN`. Cada linha traz a chave, a rota, o nível, a
pontuação, a quantidade de identificadores distintos, a proporção de 404 e os motivos.

## Testes

`EnumerationRiskEngineTests` (9) — sequência de usernames, sequência de ids, proporção de 404,
bypass por troca de rota, navegação legítima, rotas quentes, balde por identidade, janela que escoa.

`EnumerationSurfaceTests` (5) — as rotas reais de produção, quais expõem identificador e quais são
quentes.

`EnumerationGuardFilterTests` (7) — shadow não nega, enforce bloqueia, navegação legítima passa,
rota fora da superfície passa, modo off, modo inválido cai no shadow, liberação manual.
