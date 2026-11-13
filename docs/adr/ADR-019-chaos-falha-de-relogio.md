# ADR-019 — Chaos engineering: a falha de relógio que anulava o TTL

**Status:** aceita (Fase 6)
**Data:** 2026-11-13

## Contexto

O último item aberto da fase 6 era "chaos engineering: falha injetada com resultado previsível"
(`07-plano-incremental.md`). Escolher o cenário não é escolher o mais vistoso — é escolher uma falha
que o desenho **deveria** resistir e que, injetada, revela uma suposição não declarada.

A suposição não declarada estava no ADR-018. O TTL da config assinada é medido por
`agora - verificado_em` com `date +%s`, e o runtime acredita nesse número. Mas o container não tem
relógio próprio: ele usa o do kernel, e o kernel pode **voltar no tempo** — VPS sem RTC que
reinicia e sobe com a hora do firmware, máquina suspensa e retomada, correção de NTP para trás.

Com o relógio atrás do instante de verificação, `agora - verificado_em` é **negativo**, e negativo é
menor que qualquer TTL positivo. O prazo do doc 06 §6 desaparece exatamente na máquina que acabou de
reiniciar — o pior momento possível — e o nó segue executando tarefa com uma permissão cuja validade
ninguém consegue medir.

## Decisão

1. **Idade não medível é idade não provada.** `allowed_tasks_da_cache()` compara `now` com `at`
   antes de comparar com o TTL: `now < at` → drena, com mensagem dizendo **há quantos segundos** o
   relógio está atrás. A permissão não é usada porque não se pode afirmar que ela vale — e a regra
   do §6 é que o nó nunca inventa permissão.

2. **O sentido contrário não precisa de guarda nova.** Relógio à frente dá idade maior que o TTL e
   cai no caminho de "config vencida" que já existia (R106). Guarda extra ali seria código sem
   cenário que o justifique.

3. **A falha é injetada no harness, não no produto.** O cenário substitui o `date` do PATH isolado
   por um stub que devolve um instante fixo. Nada no `node-runtime.sh` sabe que está sob teste: o
   que roda é o código de produção, com o ambiente mentindo. Injetar por dentro (uma variável
   "skew seconds" no runtime) provaria que o desvio funciona, não que a produção resiste.

4. **O doctor passa a distinguir as duas causas.** `node-doctor.sh` checagem 5b: `age < 0` vira
   "Relógio atrás do instante da verificação há Ns — confira NTP/hora da máquina"; `age >= ttl`
   continua "Config vencida". Sem isso, o operador leria "config vencida" e investigaria rede
   quando o problema é a hora da máquina.

5. **O teste também fixa que a falha não vira modo permanente.** Com o Control Plane de pé, o nó
   revalida a config no próprio ciclo e volta a trabalhar mesmo com o cache imprestável. Sem essa
   asserção, uma correção que drenasse para sempre "passaria" no cenário da falha.

## Alternativa rejeitada

**Confiar no `Date`/`serverTime` do Control Plane em vez do relógio local.** O caso que o cenário
testa é justamente o nó sem contato com o Control Plane (é quando o cache é usado); não há
`serverTime` para consultar. E o `serverTime` da última resposta seria mais um número local para
guardar e comparar, com o mesmo problema de relógio.

**Monotonicidade via `/proc/uptime`.** `uptime` não sobrevive a reboot — que é o gatilho principal
do relógio voltar. Um relógio monotônico de verdade exigiria persistir um contador próprio, o que
só troca "confio no kernel" por "confio no meu arquivo". O TTL já tem a resposta certa para não
poder medir: não usar a permissão.

**Injetar o skew por configuração do runtime.** Testaria o mecanismo de injeção, não a resistência
do produto, e deixaria uma porta ("aceite skew de X") que um operador acabaria usando para
"destravar" um nó com relógio errado — exatamente o que o fail secure proíbe.

## Consequências

- Um nó com relógio adiantado ou atrasado drena até revalidar. É o comportamento desejado, mas exige
  NTP funcionando na máquina do participante: o `node-doctor.sh` aponta a causa e manda conferir a
  hora da máquina, porque o sintoma ("o nó não pega tarefa") não parece relógio.
- O nó drenado por relógio recupera sozinho no primeiro ciclo com Control Plane alcançável — não há
  quarentena nem intervenção humana, porque o estado não é uma decisão administrativa e sim uma
  medida.
- O TTL do ADR-018 passa a ter **duas** condições de recusa, e ambas são conservadoras: idade
  medida maior que o prazo, ou idade não medível.
- Fica registrado que o padrão de chaos deste repositório é **injetar no ambiente, nunca no código**:
  o stub de `date` do cenário 32 é o modelo para os próximos (falha de DNS, de disco, de relógio).

## Provas

**Q0 (código anterior, falha reproduzida):** sem a guarda, o cenário 32 imprime
`seguindo com a última config verificada (-400s de 300s)`, executa `tk_clock` e responde `DONE`. A
idade negativa atravessa o TTL e o nó trabalha com permissão de validade desconhecida.

**Q0 (mutação sobre a correção):** trocando `if (( now < at ))` por `if false`, apenas as asserções
do cenário 32 voltam a falhar; as 139 asserções restantes seguem verdes. A mutação atinge só o
caminho novo.

**Harness `node-executor-test.sh`:** 139 asserções (133 em R106), com os cenários novos:

- **32** — relógio 400 s atrás da verificação → "relógio atrás do instante da verificação — idade não
  provável, drenando" e **nenhuma** chamada a `/network/tasks/next`.
- **33** — relógio 600 s à frente → "config vencida (TTL de 300s) — drenando", sem poll. Fixa que a
  decisão é sobre a idade medida, não sobre "relógio estranho" em geral.
- **34** — mesma falha com o Control Plane de pé → o nó revalida e conclui `tk_relogio`. A falha
  injetada não vira modo permanente.

**`node-runtime-test.sh`:** 49 asserções (43 antes), 6 novas no doctor: cache vencido é WARN com
idade e TTL e diz que o nó drena; relógio atrás é WARN **diferente**, apontando NTP; nenhum dos dois
faz o doctor sair 1 (o nó drena, não quebrou).

**Suíte completa:** 2688 testes, 0 falhas, 3 skipped — sem mudança no Java; o ADR altera só o runtime
e o diagnóstico.