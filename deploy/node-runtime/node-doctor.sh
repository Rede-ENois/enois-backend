#!/usr/bin/env bash
# Diagnóstico do Node Runtime — padrão do dev-doctor.sh (PASS/WARN/FAIL).
#
# Uso:  NODE_HOME=/var/lib/enois-node ./node-doctor.sh [--control-plane URL]
#
# Somente leitura: não muda estado, não reenvia enroll, não imprime a API key.
#
# Exceção declarada: com --control-plane ele faz um heartbeat com a credencial do nó para
# perguntar "fui aceito?" — leitura de estado, mas que registra o último contato do nó no
# Control Plane. O heartbeat sai do próprio nó, com a identidade dele, e nada é alterado
# além disso.
set -u

NODE_HOME="${NODE_HOME:-/var/lib/enois-node}"
CONTROL_PLANE="${ENOIS_CONTROL_PLANE:-}"

while (($#)); do
  case "$1" in
    --control-plane) CONTROL_PLANE="$2"; shift 2 ;;
    -h|--help) sed -n '2,6p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) printf 'Erro: opção desconhecida: %s\n' "$1" >&2; exit 2 ;;
  esac
done

PASS=0
WARN=0
FAIL=0

pass() { printf '[PASS] %s\n' "$1"; PASS=$((PASS + 1)); }
warn() { printf '[WARN] %s\n' "$1"; WARN=$((WARN + 1)); }
fail() { printf '[FAIL] %s\n' "$1"; FAIL=$((FAIL + 1)); }

printf '\n=== Énóis Node Doctor ===\n\n'

# 1. Dependências. curl é o que o próprio doctor usa para checar conectividade;
#    o heartbeat de verdade sai do container — ausente no host é WARN, não FAIL.
for tool in curl docker; do
  if command -v "$tool" >/dev/null 2>&1; then
    pass "$tool encontrado"
  elif [[ "$tool" == "docker" ]]; then
    fail "docker ausente — o runtime não sobe sem ele"
  else
    warn "curl ausente — conectividade não poderá ser verificada"
  fi
done

# 2. Estado do nó
if [[ -s "$NODE_HOME/node.json" ]]; then
  node_id=$(sed -n 's/.*"nodeId":"\([^"]*\)".*/\1/p' "$NODE_HOME/node.json")
  if [[ -n "$node_id" ]]; then
    pass "Nó registrado: $node_id"
  else
    fail "node.json sem nodeId"
  fi
else
  fail "Sem $NODE_HOME/node.json — rode install.sh (enroll) primeiro"
fi

# 3. API key
if [[ -s "$NODE_HOME/api-key" ]]; then
  perms=$(stat -c '%a' "$NODE_HOME/api-key" 2>/dev/null || stat -f '%Lp' "$NODE_HOME/api-key" 2>/dev/null)
  if [[ "$perms" == "600" ]]; then
    pass "API key presente com permissão 600"
  else
    warn "API key com permissão $perms — rode: chmod 600 $NODE_HOME/api-key"
  fi
else
  warn "Sem API key ainda — o admin precisa gerá-la; rode install.sh --set-key depois"
fi

# 4. Pino da config assinada
if [[ -s "$NODE_HOME/pinning-key" ]]; then
  perms=$(stat -c '%a' "$NODE_HOME/pinning-key" 2>/dev/null || stat -f '%Lp' "$NODE_HOME/pinning-key" 2>/dev/null)
  if [[ "$perms" == "600" ]]; then
    pass "Pino da config assinada presente com permissão 600"
  else
    warn "Pino com permissão $perms — rode: chmod 600 $NODE_HOME/pinning-key"
  fi
else
  warn "Sem pino da config assinada — o executor recusa toda config; rode install.sh --start para pinar"
fi

# 4b. Chave do nó (brief §10): a privada que o instalador gera no enroll. Sem ela o nó
# fica com o certificado emitido para uma chave que não tem, e não consegue renovar com
# o mesmo par. Não é FAIL: nó instalado antes desta versão não tem a chave e continua
# operando com a API key — o aviso é para o operador não achar que a identidade existe.
if [[ -s "$NODE_HOME/node.key" ]]; then
  perms=$(stat -c '%a' "$NODE_HOME/node.key" 2>/dev/null || stat -f '%Lp' "$NODE_HOME/node.key" 2>/dev/null)
  if [[ "$perms" =~ ^(600|640)$ ]]; then
    pass "Chave do nó presente com permissão $perms"
  else
    warn "Chave do nó com permissão $perms — rode: chmod 600 $NODE_HOME/node.key"
  fi
else
  warn "Sem chave do nó (node.key) — o nó opera só com a API key; reinstale com o install.sh atual para emitir o certificado"
fi

# 5. Permissões do diretório de estado
dir_perms=$(stat -c '%a' "$NODE_HOME" 2>/dev/null || echo '?')
if [[ "$dir_perms" == "700" ]]; then
  pass "NODE_HOME com permissão 700"
else
  warn "NODE_HOME com permissão $dir_perms — recomendação: chmod 700 $NODE_HOME"
fi

# 5b. Frescor da config guardada (doc 06 §6)
# A config vale por ttlSeconds contados da verificação. Vencida, o nó drena mesmo com o Control
# Plane de pé — é aviso, não falha: ele volta a operar no primeiro ciclo em que conseguir revalidar.
if [[ -s "$NODE_HOME/config.json" && -s "$NODE_HOME/config-verified-at" ]]; then
  verified_at=$(head -n 1 "$NODE_HOME/config-verified-at")
  ttl=$(sed -n 's/.*"ttlSeconds":\([0-9][0-9]*\).*/\1/p' "$NODE_HOME/config.json" | head -n 1)
  if [[ "$verified_at" =~ ^[0-9]+$ && -n "$ttl" ]]; then
    age=$(( $(date +%s) - verified_at ))
    if (( age < 0 )); then
      # Mesmo caso do runtime: o relógio andou para trás depois da verificação, então a idade da
      # config não é medível. O nó drena até revalidar; o operador precisa ver a causa, que não é
      # "config velha" e sim o relógio da máquina.
      warn "Relógio atrás do instante da verificação há $(( -age ))s — o nó drena até revalidar; confira NTP/hora da máquina"
    elif (( age < ttl )); then
      pass "Config verificada há ${age}s (válida por ${ttl}s)"
    else
      warn "Config vencida há $(( age - ttl ))s (TTL de ${ttl}s) — o nó drena até revalidar; confira a conectividade"
    fi
  else
    warn "Cache de config ilegível — o nó vai reverificar no próximo ciclo"
  fi
fi

# 6. Versão e fonte de verdade do runtime (o bootstrap grava as duas)
if [[ -s "$NODE_HOME/runtime-version" ]]; then
  pass "Runtime declarado no heartbeat: $(head -n 1 "$NODE_HOME/runtime-version")"
  if [[ -s "$NODE_HOME/runtime-source" ]]; then
    pass "Fonte de verdade: $(head -n 1 "$NODE_HOME/runtime-source") (troque com bootstrap.sh --update --source <qa|main>)"
  fi
else
  warn "Sem $NODE_HOME/runtime-version — o heartbeat declara só a fonte; rode o bootstrap.sh para registrar a versão"
fi

# 7. Container
if command -v docker >/dev/null 2>&1; then
  if docker ps --format '{{.Names}}' 2>/dev/null | grep -qx 'enois-node'; then
    pass "Container enois-node em execução"
  else
    warn "Container enois-node não está rodando — rode install.sh --start"
  fi
fi

# 7b. Aceite pela rede — o nó autenticou e o Control Plane despacha?
# Sem esta pergunta o operador vê "API key 600, pino 600, container up" e conclui que está tudo
# bem num nó que nunca recebeu tarefa. O heartbeat com a credencial do nó é a resposta: `status`
# diz se o admin aprovou e `acceptTasks` diz se a rede está despachando. O heartbeat é leitura
# de estado, mas ele REGISTRA contato no Control Plane (último contato do nó) — o doctor deixa
# de ser 100% só-local, e isso está dito no cabeçalho do arquivo).
# Sem credencial ou sem URL não é FAIL — é "não deu para perguntar".
if [[ -n "$CONTROL_PLANE" ]] && command -v curl >/dev/null 2>&1 \
   && [[ -s "$NODE_HOME/node.json" && -s "$NODE_HOME/api-key" ]]; then
  node_id_hb=$(sed -n 's/.*"nodeId":"\([^"]*\)".*/\1/p' "$NODE_HOME/node.json")
  hb=$(curl --fail --silent --show-error --connect-timeout 5 --max-time 15 \
    -H "X-Node-Id: $node_id_hb" -H "X-Node-Key: $(head -n 1 "$NODE_HOME/api-key")" \
    -H 'Content-Type: application/json' -d '{}' \
    "$CONTROL_PLANE/network/nodes/heartbeat" 2>/dev/null) && hb_rc=0 || hb_rc=$?
  if (( hb_rc != 0 )); then
    if (( hb_rc == 22 )); then
      fail "Control Plane recusou a credencial do nó (heartbeat HTTP de erro) — a chave foi revogada ou rotacionada; gere uma nova no painel"
    else
      warn "Heartbeat não respondeu (transporte) — aceite da rede não verificado"
    fi
  else
    hb_status=$(sed -n 's/.*"status":"\([^"]*\)".*/\1/p' <<<"$hb" | head -n 1)
    hb_mode=$(sed -n 's/.*"mode":"\([^"]*\)".*/\1/p' <<<"$hb" | head -n 1)
    if grep -q '"acceptTasks":true' <<<"$hb"; then
      pass "Aceito pela rede: status=${hb_status:-?} modo=${hb_mode:-?} — o Control Plane despacha tarefas"
    else
      case "$hb_status" in
        PENDING_APPROVAL) warn "Aguardando aprovação do administrador (status=PENDING_APPROVAL) — o nó autentica e drena até lá" ;;
        QUARANTINED|SUSPENDED) warn "Fora de operação (status=$hb_status) — nó autentica e drena até o admin liberar" ;;
        REVOKED) fail "Nó revogado (status=REVOKED) — revogação é terminal; provisione um nó novo" ;;
        ACTIVE) warn "Aprovado, mas a rede não despacha agora (modo=${hb_mode:-?}) — drenando" ;;
        *) warn "A rede não despacha (status=${hb_status:-?} modo=${hb_mode:-?})" ;;
      esac
      if [[ ! -s "$NODE_HOME/pinning-key" ]]; then
        warn "E sem pino da config assinada: mesmo aprovado, o runtime recusa toda config — rode install.sh --start"
      fi
    fi
  fi
elif [[ -n "$CONTROL_PLANE" ]]; then
  warn "Aceite da rede não verificado (falta node.json ou API key)"
fi

# 8. Conectividade com o Control Plane
if [[ -n "$CONTROL_PLANE" ]] && command -v curl >/dev/null 2>&1; then
  if curl --fail --silent --output /dev/null --connect-timeout 5 --max-time 10 "$CONTROL_PLANE/health" 2>/dev/null; then
    pass "Control Plane acessível em $CONTROL_PLANE"
  else
    fail "Control Plane não respondeu em $CONTROL_PLANE/health"
  fi
else
  warn "Conectividade não verificada (use --control-plane URL)"
fi

printf '\n=== Resultado ===\n'
printf 'PASS: %d | WARN: %d | FAIL: %d\n' "$PASS" "$WARN" "$FAIL"

if ((FAIL > 0)); then
  printf 'Nó precisa de ajustes antes de operar.\n'
  exit 1
fi
printf 'Nó pronto para operar.\n'
exit 0
