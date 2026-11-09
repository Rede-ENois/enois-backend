# Relatório de validação — documentação oficial de chamadas e Tamo junto

Documento de apoio à revisão. Registra a auditoria técnica que sustenta a redação dos Termos de Uso,
das Diretrizes da Comunidade e da Política de Privacidade quanto a chamadas de voz/vídeo e salas
abertas ("Tamo junto"). Data da auditoria: 2026-11-09.

Nada aqui é funcionalidade nova: o escopo é **documentação**. As lacunas apontadas estão registradas
para revisão humana e priorização, não foram corrigidas nesta tarefa.

## 1. Arquitetura encontrada

| Peça | O que é |
| --- | --- |
| Énóis (backend) | Java 25, Spring Boot, orquestra as chamadas, guarda estado e faz a moderação |
| Énóis (front) | Painel de chamada em iframe dentro do app; pede a captura à sala por `postMessage` |
| Épapo | Fork do Jitsi Meet, hospedado pelo projeto: web (nginx), prosody, jicofo, jvb, auth-gateway (Node), Traefik como borda TLS |
| Persistência da mídia | O Epapo não tem banco nem volume (`deploy/docker-compose.yml`) |

Não há gravação de áudio/vídeo. A mídia trafega pelos servidores do Epapo (modelo SFU): **o JVB
recebe o áudio e o vídeo para retransmitir**, portanto **não há criptografia ponta a ponta entre
participantes** — o TLS e o SRTP protegem cada trecho, não o caminho inteiro. STUN apenas
(`stun:meet-jit-si-turnrelay.jitsi.net:443`); **não há TURN**.

## 2. Fluxos de dados

1. **Entrada na sala.** Énóis → gateway da Épapo `POST /auth/token` com `X-Epapo-Internal` e corpo
   `{callId, userId}`. O gateway pergunta ao Énóis `POST /calls/{id}/gateway/entry/{userId}` (header
   `X-Enois-Authorize`, comparação em tempo constante) e só então assina o JWT (HS256) com o
   `JITSI_APP_SECRET` — segredo que nunca é visto pelo Énóis. O token carrega `room`, `aud=jitsi`,
   `iss=epapo`, `context.user.*` e `context.features.*` (gravação/transmissão/chamada externa/
   transcrição = false).
2. **Mídia.** Navegador ↔ Epapo (JVB). Nenhum trecho passa pelo backend do Énóis.
3. **Denúncia em chamada.** O front pede a captura à iframe (`enois:call-capture-request`), que
   responde uma folha JPEG (até 4 vídeos visíveis, lado maior 1280 px, JPEG 0.85). O front sobe a
   imagem com propósito `REPORT_CAPTURE` e chama `POST /calls/{callId}/reports` com
   `{reportedUserId, reason, description, mediaId}`.
4. **Moderação.** `CallReportService` → `ModerationService.reportCall` grava `call_reports` (V241) e
   notifica **o suporte** por e-mail. A triagem por IA (`ModerationAiService`) recebe relato + até
   2 imagens no endpoint externo (`MODERATION_LLM_*`). `AiVerdictApplicationService` **ignora** alvo
   `CALL` na penalidade automática: a decisão é humana no painel `/moderacao/{reportId}`.
5. **Recurso.** `POST /me/sanctions/appeals` (usuário) → `GET /admin/appeals` → `POST
   /admin/appeals/{id}/approve|deny` (moderador). No app: `/penalidades` e `/suporte`.

## 3. Informações coletadas por serviço

| Dado | Onde fica | Finalidade |
| --- | --- | --- |
| Sala, participantes, entrada/saída, estado | `call_sessions` / `call_participants` (Énóis) | Operar a chamada, expirar sala abandonada |
| Relato, motivo, denunciante, denunciado, participantes do momento, horário | `call_reports` (Énóis) | Moderação |
| Captura estática | asset `REPORT_CAPTURE` (Énóis) | Prova da denúncia |
| Nome/papel no token; logs técnicos dos contêineres | Epapo | Operação da sala |
| Relato + imagem da captura | Provedor externo da triagem | Apoio à análise (não decide) |

## 4. Mecanismos de segurança

- Vínculo lido do banco a cada entrada; token preso à sala; nome de sala UUID.
- `ENABLE_AUTH=1`, `ENABLE_GUESTS=0`, `JWT_ALLOW_EMPTY=0`, `AUTH_TYPE=jwt`.
- Proxy web da Epapo com `access_log off`.
- Binário da evidência servido só a moderador (`401` sem sessão, `403` sem cargo,
  `Cache-Control: private, no-store`).
- Captura aceita apenas quando `event.origin === EPAPO_CAPTURE_ORIGIN` e
  `event.source === window.parent`.

## 5. Épapo × Tamo junto — diferenças

| | Épapo | Tamo junto |
| --- | --- | --- |
| O que é | O serviço de videoconferência (infra) | A **modalidade** de chamada aberta (grupo, tópico ou evento) que roda sobre o Épapo |
| Quem administra | Projeto (hospedagem/operadores) | Énóis (a sala e a moderação) |
| Grava? | Não | Não |
| Guarda denúncia? | Não | Sim, no Énóis (`call_reports`) |
| Decide penalidade? | Não | Não — humano no Énóis |

Tamo junto **não é um serviço separado**: é a mesma pilha de chamadas aberta por grupo, tópico ou
evento (o `boolean tamoJunto` no evento apenas indica que o evento terá a sala aberta).

## 6. Documentos alterados e justificativa

| Documento | Mudança | Por quê |
| --- | --- | --- |
| `src/texts.ts` → `legal.terms.sections` (seção 16) | Regras de uso de chamadas, conduta, denúncia (imagem estática, sem gravação), decisão humana, limites técnicos | Termos precisam cobrir a funcionalidade nova sem prometer o que não existe |
| `src/texts.ts` → `guidelines` (blocos `calls*` e `callReports*`) | Conduta em chamada, consentimento, denúncia, limite da prova, recurso em `/penalidades` | Onde as pessoas procuram as regras de convivência |
| `src/texts.ts` → `legal.privacy.sections.other` (11 e 12) + `updatedAt` | Dados de chamada, sem E2EE, metadados, evidência, terceiro (triagem), retenção honesta, pedido de autoridades | Política de Privacidade precisa refletir o tratamento real |
| `src/routes/privacidade.tsx`, `src/routes/diretrizes.tsx` | Renderização das seções novas pelo `RichText` do design system | Regra `no-front-markup`; a baseline de lint só pode encolher |
| `docs/CHAMADAS.md` | Seções "O que a Épapo vê" e fatos da denúncia + lacunas | Contraparte técnica/transparência |
| `docs/SECURITY.md` | Seção 2.1 com os fatos verificados | Alimentar o plano de segurança com o que a auditoria achou |

## 7. Pontos que precisam de validação jurídica humana

1. **Base legal da denúncia**: o texto adota cumprimento de obrigação legal/exercício regular de
   direitos (art. 7º, LGPD). **Confirmar** se o enquadramento escolhido é o desejado, considerando
   que a imagem vem de captura sem aviso ao participante.
2. **Retenção**: como não há retenção automática da evidência nem purge temporal dos registros de
   moderação, o texto diz que o material permanece enquanto necessário. **Definir prazo** é decisão
   jurídica/produto.
3. **Fluxo de requisição de autoridades**: o texto diz "requisição judicial ou pedido formal de
   autoridade competente". **Validar** a formulação e o procedimento interno de atendimento.
4. **Compartilhamento com provedor externo de análise**: confirmar contrato/ZDR com o provedor e a
   redação do compartilhamento (operador × controlador conjunto).
5. **Regra de idade (16+, supervisão até os 18)**: implementada no backend e refletida nos textos
   oficiais (Termos 1 e 3, Privacidade 9, Diretrizes). **Validar** a redação do consentimento do
   responsável e o alcance da supervisão — em especial a afirmação "de 16 a 17 anos a conta é
   somente leitura": no código, o bloqueio vale para as escritas (publicar, comentar, mensagem,
   entrar em chamada) e para as demais áreas com portão de idade, mas a leitura do feed e de
   demais áreas sem portão permanece liberada.

## 8. Funcionalidades que NÃO puderam ser provadas / divergências com a documentação anterior

- **Não há comprovação de E2EE** e o modelo (SFU/JVB) a torna inexistente como implementação. Por
  isso nenhum texto usa "criptografia ponta a ponta".
- **`GET /media/{mediaId}` é público** e devolve `objectKey`/`cdnUrl` de um asset `REPORT_CAPTURE`
  (o binário é de moderador). Divergência entre a proteção do arquivo e a do metadado — registrada
  como lacuna, **não corrigida** (escopo é documentação).
- **Sem trilha de auditoria** de acesso à evidência.
- **Sem TURN**: a observação de `service-unavailable` do TURN é tarefa independente, fora do escopo.
- **Captura silenciosa**: confirmado no `custom.js` (sem aviso, sem log).

## 9. Checklist de consistência implementação × documentação

| Afirmação no texto | Verificado em | Confere? |
| --- | --- | --- |
| "não são gravadas em áudio ou vídeo" | `ENABLE_RECORDING=0`, `context.features.recording=false` | Sim |
| "captura uma imagem estática no momento da denúncia" | `custom.js` `captureForReport`, `call-report-dialog.tsx` | Sim |
| "nenhuma penalidade sem revisão humana" | `AiVerdictApplicationService` (ignora `CALL`) | Sim |
| "acesso restrito à equipe de moderação" | `MediaStorageProxyController.read` | Sim, com a ressalva do metadado público |
| "pode ser avaliado por provedor externo" | `ModerationAiService.reportImages` | Sim |
| "não tem criptografia ponta a ponta" | SFU/JVB em `deploy/docker-compose.yml` | Sim |
| "permanece armazenado enquanto necessário" | ausência de purge de `REPORT_CAPTURE` | Sim (reflete a realidade) |

## 10. Pendência de revisão humana

Este relatório e os textos não devem ir a `main` sem revisão técnica e validação jurídica. Os pontos
da seção 7 dependem de decisão humana e não foram resolvidos por suposição.
