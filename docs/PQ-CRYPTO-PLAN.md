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
2. **Nenhum PR desta branch pode ter `qa` como base.** A sincronização é sempre `qa` →
   `sec/cripto-pq` (para não apodrecer), nunca o contrário. Um PR com base `qa` é o erro a evitar:
   já aconteceu de o `gh pr edit` não trocar a base e o merge cair no lugar errado — confira a base
   antes de mergear.
3. Só depois de **tudo** isto a branch é promovida (`sec/cripto-pq` → `qa` → `main`):
   - migração de dado verificada (decifra == claro, 100% das linhas, com contador);
   - rotação de chave ensaiada sem downtime;
   - zero falha de decifra e zero fallback para claro nos últimos dias de QA;
   - suíte completa verde, incluindo o boot duplo (H2 e MariaDB);
   - revisão de segurança registrada no PR de promoção.
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
- **Clientes de terceiros:** API pública com sessão/`X-Session-Token`; não há OAuth2 de cliente nem
  chave de API hasheada com escopo.

## 3. Camada 1 — transporte (a parte que resolve 90% do risco real)

1. **TLS 1.3 obrigatório** no domínio do app e da API; `TLS 1.2` aceito **só** por clientes externos,
   com prazo e telemetria de uso; `1.0/1.1` recusados. HSTS `max-age=31536000; includeSubDomains;
   preload`.
2. **Troca de chave híbrida PQ**: `X25519MLKEM768` no edge (Cloudflare já suporta) e no terminador
   do backend. Híbrido é a escolha certa: se o ML-KEM cair, o X25519 segura a confidencialidade.
   - Provas: `openssl s_client -groups X25519MLKEM768` negocia o grupo; sonda automatizada contra os
     hosts de produção, falhando o deploy se o grupo não for aceito; na QA a sonda é **informativa**
     enquanto a borda de lá não suportar o grupo (ver §9.1 e §16); relatório mensal de clientes sem PQ.
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
| **P0 — agora** | Classificação dos dados campo a campo; threat model; ADRs; inventário de fluxos (browser, edge, backend, DB, S3, logs, painel, e-mail, terceiros) | documento revisado + classificação aprovada |
| **P1 — transporte PQ** | `X25519MLKEM768` no edge e no backend; TLS 1.2 só para clientes externos com prazo; HSTS | teste automatizado de negociação PQ no CI; relatório de clientes sem PQ |
| **P2 — repouso (PII crítica)** | Envelope + KMS para e-mail, telefone, documento, currículo, dados de pagamento e IP do painel; índice cego para busca por e-mail | backfill verificado 100%; falha de decifra = 0; restore de backup testado |
| **P3 — aplicação** | Envelope por requisição nos endpoints sensíveis (login/2FA, cadastro, pagamento, importação) | teste de AAD (não aceita corpo de outra rota/registro), anti-replay, e 0 regressão funcional |
| **P4 — terceiros** | OAuth2 client credentials + API key hasheada + escopo + mTLS opcional | cliente de teste ponta a ponta; revogação em < 1 min; nenhuma chave de cripto exposta |
| **P5 — E2E de mensagens** | Decisão de produto + desenho Signal-like | só entra com moderação/denúncia redesenhadas e aprovadas |
| **P6 — operação** | Rotação, drills, auditoria contínua, métricas | rotação ensaiada sem downtime; crypto-shredding provado |

Sequência obrigatória: **P0 → P1 → P2 → P3 → P4**. P5 depende de decisão de produto; P6 atravessa
todas.

### 9.0 Estado da implementação (2026-10-26)

| Peça | Situação |
|---|---|
| Envelope `v1:<kid>:<base64>` (AES-256-GCM + AAD) | **implementado e testado** (`shared/crypto/EnvelopeCipher`) |
| Canal de aplicação (ECDH P-256 + HKDF, handshake, sessão com TTL) | **implementado e testado** (`POST /crypto/session`) |
| Filtro que cifra/decifra corpo por rota | **implementado e testado**, desligado até a rota entrar em `rede.crypto.protected-paths` (ver §17) |
| Interruptor seguro (desmarcar rota sem quebrar o front que já cifra) | **implementado e testado** — envelope é aberto mesmo em rota desmarcada |
| Cofre (Transit) para a chave de dados | **implementado e testado** (`VaultTransitClient`/`VaultBackedKeyProvider`) |
| Front (WebCrypto: handshake + envelope por requisição) | **implementado e testado**, ligado por `VITE_CRYPTO_PATHS` (vazio no padrão) |
| Tabela `data_keys` (V183) + store + bootstrap | **implementado e testado**, atrás de `rede.crypto.at-rest.enabled=false` |
| Backfill dos campos em repouso | próximo incremento (depende do cofre no ar, §15.1) |
| Cifra em repouso ligada a campos | próximo incremento |
| Perfil: allowlist do que é público | **implementado e testado** (`UserProfileViewExposureTests`) |
| Reenvio de confirmação sem revelar cadastro | **implementado e testado** (teto uniforme por e-mail) |
| Transporte PQ | ver 9.1 |

### 9.1 P1 — medido em 2026-10-26

A sonda `scripts/pq-tls-check.sh` (OpenSSL 3.5 em contêiner — o 3.0 das imagens não conhece o grupo e
"passaria" sem provar nada) mostrou que o transporte já está heterogêneo:

| Host | Negociação |
|---|---|
| `api.enois.ia.br` | **`X25519MLKEM768`** — pós-quântico híbrido ativo |
| `enois.ia.br` | clássico |
| `enois-qa.b7cloud.tech` | clássico |
| `enois-api-qa.b7cloud.tech` | clássico |

Parte da borda já negocia PQ e parte não. Fechar a diferença é configuração de borda
(Cloudflare/terminador TLS), não código — bloqueio de infraestrutura registrado. O script roda antes e
depois da mudança e sai com 1 quando algum host não negocia, para virar verificação de deploy.

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

1. **Classificar os campos** (PII / sensível / público) — é o que define o que cifra primeiro.
2. **Habilitar KMS** e criar as KEKs por ambiente (dev/qa/prd) com políticas separadas.
3. **Medir**: p50/p95 por rota e volume por tabela, para saber o custo real antes de cifrar.
4. **Varredura de segredo no CI**: o build falha se aparecer material de chave no código.

> Tratamento de qualquer incidente de dados segue fora deste documento e fora do repositório público.

## 12. Como medimos sucesso

- Negociação PQ presente em ≥ 95% das conexões dos navegadores atuais (relatório semanal).
- 100% dos campos classificados como PII cifrados, com 0 falha de decifra em produção.
- 0 chave em código/histórico/imagem (varredura automatizada no CI).
- Tempo de revogação de cliente de API < 1 min; tempo de rotação de KEK sem downtime.
- Backfill sempre com contador de progresso e verificação de igualdade — nunca um "deve ter dado
  certo".

## 13. Escopo essencial (decisão de 2026-10-26)

Cifrar **tudo** não entra: em leitura de alto volume (feed, listagens, busca, ranking) a cifra
quebraria índice e ordenação e apareceria como lentidão. O alvo é **tráfego de dado do usuário e o que
é essencial**.

### 13.1 O que entra no canal (corpo cifrado)

| Grupo | Rotas | Por quê |
|---|---|---|
| Credenciais e sessão | `/auth/login`, `/auth/signup`, `/auth/2fa/{verify,resend}`, `/auth/email/{verify,resend}`, redefinição de senha, `/auth/refresh` | senha e código não podem aparecer em proxy/log |
| Identidade e documentos | `/identity/verification`, `/age/parental/*` | CPF/RG e dados do responsável |
| Perfil e PII | `/me/*` (e-mail, telefone, nascimento, currículo, links) | dado pessoal do usuário |
| Candidatura e vaga | `/jobs/{id}/applications`, `/jobs`, `/jobs/external` | currículo, respostas pessoais, contato |
| Suporte | `/support/requests` | o formulário de acesso à API pede nome, RG, CPF, e-mail e telefone |
| Mensagens | `/conversations/direct`, `/conversations/{id}/messages` | conteúdo privado |
| Dinheiro | `/marketplace/{listingId}/buy`, `/purchases/*`, `/coin/cotacao` | pagamento/carteira |
| Chaves de integração | `/developers/origins` | origem/chave de terceiro |

### 13.2 O que fica de fora (de propósito)

Conteúdo público e de alto volume — posts, comentários, reações, listagens, vitrine, ranking, busca.
Esses continuam protegidos pelo TLS e nada mais; cifrá-los custaria latência e quebraria consulta sem
proteger "dado do usuário" no sentido que importa.

### 13.3 Ordem de ligação (sem quebrar quem já está na rua)

1. **Front primeiro**: handshake + envelope nas rotas do 13.1, com re-handshake no vencimento.
2. **Janela de transição por rota**: o servidor aceita corpo em claro e registra a ocorrência (métrica
   de "legado"), para medir quanto tráfego antigo ainda existe.
3. **Fechar**: rota marcada em `rede.crypto.protected-paths` (fail-closed) só quando o "legado" zerar.
4. **Prova de latência**: p50/p95 da rota antes e depois de ligar — se piorar de forma visível, a rota
   sai do escopo.

## 14. Privacidade por arquitetura — o que o Énóis precisa saber (decidido em 2026-10-26)

A pergunta que organiza tudo: **quais dados o Énóis precisa conhecer para funcionar e quais precisam
continuar inacessíveis até para nós?** Privacidade não pode depender de confiar em quem administra o
servidor; precisa de proteção técnica.

### 14.1 Classificação — o servidor precisa ler?

| Dado | Precisa? | Consequência |
|---|---|---|
| Conteúdo de mensagem direta | **Não** — só entregar | cifra no cliente; servidor guarda blob cifrado e fica cego |
| Anexo de mensagem | **Não** | cifrado no cliente antes do upload; servidor hospeda bytes opacos |
| E-mail da conta | sim (login/recuperação) | guardado, com exposição controlada e cifra em repouso |
| Telefone | **só se houver função que use** | hoje não há; não deve ser público nem consultável |
| Nome, @, bio, foto, skills | sim — é o produto | público **por decisão explícita**, campo a campo (14.3) |
| CPF/RG (API e suporte) | sim, para verificação | cifrado em repouso, acesso auditado |
| Currículo | sim, para entregar ao contratante | cifrado em repouso; entrega por link assinado e expirável |
| Post/comentário | sim — público por definição | TLS + integridade; cifrar aqui não protege nada |
| Metadados de conversa (quem, quando, volume) | sim, para rotear | reduzir com *sealed sender*; o resto é limite honesto |
| Senha, códigos 2FA, tokens | só o hash | nunca reversível (já é assim) |

### 14.2 Mensagens privadas — E2E como arquitetura (trilha P5)

O mínimo para "o servidor não consegue ler" ser garantia, e não promessa:

1. **Identidade por dispositivo** (assinatura + troca de chave) publicada num **diretório de chaves com
   log de transparência** — o servidor não troca a chave de ninguém sem isso aparecer.
2. **X3DH** para abrir a sessão e **Double Ratchet** por mensagem: sigilo futuro e recuperação
   pós-comprometimento.
3. **Multi-dispositivo**: sessão por aparelho, lista assinada pela identidade, aparelho novo aprovado.
4. **Servidor cego**: roteia, guarda ciphertext, entrega. **Push sem conteúdo** — hoje a notificação
   leva um pedaço do texto, o que já fura a promessa.
5. **Denúncia sem backdoor**: o cliente encaminha a mensagem denunciada **com o ciphertext original**;
   o servidor confere que é o mesmo que está guardado (não dá para forjar) e o moderador lê só aquilo.
6. **Backup cifrado no cliente**; senão o backup vira o ponto cego.

**O que se perde, dito sem enfeite:** busca no conteúdo das DMs, moderação automática por texto,
heurística de spam por conteúdo; pedido judicial entrega ciphertext + metadados. Em troca, a confiança
deixa de depender de quem opera o servidor. Moderação passa a ser por denúncia + metadados.

### 14.3 Perfil — política de exposição

- **Allowlist explícita** do que é público (nome, @, bio, skills, foto); cada campo novo entra por
  decisão registrada e com teste que falha se vazar.
- **E-mail e telefone nunca saem em resposta pública**, nem em busca, nem em listagem.
- **Contato por mediação**: o interessado pede contato, o servidor entrega a mensagem ao dono, o
  endereço não trafega.

### 14.4 Camadas que **não** são confidencialidade

Rate limit, paginação e cursor com TTL são **anti-extração**: dificultam varredura em massa e não
transformam dado público em privado. Watermark rastreia cópia distribuída a um destinatário; não
impede extração nem identifica com confiança (colusão, reencode, print). Nenhum dos dois substitui
cifra.

### 14.5 O que hoje fura a promessa (evidência no código)

- `MessageJpaEntity.text` guarda o texto **em claro**.
- ~~`MessagingService` monta preview de até 80 caracteres e grava numa notificação~~ — **corrigido**:
  a notificação passou a dizer só que chegou algo (ver §14.6, item 1).
- `ModerationAiService` (`ReportedContentReader`) e `SuspiciousContentSweepWorker` leem/varrem
  conteúdo — enquanto DMs forem legíveis, elas são superfície de moderação automática, exatamente o
  que o E2E remove.

### 14.6 Ordem de execução

1. **Parar de copiar conteúdo** (dias): remover o preview da notificação de mensagem e excluir DMs de
   varredura/IA. É o maior ganho de exposição por menor custo.
2. **Cifra em repouso das DMs** (com KMS): protege dump e backup, ainda legível por nós.
3. **E2E de verdade** (P5, semanas): o desenho de 14.2, incluindo o fluxo de denúncia e o backup.

## 15. Decisões de 2026-10-27 (registradas)

### 15.1 Chave-mestra: **cofre próprio** (opção B)

Cofre gerenciado na própria infraestrutura (OpenBao/Vault com o motor *Transit*) rodando no Portainer,
em vez de KMS de nuvem e em vez de chave em variável de ambiente.

- **O que compra**: o banco, os backups e os dumps deixam de conter material legível; a chave-mestra não
  fica no ambiente do app; rotação e auditoria de uso passam a existir.
- **O que NÃO compra (e o plano não vai fingir)**: o cofre mora na mesma infraestrutura. Quem tem root
  no host e consegue deslacrar o cofre ainda decifra. A promessa honesta passa a ser "não é legível por
  quem lê banco/backup/dump", não "inacessível a nós".
- **Runbook** (unseal, backup das chaves de lacre, rotação, break-glass) fica **fora do repositório
  público**, como já previsto na abertura deste documento.

### 15.2 TLS: **misto** (opção C) — Cloudflare fica

O Cloudflare não sai: WAF, bot e PQ híbrido em parte dos hosts já existem e serão mantidos.

**Consequência que muda o plano**: onde o Cloudflare termina o TLS, **ele vê o corpo em claro**. Portanto,
nas rotas de dado do usuário (§13.1), o canal de aplicação não é camada de reforço — é **o** mecanismo que
impede terceiro de ler o conteúdo. Isso eleva a prioridade de ligar o canal nessas rotas (era "fase 3",
passa a ser pré-requisito de exposição segura). O transporte pós-quântico continua sendo medido por
`scripts/pq-tls-check.sh`.

### 15.3 Mensagens: **cifra em repouso** por ora (opção B)

E2E de verdade (servidor cego) **não** entra agora: o conteúdo continua legível pelo servidor, para
preservar busca, moderação automática e resposta a pedido judicial. A trilha de E2E (§14.2) fica
registrada como decisão futura, condicionada a aceitar moderação por denúncia com ciphertext original.

O que já vale: **parar de copiar conteúdo** (feito — notificação sem texto) e, quando o cofre de 15.1
estiver no ar, cifrar as mensagens em repouso.

## 16. Paridade de ambientes — QA (sem WAF/Cloudflare) e produção

Regra que vale daqui em diante: **nada da camada de aplicação pode depender da borda.** Se a proteção
só funcionasse atrás do Cloudflare, a QA deixaria de testar o que vai para produção — e o próprio
work que dá a garantia viraria específico de um host.

### 16.1 O que foi verificado no código (2026-10-27)

| Item | Situação | Consequência para a QA |
|---|---|---|
| `rede.crypto.protected-paths` | **vazio em todos os perfis** (`dev`, `hml`, `qa`, `prd`) | o filtro é no-op até o ambiente optar; subir o código não muda o comportamento da QA |
| Cofre / KMS | `VaultBackedKeyProvider` e `VaultTransitClient` **não são `@Component`**; nenhum `@Bean` os instancia | boot da QA não precisa de OpenBao/Vault no ar |
| CORS / origem | `enois-qa.b7cloud.tech` e `enois-api-qa.b7cloud.tech` estão em `rede.cors.allowed-origins` e `rede.origins.trusted` | handshake `POST /crypto/session` e as rotas do §13.1 passam pelo gate de origem igual à produção |
| Recusas do filtro (409/400) | passaram a refletir CORS da origem confiável (`TrustedOriginCors.apply`) | o front lê `CRYPTO_CHANNEL_REQUIRED` e abre o canal **nos dois ambientes**; antes o navegador escondia o status |
| IP real | `ClientIpResolver` só usa `CF-Connecting-IP` quando o salto é faixa do Cloudflare; senão lê o `X-Forwarded-For` da direita para a esquerda | QA (Traefik direto) continua com IP correto sem Cloudflare |
| Prefixo `/api` | `CryptoChannelFilter.applicationPath` remove o prefixo | a mesma lista de rotas vale nos dois ambientes |
| Relay do Chrome/`X-Crypto-*` | `allowedHeaders("*")` no CORS e o preflight reflete o pedido | cabeçalhos novos não exigem mudança de CORS |

### 16.2 O que difere e não é código

- **PQ no TLS do salto** é propriedade da borda de cada host: `api.enois.ia.br` negocia
  `X25519MLKEM768`; os hosts da QA negociam clássico até o terminador de lá suportar o grupo (§9.1).
  Isso muda o atributo criptográfico *daquele salto*, não a funcionalidade — e é justamente o motivo
  de a camada de aplicação existir: ela entrega a mesma proteção nos dois lugares.
- **WAF/bot do Cloudflare** não existe na QA. Nenhum controle do app pode pressupor que exista: o que
  for bloqueio de borda fica na borda; o que for regra de negócio fica no app (rate limit, origem,
  captcha com política `degrade` na QA).

### 16.3 Ordem de ligação, ambiente por ambiente

1. **Local primeiro**: os dois repositórios na `sec/cripto-pq`, front falando o canal contra um backend
   local. Prova de ida e volta antes de qualquer ambiente compartilhado.
2. **QA depois da promoção** (§0, item 3): o front da QA só recebe o handshake junto com o backend
   promovido. Ligar `protected-paths` na QA **antes** de o front da QA estar no ar transforma todo
   login em 409.
3. **Produção por último**, com a mesma ordem (front publicado, janela de métrica, depois a flag).
4. **Desligar é voltar a lista para vazio** — não exige mudança de código. É o kill switch
   **porque** o filtro abre envelope também em rota desmarcada: o front que já cifra continua
   atendido, e quem manda claro volta a passar. Sem esse comportamento, desmarcar a rota quebraria
   o front novo — foi por isso que ele entrou antes de qualquer ligação.

### 16.4 Matriz de ligação (o que precisa estar definido, por ambiente)

| Chave | Onde | Padrão | Efeito |
|---|---|---|---|
| `rede.crypto.protected-paths` | backend | vazio | rotas cujo corpo **exige** envelope (fail-closed) |
| `VITE_CRYPTO_PATHS` | front (build) | vazio | rotas que o cliente cifra |
| `rede.crypto.at-rest.enabled` | backend | `false` | liga cofre + tabela de chaves |
| `rede.crypto.vault.{address,token,transit-key}` | backend | vazio | exigidos só quando a cifra em repouso está ligada |

As duas primeiras precisam concordar em **conjunto de rotas**, mas não em momento: o backend aceita
envelope em rota desmarcada (§16.3, item 4). Por isso a ordem segura é

1. `VITE_CRYPTO_PATHS` no front → nada muda de exigência, e o backend já abre os envelopes;
2. observar: sessões criadas, zero erro de integridade;
3. `rede.crypto.protected-paths` no backend → a exigência passa a valer.

Invertida, a ordem quebra o tráfego antigo; é por isso que ela está escrita.

### 16.5 O que continua proibido

- Ligar a flag em produção enquanto a validação está na QA.
- Usar a QA como ambiente de experimento de criptografia (é o motivo da branch dedicada, §0).
- Fazer o front da QA apontar para a API de produção para "testar o canal" — isso mistura dados e
  sessões de dois ambientes.

### 16.6 IP de origem — decisão de 2026-10-27

O objetivo era guardar o IP do painel como HMAC. Ao ler o código, a premissa não se confirmou: **o
painel não lê mais do banco**. O sink ativo é o arquivo JSONL (`FileEventSink`), e a tabela
`system_events` (V172) não tem leitor nem escritor no código — um dump do banco **não leva IP**.

Decisão: manter o IP em claro **no log operacional** (JSONL, retenção curta, dentro do contêiner) e
não criá-lo em tabela nenhuma. HMAC ali removeria a informação de investigação sem proteger nada que
um dump alcance; a proteção que importa — o IP não estar no banco — já existe. Se a tabela
`system_events` voltar a ser usada, a coluna de IP entra como HMAC, nunca em claro.

## 17. Runbook de promoção (o que fazer, na ordem)

Promover a branch **não** liga a proteção. Este é o procedimento completo, com a prova de cada passo.

### 17.1 Pré-condições

- Front da `sec/cripto-pq` (outro repositório) falando o canal contra o backend da branch **em
  local** — ida e volta provada antes de qualquer ambiente.
- Suíte completa verde, incluindo o boot duplo (H2 e MariaDB).
- Migrações (se houver) aditivas, com `charset`/`collation` explícitos, ensaiadas em MariaDB.
- Revisão de segurança registrada no PR de promoção (§0).
- Se o pacote incluir cifra em repouso: cofre no ar, chave *transit* criada, token do app e a
  migração `data_keys` — **nada disso existe hoje**.

### 17.2 QA

1. PR `sec/cripto-pq` → `qa` no backend (**conferir a base antes de mergear**; já aconteceu de o
   merge cair no lugar errado). Merge dispara o deploy automático.
2. Prova de pod novo: `GET /api/version` no host da QA mostrando a versão do commit promovido.
3. PR da branch do front → `qa`; deploy do front.
4. Só então preencher `rede.crypto.protected-paths` na QA com as rotas do §13.1.
5. **Prova de que a flag pegou**: mandar um corpo em claro para uma rota marcada e exigir recusa
   (`CRYPTO_ENVELOPE_REQUIRED`/`CRYPTO_CHANNEL_REQUIRED`), e fazer o login pela tela funcionar. Sem
   essa prova, um erro de digitação na lista deixa tudo funcionando e nada protegido — o filtro abre
   envelope em rota desmarcada, então o produto parece normal.
6. Janela de observação: zero falha de decifra; uso do canal maior que zero; 409 do canal só por
   sessão expirada.

### 17.3 Produção

1. `qa` → `main` nos dois repositórios, **backend primeiro** (a dependência de sessão já obrigou essa
   ordem na promoção anterior). Push em `main` dispara deploy.
2. Prova de pod novo (`GET /api/version`) e `GET /health`.
3. Preencher `protected-paths` em produção **depois** do front de produção publicado.
4. Repetir a prova do item 17.2.5 e conferir o painel: nenhuma tempestade de 409.
5. Rodar `scripts/pq-tls-check.sh` (hosts de produção) e registrar o resultado.

### 17.4 Interruptor e rollback

| Situação | Ação | Efeito |
|---|---|---|
| Precisa desligar a exigência | esvaziar `protected-paths` e reiniciar | front que cifra continua atendido; claro volta a passar |
| Precisa religar | preencher de novo e reiniciar | exigência volta, front já está pronto |
| Precisa desfazer o front | rollback da imagem do front | obrigatório **só** se o backend ficar em versão anterior à do envelope |

Nenhum dos dois primeiros exige mudança de código ou deploy do front. O terceiro é o único caso em
que a ordem front↔back importa.

### 17.5 O que este runbook não cobre

- Cofre (unseal, rotação, break-glass): runbook do time, **fora do repositório público** (§15.1).
- Rotação de credenciais e o trabalho da branch paralela: não fazem parte desta promoção.
- TLS PQ da QA: depende da borda de lá; a sonda é informativa (§9.1).
