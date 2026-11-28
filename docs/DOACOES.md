# Doações via Pix e o selo de apoiador

Módulo `doacoes` (backend) + tela `/apoiar` e selo no perfil (frontend). Registra as decisões da
entrega de 20/11/2026.

## O que resolve

A página de apoio mostrava só a chave Pix estática (`src/lib/pix.ts` no front): não havia como
saber **quem** doou, então não havia como reconhecer quem doa. Agora cada conta logada pede um
copia e cola **só dela**, e a doação confirmada vira o selo "Apoiador" no perfil.

## Como a conta é amarrada ao Pix

A única peça que o Pix estático carrega e devolve ao recebedor é o **txid** (campo EMV 62-05): o app
do pagador o retransmite intacto (pixManual 2.6.2). A chave não serve para isso — o app consulta o
DICT e recebe o recebedor, sem espaço para concatenar nada.

1. A conta logada pede o código (`POST /doacoes/pix`). O servidor sorteia o txid
   (`ENOIS` + 13 caracteres do alfabeto sem `0/1/I/O`, 18 no total, 84 bits) e monta o BR Code.
2. A pessoa escolhe o valor (R$ 1,00 a R$ 5.000,00) e paga **exatamente** esse valor.
3. Para o selo, ela cola o comprovante (extrato do próprio banco ou a linha digitada). O servidor
   **lê** o comprovante, confere que o valor é o mesmo gerado no código e extrai o identificador
   fim a fim, que é único por pix. **O comprovante não é armazenado**: o texto é descartado no
   mesmo request; o que fica é a decisão (concluída ou não) e o resultado da leitura.

## Por que o txid sozinho não vale selo

O txid está dentro do QR Code, que circula por aí. Qualquer pessoa com o código de outra pode gerar
o mesmo pagamento e tentar se declarar "quem doou". Por isso:

- o txid identifica **a doação**, nunca a pessoa;
- o selo só nasce de comprovante com E2E (do doador) ou de linha de ENTRADA do extrato da conta
  (conferência, abaixo);
- a confirmada por revisão no extrato é a prova forte (`origem = CONTA`); o selo dado à mão pela
  administração tem `origem = MANUAL` e valor zero (seção própria abaixo).

## Conferência por quem recebe

`POST /admin/doacoes/conferir` recebe o extrato Pix da conta (texto colado) e confirma as doações
abertas com o mesmo valor (e a mesma data, quando o extrato traz ano). `dryRun=true` mostra o que
faria sem gravar. `PixStatement` ignora saída, boleto, TED, DOC, saque e estorno; uma linha sem
sinal de entrada só entra se disser "recebido". `GET /admin/doacoes` lista abertas e decididas.

Não há webhook: o webhook do Pix pertence à API Pix do PSP da conta (a NuPay não é PSP do recebedor
nesse arranjo, a leitura dela sobre pagador vale para a cobrança dela, não para Pix direto).

## Selo registrado à mão (administração)

Quando o dinheiro chega e o comprovante não é reconhecido (comprovante de outro formato, Pix feito
por outra conta, transferência combinada fora do app), a pessoa ficou sem o selo que o gesto dela
merece. `POST /admin/doacoes/sele/{username}` registra o selo pelo perfil, e o corpo leva o motivo:

```json
{ "reason": "Pix de 25/11 chegou na conta e o comprovante não foi reconhecido" }
```

Regras, e por que cada uma existe:

- **Só admin** (`AdminGuard`), e o nome de quem registrou fica na linha (`decided_by`).
- **Motivo obrigatório** (`REASON_REQUIRED`), como nas outras ações administrativas de conta.
- **Um selo manual por conta**: a segunda tentativa responde `409 SUPPORTER_SEAL_ALREADY_GRANTED`.
  Sem isso, o botão viraria clique sem consequência — e "apoiador" deixaria de significar um gesto.
- **Quem já é apoiador por doação confirmada** não recebe (`409 SUPPORTER_ALREADY_CONFIRMED`): não
  há o que registrar, e a linha manual não pode substituir a prova que já existe.
- A linha nasce **com valor zero** e `origem = MANUAL`, e **não aparece em "minhas doações"**
  (`GET /doacoes/minhas` filtra `MANUAL`): ela não é uma doação da pessoa, e mostrá-la faria a tela
  apresentar um pagamento de R$ 0,00 com `txid MANUAL-…` que ninguém fez. O selo continua vindo por
  `GET /users/{username}/apoiador`, que é onde ele pertence.
- `selo.confirmedCount` conta **doações reais** (a linha manual não entra), e `selo.apoiador` vale
  `true` com qualquer confirmação, inclusive a concedida à mão.
- O resumo público `GET /doacoes/resumo` → `confirmed` conta **doações** confirmadas, e o selo manual
  não entra: o número é de dinheiro recebido, não de quantas pessoas têm selo.
- O selo manual dá ao perfil exatamente o que uma doação confirmada dá — `GET /users/{username}/apoiador`
  e o selo nas projeções do feed. Nada mais: nenhum dado financeiro sai da conta.

## Janela e troca de código

Cada código vale 36 horas (`QR_WINDOW_HOURS`). Pedir um código novo **recusa** o anterior — dois
abertos multiplicariam comprovantes a conferir e o txid antigo pode chegar atrasado no extrato.

## Privacidade

- O perfil público recebe só `{ apoiador: boolean }` (`GET /users/{username}/apoiador`). Valor e
  data de doação não saem da conta da pessoa.
- O selo não aparece em mensagens (conversa privada não é lugar de dado financeiro).
- Não há comprovante em disco, nem no banco, nem em log. A tabela `doacoes_pix_ids` guarda só os
  identificadores de pix que já confirmaram uma doação (é o que impede o mesmo comprovante pagar o
  selo de duas contas); o txid é público por construção.

## Configuração

| Propriedade | Padrão | O que é |
|---|---|---|
| `rede.doacoes.pix.key-digits` | `51839682000161` | Chave CNPJ em 14 dígitos (a mesma da campanha) |
| `rede.doacoes.pix.name` | `Apoie o Enois` | Nome no campo 59 (vai sem acento) |
| `rede.doacoes.pix.city` | `SAO PAULO` | Cidade no campo 60 |

## Arquivos

- Domínio: `BrCode`, `EndToEndId`, `PixStatement`, `ComprovanteTexto`, `Doacao`, `DoacaoStatus`.
- Aplicação: `DoacaoService` (open, confirmWithProof, reconcile, decide, sealState).
- Web: `DoacaoController` (`/doacoes/pix`, `/doacoes/{id}/comprovante`, `/doacoes/minhas`,
  `/admin/doacoes*`, `/admin/doacoes/sele/{username}`) e `ApoiadorSealController`
  (`/users/{username}/apoiador`).
- Migração: `V372__doacoes.sql` (tabelas `doacoes` e `doacoes_pix_ids`).
- Projeções: `PostView.authorApoiador` e `CommentView.authorApoiador`, carimbados em lote no
  `GetFeedService`.
