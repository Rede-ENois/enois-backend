#!/usr/bin/env bash
# Swap em uso e pressão de memória no heartbeat (brief §8.2, "Sistema operacional": "Swap",
# "Pressão de memória").
#
# O que faltava: o runtime JÁ MEDE os dois desde o ciclo 157 e os manda no corpo, mas o Control
# Plane não os recebia no contrato — eram descartados na borda e o painel do nó dizia "não medido"
# para sempre. A coluna (V389) e o contrato chegaram no ciclo 165; o que aqui se mede é o outro lado:
# o runtime REAL (`cycle`), com `/proc` forjado, produz os valores no corpo.
#
#   1. swap é `SwapTotal - SwapFree` de /proc/meminfo, em MiB (não o total, nem o livre);
#   2. `meminfo` SEM as linhas de swap não vira swap zero — o campo não vai;
#   3. pressão é `avg300` do `some` de /proc/pressure/memory, em porcentagem;
#   4. sem o arquivo do PSI o campo não vai;
#   5. `avg10`/`avg60` NÃO são usados no lugar do `avg300` (são janelas diferentes);
#   6. o corpo inteiro continua JSON válido com os dois campos.

set -uo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
RUNTIME="$ROOT/deploy/node-runtime/node-runtime.sh"

PASS=0
FAIL=0
pass() { printf '[PASS] %s\n' "$1"; PASS=$((PASS + 1)); }
fail() { printf '[FAIL] %s\n' "$1" >&2; FAIL=$((FAIL + 1)); }

TMP=$(mktemp -d "${TMPDIR:-/tmp}/enois-swap-XXXXXX")
trap 'rm -rf "$TMP"' EXIT

# Um `curl` que captura o corpo do heartbeat (o que o runtime manda) e responde o que o Control
# Plane responderia a um nó em dia.
cat > "$TMP/curl" <<'SH'
#!/usr/bin/env bash
capture=0
for arg in "$@"; do
  if [[ "${capture:-}" == 1 ]]; then
    printf '%s' "$arg" > "${HB_CAPTURE:?}"
    capture=0
    continue
  fi
  [[ "$arg" == "-d" ]] && capture=1
done
printf '{"status":"ACTIVE","acceptTasks":false,"operable":true}'
SH
chmod +x "$TMP/curl"

cat > "$TMP/run-cycle.sh" <<'SH'
#!/usr/bin/env bash
# Runtime REAL, com PATH e diretórios de estado do teste. O `/proc` já vem trocado na cópia.
# O laço final vira uma chamada só: `cycle` roda uma volta e sai (é o da produção, sem o `sleep`).
set -uo pipefail
runtime="$1"; home="$2"
export PATH="__TMP__:$PATH"
export NODE_HOME="$home/home"
export CONTROL_PLANE_URL="http://control-plane.invalid"
export NODE_POLL_SECONDS=0
export DISKSTATE_FILE="$home/disk-prev"
export THERMAL_HWMON_DIR="$home/sem-hwmon"
export THERMAL_ZONE_DIR="$home/sem-thermal"
mkdir -p "$NODE_HOME" "$THERMAL_HWMON_DIR" "$THERMAL_ZONE_DIR" "$home/link"
chmod 700 "$NODE_HOME"
printf '{"nodeId":"nd_swap_test","status":"ACTIVE"}\n' > "$NODE_HOME/node.json"
printf 'chave-de-teste\n' > "$NODE_HOME/api-key"
chmod 600 "$NODE_HOME/node.json" "$NODE_HOME/api-key"
sed 's/^while true; do$/cycle; exit 0/' "$runtime" > "$home/one-cycle.sh"
bash "$home/one-cycle.sh" >"$home/out.log" 2>&1
SH
chmod +x "$TMP/run-cycle.sh"
sed -i "s|__TMP__|$TMP|" "$TMP/run-cycle.sh"

proc_com() { # proc_com DIR meminfo(ok|sem-swap) psi(ok|ausente)
  local dir="$1"
  mkdir -p "$dir/self" "$dir/net"
  cat > "$dir/self/status" <<'EOF'
Name:	bash
State:	R (running)
Threads:	3
EOF
  if [[ "$2" == ok ]]; then
    cat > "$dir/meminfo" <<'EOF'
MemTotal:       16384000 kB
MemFree:         2048000 kB
MemAvailable:   12288000 kB
SwapTotal:       4194304 kB
SwapFree:        3145728 kB
EOF
  else
    cat > "$dir/meminfo" <<'EOF'
MemTotal:       16384000 kB
MemFree:         2048000 kB
MemAvailable:   12288000 kB
EOF
  fi
  if [[ "$3" == ok ]]; then
    mkdir -p "$dir/pressure"
    cat > "$dir/pressure/memory" <<'EOF'
some avg10=12.34 avg60=9.87 avg300=4.20 total=123456
full avg10=0.00 avg60=0.00 avg300=0.00 total=0
EOF
  fi
  printf '  lo: 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0\n' > "$dir/net/dev"
  printf '12345.67 100.00\n' > "$dir/uptime"
}

runtime_com() { # runtime_com DIR-PROC → copia do runtime apontando para ele
  sed 's|\([^A-Za-z_]\)/proc|\1'"$1"'|g' "$RUNTIME" > "$TMP/runtime-$(basename "$1").sh"
  printf '%s\n' "$TMP/runtime-$(basename "$1").sh"
}

hb_field() {
  grep -o "\"$1\":[0-9.]*" "${2:-$TMP/hb.json}" | head -n 1 | cut -d: -f2
}

json_ok() { python3 -c 'import json,sys; json.load(open(sys.argv[1]))' "$1" 2>/dev/null; }

# 1. Meminfo com swap e PSI presente: os dois valores (1 GiB de swap usado, 4.2% de pressão).
P1="$TMP/proc1"; proc_com "$P1" ok ok
R1=$(runtime_com "$P1")
if grep -q '/proc/' "$R1" || ! grep -q "$P1/meminfo" "$R1"; then
  fail "o teste não apontou o runtime para o /proc forjado"
else
  pass "o runtime de teste lê o /proc forjado, sem o real"
fi
HB_CAPTURE="$TMP/hb.json" "$TMP/run-cycle.sh" "$R1" "$TMP" || true
if [[ "$(hb_field swapUsedMb)" == "1024" ]]; then
  pass "swap em uso é SwapTotal - SwapFree em MiB (1024)"
else
  fail "swap esperado 1024 (1 GiB de 4 GiB usados); veio $(hb_field swapUsedMb) — corpo: $(head -c 300 "$TMP/hb.json" 2>/dev/null)"
fi
if [[ "$(hb_field memoryPressurePercent)" == "4.2" ]]; then
  pass "pressão é o avg300 do some (4.2%), não o total nem 5 s/10 s"
else
  fail "pressão esperada 4.2 (avg300); veio $(hb_field memoryPressurePercent)"
fi
if json_ok "$TMP/hb.json"; then
  pass "corpo do heartbeat é JSON válido com swap e pressão"
else
  fail "corpo do heartbeat não é JSON válido: $(head -c 300 "$TMP/hb.json" 2>/dev/null)"
fi

# 5. avg10/avg60 têm outros valores de propósito: usar qualquer um deles não casa com o avg300.
# (O arquivo forjado tem 12.34, 8.50 e 4.2; as três seriam distinguíveis por valor.)

# 2. Meminfo SEM swap: o campo não vai (e não vira zero).
P2="$TMP/proc2"; proc_com "$P2" sem-swap ok
R2=$(runtime_com "$P2")
HB_CAPTURE="$TMP/hb2.json" "$TMP/run-cycle.sh" "$R2" "$TMP" || true
if [[ -s "$TMP/hb2.json" ]] && ! grep -q '"swapUsedMb"' "$TMP/hb2.json"; then
  pass "meminfo sem swap não afirma swap (nem zero)"
else
  fail "meminfo sem swap enviou swapUsedMb: $(head -c 300 "$TMP/hb2.json" 2>/dev/null)"
fi
if [[ "$(hb_field memoryPressurePercent "$TMP/hb2.json")" == "4.2" ]]; then
  pass "sem swap a pressão continua medida"
else
  fail "sem swap a pressão sumiu: $(hb_field memoryPressurePercent "$TMP/hb2.json")"
fi

# 3/4. Sem /proc/pressure/memory: a pressão não vai, o swap sim.
P3="$TMP/proc3"; proc_com "$P3" ok ausente
R3=$(runtime_com "$P3")
HB_CAPTURE="$TMP/hb3.json" "$TMP/run-cycle.sh" "$R3" "$TMP" || true
if [[ -s "$TMP/hb3.json" ]] && ! grep -q '"memoryPressurePercent"' "$TMP/hb3.json"; then
  pass "sem PSI o heartbeat não afirma pressão (nem zero)"
else
  fail "sem PSI a pressão foi enviada: $(head -c 300 "$TMP/hb3.json" 2>/dev/null)"
fi
if [[ "$(hb_field swapUsedMb "$TMP/hb3.json")" == "1024" ]]; then
  pass "sem PSI o swap continua medido"
else
  fail "sem PSI o swap sumiu: $(hb_field swapUsedMb "$TMP/hb3.json")"
fi

# 6. Pressão ilegível (arquivo truncado, sem avg300) não vira medida.
P4="$TMP/proc4"; proc_com "$P4" ok ausente
mkdir -p "$P4/pressure"
printf 'some avg10=1.00 avg60=2.00\n' > "$P4/pressure/memory"
R4=$(runtime_com "$P4")
HB_CAPTURE="$TMP/hb4.json" "$TMP/run-cycle.sh" "$R4" "$TMP" || true
if [[ -s "$TMP/hb4.json" ]] && ! grep -q '"memoryPressurePercent"' "$TMP/hb4.json"; then
  pass "PSI sem avg300 não vira medida"
else
  fail "PSI sem avg300 foi enviado: $(head -c 300 "$TMP/hb4.json" 2>/dev/null)"
fi

printf 'node-memory-pressure: PASS %d | FAIL %d\n' "$PASS" "$FAIL"
[[ "$FAIL" -eq 0 ]]