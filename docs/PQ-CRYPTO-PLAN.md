# Plano — criptografia pós-quântica e proteção de dados ponta a ponta

> **Status:** plano. Nada aqui está implementado até a fase correspondente estar mergeada, testada e
> provada em produção. Este documento é o contrato técnico do trabalho; o runbook operacional
> (nomes de chaves, ARNs, papéis do KMS e procedimento de emergência) **não** mora no repositório
> público — fica no cofre do time.

## 0. Onde este trabalho vive

**Branch dedicada: `sec/cripto-pq`.** Nada de criptografia entra na `qa` enquanto a funcionalidade
não estiver completa e provada — `qa` é ambiente de teste do produto, não de experimento de
criptografia. O fluxo é:

1. Cada incremento nasce de `sec/cripto-pq` (nunca de `qa`) e volta para ela por PR.
2. A branch é sincronizada com a `qa` com frequência para não apodrecer, mas **nunca o contrário**:
   `qa` não recebe nada dela.
3. Só depois de: migração de dado verificada (decifra == claro), rotação ensaiada, nenhuma falha de
   decifra em QA e revisão de segurança — a branch é promovida (`sec/cripto-pq` → `qa` → `main`).
4. O frontend tem a mesma branch `sec/cripto-pq`, pelo mesmo motivo.

## 1. Resumo executivo — o que é honesto prometer

"Ponta a ponta" tem dois significados e só um deles é possível sem mentir:

| Promessa | É possível? | Como |
|---|---|---|
| Nenhum byte trafega sem cifra forte, com resistência pós-quântica no transporte | **Sim** | TLS 1.3 com troca de chave híbrida `X25519MLKEM768` (o navegador faz sozinho), TLS/1.2 desligado para o app, HSTS, e mTLS/assinatura para clientes que controlamos |
| O servidor **não consegue ler** o conteúdo (E2E de verdade) | **Só para conteúdo específico** | Mensagens diretas, com chaves no cliente (modelo Signal). Um feed, uma busca, uma moderação e um ranking exigem que o servidor leia — para esses não existe E2E, existe cifra em trânsito + em repouso |
| Nada gravado no banco fica em claro | **Sim** | Envelope com AES-256-GCM por registro, chave embrulhada por KMS, índice cego (HMAC) para busca exata, rotação e destruição de chave (crypto-shredding) |
| Cliente de terceiro usa a API sem receber nossa chave | **Sim** | OAuth2 client credentials com escopo e token curto; API key guardada como hash Argon2id; a chave de criptografia **nunca** sai do KMS |
| Nenhum rastro de chave no código | **Sim** | Só referências (ARN/alias) por configuração; material de chave nunca entra em repositório, log, imagem ou variável versionada |

O que **não** prometemos: cifrar de forma que o servidor continue fazendo o que faz sem poder ler.
Isso não existe. O que existe é reduzir a superfície a: transporte cifrado, repouso cifrado, acesso
mínimo e auditável, e E2E verdadeiro apenas onde o produto aceitar que o servidor fique cego.

## 2. Estado atual (ponto de partida medido)

- **Java 25** — o JDK já traz **ML-KEM (JEP 496)** e **ML-DSA (JEP 497)** na biblioteca padrão. Não
  precisamos de dependência externa para PQ no backend.
- **Transporte:** TLS 1.3 no Cloudflare/nginx (clássico, sem PQ híbrido garantido hoje).
- **Repouso:** nada cifrado por campo. Tokens de sessão/refresh são **hash SHA-256** (correto: não
  precisa reverter). Segredo TOTP usa `SecretCipher` (AES-256-GCM com chave em base64 no ambiente).
- **Dados pessoais em claro:** e-mail, telefone, CPF/RG (pedidos de API), currículo, IP em log e no
  painel de monitoramento, mensagens, denúncias, dados de pagamento/Pix.
- **Passivos conhecidos:** um dump real do banco foi **commitado no passado** (commits `DUMP` e
  "foda-se a LGPD, toma o dump do banco de dados") e removido do HEAD — **continua no histórico**.
- **Clientes de terceiros:** API pública com sessão/`X-Session-Token`; não há OAuth2 de cliente nem
  chave de API hasheada com escopo.

## 3. Camada 1 — transporte (a parte que resolve 90% do risco real)

1. **TLS 1.3 obrigatório** no domínio do app e da API; `TLS 1.2` aceito **só** por clientes externos,
   com prazo e telemetria de uso; `1.0/1.1` recusados. HSTS `max-age=31536000; includeSubDomains;
   preload`.
2. **Troca de chave híbrida PQ**: `X25519MLKEM768` no edge (Cloudflare já suporta) e no terminador
   do backend. Híbrido é a escolha certa: se o ML-KEM cair, o X25519 segura a confidencialidade.
   - Provas: `openssl s_client -groups X25519MLKEM768` negocia o grupo; teste automatizado no CI
     contra a QA, falhando se o grupo não for aceito; relatório mensal de clientes sem PQ.
3. **Certificados**: ECDSA P-256 (ou RSA-3072) com OCSP stapling. Certificado com assinatura PQ
   (ML-DSA) ainda não é prático nas CAs públicas — entra como acompanhamento, não como bloqueio.
4. **Clientes que controlamos** (apps futuros, integrações servidor-a-servidor): mTLS + **token JWT
   assinado com ML-DSA** (JDK 25), com `kid` e rotação. Navegador não assina com PQ hoje — por isso a
   assinatura PQ fica no servidor-a-servidor.
5. **Superfície fora do TLS**: nenhum endpoint, webhook ou health pode ficar fora. O gateway que
   repassa `/api/*` autentica mais forte do que "tem sessão" (ver camada 4).

## 4. Camada 2 — aplicação (defesa em profundidade, com escopo claro)

Objetivo: mesmo que um intermediário (proxy, CDN, log de borda, inspeção TLS de rede corporativa)
veja o corpo, ele não leia o dado. **Isto não protege do nosso servidor** — protege do caminho.

- Handshake de sessão: o cliente gera um par **X25519** efêmero; o servidor responde com o par dele;
  ambos derivam uma chave de sessão (HKDF-SHA256). Onde o cliente suportar ML-KEM (WebCrypto ainda
  não expõe, mas libs WASM expõem), o segredo combinado é `X25519 || ML-KEM` — mesmo desenho híbrido
  do TLS.
- Corpo sensível vai em envelope **AES-256-GCM** com **AAD** amarrando `método + rota + requestId +
  timestamp + id do usuário`. AAD é o que impede mover um corpo cifrado de uma rota/registro para
  outro, ou reusar uma resposta antiga.
- Anti-replay: janela de 60 s + cache de `requestId` no servidor; requisição repetida é recusada com
  erro explícito (nunca "talvez funcione").
- Escopo inicial (onde dói mais): login/2FA, cadastro, troca de e-mail/senha, dados de pagamento,
  currículo, importações (LinkedIn/ZIP), webhooks.
- **Não** se aplica a conteúdo que o servidor precisa indexar/servir — ali vale a camada 1 e a 3.

## 5. Camada 3 — repouso (banco, arquivos, backup)

Formato de envelope versionado, único no sistema:

```
v1:<kid>:<base64(iv || ciphertext || tag)>
```

- **AES-256-GCM**, IV de 12 bytes aleatório por registro, AAD = `tabela:coluna:id` (impede copiar
  cifra de uma linha para outra).
- **Envelope encryption de verdade**: cada registro (ou cada tenant, para custo) tem uma **DEK**;
  a DEK é embrulhada por uma **KEK** que vive no **KMS** (AWS KMS, mesma conta do S3 que já usamos).
  O banco guarda só a DEK embrulhada. **Nenhuma chave em claro no banco.**
- **Índice cego** para busca exata: `HMAC-SHA256(pepper, valor_normalizado)` numa coluna própria com
  índice. Serve para "achar por e-mail", não para `LIKE`/ordenação/agregação.
- **Consequências aceitas e documentadas**: ordenar, filtrar por faixa e agregar sobre campo cifrado
  exige coluna derivada não sensível (ex.: `created_at` continua claro; `salary` pode ter uma faixa
  derivada sem o valor exato, se o produto precisar).
- **Colunas que crescem**: ciphertext em base64 ocupa ~1,4× o claro. Toda migração que cifra um campo
  **primeiro** amplia a coluna (`VARCHAR(n)` → `TEXT`), com charset/collation explícitos.
- **Arquivos e logs**: mídia no S3 com SSE-KMS; o arquivo de eventos do painel contém IP (dado
  pessoal) → passa a gravar IP truncado/derivado por HMAC e o arquivo é cifrado em repouso; backups
  cifrados com a mesma KEK, com teste de restauração trimestral.
- **Mensagens (E2E de verdade)**: trilha separada, decisão de produto. Só entra se aceitarmos que
  moderação/denúncia nessas conversas passa a ser limitada — o desenho é Signal-like (X3DH + Double
  Ratchet, chaves no cliente, servidor cego). Sem essa decisão, mensagens seguem nas camadas 1 e 3.

## 6. Camada 4 — clientes de terceiros sem expor chave

- **OAuth2 client credentials** para integrações: `client_id` público + `client_secret` guardado como
  **Argon2id** (nunca reversível), token de acesso **curto** (15 min) com escopo explícito e `aud`.
- **Chave de API** (quando o cliente não fala OAuth): formato `pk_live_<id>.<segredo>` — o `id`
  identifica sem revelar, o segredo só existe no hash. Revogação por `id`, rotação com sobreposição.
- **mTLS** opcional para servidor-a-servidor: certificado do cliente emitido por nós, rotação
  automática.
- **A chave de criptografia nunca é entregue a cliente nenhum.** Cliente de terceiro autentica e
  fala com a API; a cifra de repouso é problema nosso e morre no nosso perímetro.
- Escopo por módulo (o catálogo de `/developers` já define módulos) + rate limit por cliente +
  auditoria de uso (quem chamou o quê, sem corpo).
- Token de cliente assinado com **ML-DSA** e verificação híbrida (aceita ES256 enquanto a frota de
  clientes não suportar PQ, com prazo e telemetria).

## 7. Gestão de chaves (o que separa plano de teatro)

- **KMS/HSM** para KEKs; DEKs só em memória, com cache curto e limite.
- **Sem chave no código, na imagem, no log ou em variável versionada.** O que existe na configuração é
  o **alias/ARN**; a permissão é por papel do pod (IRSA no cluster, credencial do host na VPS).
- **Rotação**: KEK anual e sob suspeita; DEK por registro permite rotação sem reescrever tudo
  (re-embrulhar, não re-cifrar). Envelope carrega `kid`, então convivem versões.
- **Crypto-shredding** para LGPD: apagar a DEK de um registro o torna irrecuperável sem tocar no
  backup — é o mecanismo de exclusão definitiva.
- **Separação de deveres**: quem opera o banco não tem permissão de KMS; quem tem KMS não lê o banco.
- **Break-glass**: procedimento documentado (fora do repo público), com auditoria e alerta.

## 8. Incremental sem corromper dado (a parte que dá medo, resolvida)

Para **cada** campo que vira cifrado, sempre na mesma ordem, em PRs separados:

1. **Aditiva**: nova coluna `<campo>_enc` (TEXT) + `<campo>_bidx` (índice cego, com índice).
2. **Dual-write**: toda escrita grava claro e cifrado (a leitura continua no claro).
3. **Backfill** em lotes (1.000 por vez, com pausa), idempotente, com métrica de progresso e de
   falha por linha.
4. **Verificação**: job que decifra e compara com o claro; divergência = alarme, nunca "quase igual".
5. **Dual-read**: leitura tenta o cifrado e cai para o claro (com contador de fallback).
6. **Corte**: quando o fallback zera por N dias, a leitura passa a exigir o cifrado.
7. **Limpeza**: só então o claro é apagado (migração aditiva, nunca editando migração aplicada), com
   a coluna antiga ficando `NULL`-able por um ciclo antes de sair.

Provas obrigatórias em cada etapa: teste com H2 **e** com MariaDB (o job `Schema x migrations` já
faz esse duplo boot), teste de idempotência (rodar o backfill duas vezes não muda o resultado),
teste de envelope inválido (falha explícita, nunca dado truncado), e restauração de backup ensaiada.

## 9. Fases, critérios de aceite e provas

| Fase | Entrega | Aceite (prova) |
|---|---|---|
| **P0 — agora** | Classificação dos dados campo a campo; threat model; ADRs; purga do dump do histórico do git; inventário de fluxos (browser, edge, backend, DB, S3, logs, painel, e-mail, terceiros) | documento revisado + `git log --all -- dump` vazio + classificação aprovada |
| **P1 — transporte PQ** | `X25519MLKEM768` no edge e no backend; TLS 1.2 só para clientes externos com prazo; HSTS | teste automatizado de negociação PQ no CI; relatório de clientes sem PQ |
| **P2 — repouso (PII crítica)** | Envelope + KMS para e-mail, telefone, documento, currículo, dados de pagamento e IP do painel; índice cego para busca por e-mail | backfill verificado 100%; falha de decifra = 0; restore de backup testado |
| **P3 — aplicação** | Envelope por requisição nos endpoints sensíveis (login/2FA, cadastro, pagamento, importação) | teste de AAD (não aceita corpo de outra rota/registro), anti-replay, e 0 regressão funcional |
| **P4 — terceiros** | OAuth2 client credentials + API key hasheada + escopo + mTLS opcional | cliente de teste ponta a ponta; revogação em < 1 min; nenhuma chave de cripto exposta |
| **P5 — E2E de mensagens** | Decisão de produto + desenho Signal-like | só entra com moderação/denúncia redesenhadas e aprovadas |
| **P6 — operação** | Rotação, drills, auditoria contínua, métricas | rotação ensaiada sem downtime; crypto-shredding provado |

Sequência obrigatória: **P0 → P1 → P2 → P3 → P4**. P5 depende de decisão de produto; P6 atravessa
todas.

## 10. Riscos e não-objetivos

- **Performance**: cifra por campo custa CPU e espaço; medir p95 antes/depois em cada fase (o painel
  já dá p50/p95/p99).
- **Busca**: campo cifrado não tem `LIKE`/ordenação; cada caso precisa de coluna derivada ou índice
  cego — decisão de produto, registrada.
- **Chave perdida = dado perdido**: por isso KMS com backup de política, alarme de acesso e teste de
  restauração; e por isso DEK por registro (perder uma não derruba tudo).
- **Dispositivo comprometido**: nada disso protege contra o cliente já infectado. Fica explícito.
- **Não é objetivo**: tornar o servidor cego para feed, busca, moderação e ranking.

## 11. Ações imediatas (antes de qualquer fase)

1. **Purgar o dump do histórico do git** e rotacionar tudo que ele expunha. Enquanto o histórico
   existir, o resto do plano é cosmético.
2. **Classificar os campos** (PII / sensível / público) — é o que define o que cifra primeiro.
3. **Habilitar KMS** e criar as KEKs por ambiente (dev/qa/prd) com políticas separadas.
4. **Medir**: p50/p95 por rota e volume por tabela, para saber o custo real antes de cifrar.

## 12. Como medimos sucesso

- Negociação PQ presente em ≥ 95% das conexões dos navegadores atuais (relatório semanal).
- 100% dos campos classificados como PII cifrados, com 0 falha de decifra em produção.
- 0 chave em código/histórico/imagem (varredura automatizada no CI).
- Tempo de revogação de cliente de API < 1 min; tempo de rotação de KEK sem downtime.
- Backfill sempre com contador de progresso e verificação de igualdade — nunca um "deve ter dado
  certo".
