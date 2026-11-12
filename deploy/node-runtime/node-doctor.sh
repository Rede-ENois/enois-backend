#!/usr/bin/env bash
# Diagnóstico do Node Runtime — padrão do dev-doctor.sh (PASS/WARN/FAIL).
#
# Uso:  NODE_HOME=/var/lib/enois-node ./node-doctor.sh [--control-plane URL]
#
# Somente leitura: não muda estado, não reenvia enroll, não imprime a API key.
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

# 5. Permissões do diretório de estado
dir_perms=$(stat -c '%a' "$NODE_HOME" 2>/dev/null || echo '?')
if [[ "$dir_perms" == "700" ]]; then
  pass "NODE_HOME com permissão 700"
else
  warn "NODE_HOME com permissão $dir_perms — recomendação: chmod 700 $NODE_HOME"
fi

# 6. Container
if command -v docker >/dev/null 2>&1; then
  if docker ps --format '{{.Names}}' 2>/dev/null | grep -qx 'enois-node'; then
    pass "Container enois-node em execução"
  else
    warn "Container enois-node não está rodando — rode install.sh --start"
  fi
fi

# 7. Conectividade com o Control Plane
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
