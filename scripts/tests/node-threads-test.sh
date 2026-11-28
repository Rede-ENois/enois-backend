#!/usr/bin/env bash
# Threads do processo do runtime (brief §8.2, bloco "Runtime", item "Threads").
#
# O que faltava: o runtime é o que mede o host, e o `Threads:` do próprio processo não era lido —
# o painel do nó não tinha como dizer se o processo abriu thread demais (encoder, sinal,
# subprocesso), e a coluna não existia (V388).
#
# O que aqui se mede, rodando o runtime REAL (`cycle`) com um `curl` que captura o corpo e um
# `/proc` forjado:
#   1. `Threads:` de `/proc/<pid>/status` vai no heartbeat como `threads`, com o número do arquivo
#      (não um valor fixo, não o de `/proc/loadavg`);
#   2. é o `status` do PROCESSO (`self`), e não do sistema: o diretório forjado só tem `self`;
#   3. sem `/proc/<pid>/status` o campo NÃO vai — ausente é "não medido", nunca zero;
#   4. um `Threads:` não numérico (arquivo truncado, lixo) NÃO vira medida;
#   5. o corpo inteiro continua JSON válido com o campo no lugar.

set -uo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
RUNTIME="$ROOT/deploy/node-runtime/node-runtime.sh"

PASS=0
FAIL=0
pass() { printf '[PASS] %s\n' "$1"; PASS=$((PASS + 1)); }
fail() { printf '[FAIL] %s\n' "$1" >&2; FAIL=$((FAIL + 1)); }

[[ -r "$RUNTIME" ]] || { fail "runtime ausente: $RUNTIME"; exit 1; }

TMP=$(mktemp -d "${TMPDIR:-/tmp}/enois-thr-XXXXXX")
cleanup() { [[ -n "${KEEP:-}" ]] || rm -rf "$TMP"; }
trap cleanup EXIT

# --- ambiente de uma volta do ciclo --------------------------------------------------------------
# Não há curl falso de propósito: o de verdade é ponteiro do PATH (`command -v` no stub do
# runner), então o do sistema fica INTEIRO fora do PATH — inclusive o `/usr/bin/curl` que o
# runner acha antes de trocar o PATH.
LINK="$TMP/link"
mkdir -p "$LINK" "$TMP/home"
IFS=: read -r -a path_dirs <<< "$PATH"
for dir in "${path_dirs[@]}"; do
  [[ -d "$dir" ]] || continue
  for exe in "$dir"/*; do
    [[ -f "$exe" && -x "$exe" ]] || continue
    base=$(basename "$exe")
    [[ -e "$LINK/$base" ]] && continue
    ln -s "$exe" "$LINK/$base"
  done
done
# os utilitários usados pelo runtime, garantidos mesmo que o PATH do teste seja mínimo
for dir in /usr/bin /bin /usr/sbin /sbin; do
  [[ -d "$dir" ]] || continue
  for base in bash sh awk sed grep cat df date chmod head tail cut tr od wc mkdir rm sleep env dirname basename realpath find openssl df; do
    [[ -e "$LINK/$base" || -L "$LINK/$base" || ! -x "$dir/$base" ]] && continue
    ln -s "$dir/$base" "$LINK/$base"
  done
done
# curl falso: o do sistema é um ponteiro, não um arquivo — tirar antes de escrever o stub.
rm -f "$LINK/curl"
# curl falso: grava o corpo do heartbeat e responde um JSON mínimo (o ciclo lê `mode`/`status`).
cat > "$LINK/curl" <<'STUB'
#!/bin/bash
for arg in "$@"; do
  case "$arg" in
    -d) capture=1 ;;
    *) if [[ "${capture:-}" == 1 ]]; then printf '%s' "$arg" > "$HB_CAPTURE"; capture=0; fi ;;
  esac
done
printf '{"mode":"NORMAL","status":"ACTIVE","acceptTasks":false}'
STUB
chmod +x "$LINK/curl"

# Identidade mínima que o runtime exige antes de qualquer ciclo (o enroll não é o que se prova
# aqui): o heartbeat só sai se este arquivo e a chave existirem.
printf '{"nodeId":"nd_trafego"}\n' > "$TMP/home/node.json"
printf 'chave-de-teste\n' > "$TMP/home/api-key"

# Executa o runtime real até o fim do `cycle()` e para: o laço final trocado por uma chamada única.
cat > "$TMP/run-cycle.sh" <<'RUNNER'
#!/bin/bash
script="$1"
tmp="$2"
# `cycle` é chamada uma vez no lugar do `while true`; o resto é o script como está.
# Cada volta deriva o seu: derivar sobre um arquivo JÁ derivado perderia o `cycle` e a volta
# seguinte não enviaria heartbeat nenhum (o teste mediria a ausência que ele mesmo criou).
sed 's/^while true; do$/cycle; exit 0/' "$script" > "$tmp/one-cycle.sh"
if ! grep -q '^cycle; exit 0' "$tmp/one-cycle.sh"; then
  printf '[FAIL] não achei o laço final em %s para trocar pela chamada única\n' "$script" >&2
  exit 1
fi
# `HB_CAPTURE` vem de fora (um arquivo por volta); atribuir aqui sobrescreveria o do chamador.
NODE_HOME="$tmp/home" CONTROL_PLANE_URL="http://stub" NODE_POLL_SECONDS=0 PATH="$tmp/link" \
  bash "$tmp/one-cycle.sh" >"$tmp/out.log" 2>&1
RUNNER
chmod +x "$TMP/run-cycle.sh"

# --- /proc forjado: só o `self` do processo, como o kernel publica -----------------------------
# Valor numérico de um campo do heartbeat capturado.
hb_field() {
  grep -o "\"$1\":[0-9]*" "${2:-$TMP/hb.json}" | head -n 1 | cut -d: -f2
}

# O corpo enviado é JSON válido?
json_ok() {
  python3 -c 'import json,sys; json.load(open(sys.argv[1]))' "$1" 2>/dev/null
}

# 1. O `Threads:` do processo vai como `threads`.
PARENT="$TMP/proc"
mkdir -p "$PARENT/self"
printf 'Name:\tbash\nState:\tR (running)\nPid:\t4242\nVmRSS:\t  4096 kB\nThreads:\t7\n' > "$PARENT/self/status"
sed 's|/proc|'"$PARENT"'|g' "$RUNTIME" > "$TMP/runtime-threads.sh"
if ! grep -q '/proc/self/status' "$TMP/runtime-threads.sh"; then
  pass "o teste aponta a leitura para um /proc forjado, sem deixar o real"
else
  fail "sobrou referência ao /proc real na cópia de teste"
fi
HB_CAPTURE="$TMP/hb.json" "$TMP/run-cycle.sh" "$TMP/runtime-threads.sh" "$TMP" || true
threads=$(hb_field threads)
if [[ "$threads" == "7" ]]; then
  pass "o Threads: do processo vai no heartbeat (7)"
else
  fail "esperado threads=7; veio ${threads:-ausente}"
fi
# 7 é o do arquivo; um valor do sistema (loadavg inteiro, nº de CPUs) não casaria com ele de
# propósito. O número é lido do status, não inventado.
if [[ "$threads" != "1" && "$threads" != "0" ]]; then
  pass "o número é o do arquivo, não um valor fixo nem zero"
else
  fail "threads saiu ${threads:-ausente} — parece valor fixo"
fi

# 2. Outro Threads: muda o valor (o campo é lido a cada ciclo, não memorizado).
printf 'Name:\tbash\nThreads:\t11\n' > "$PARENT/self/status"
HB_CAPTURE="$TMP/hb2.json" "$TMP/run-cycle.sh" "$TMP/runtime-threads.sh" "$TMP" || true
threads2=$(hb_field threads "$TMP/hb2.json")
if [[ "$threads2" == "11" ]]; then
  pass "o valor acompanha o arquivo (11 no ciclo seguinte)"
else
  fail "esperado threads=11 na 2ª volta; veio ${threads2:-ausente}"
fi

# 3. Sem /proc/<pid>/status o campo NÃO vai (nem zero).
SEM="$TMP/proc-vazio"
mkdir -p "$SEM"
sed 's|/proc|'"$SEM"'|g' "$RUNTIME" > "$TMP/runtime-sem.sh"
HB_CAPTURE="$TMP/hb3.json" "$TMP/run-cycle.sh" "$TMP/runtime-sem.sh" "$TMP" || true
if [[ -s "$TMP/hb3.json" ]] && ! grep -q '"threads"' "$TMP/hb3.json"; then
  pass "sem /proc/<pid>/status o heartbeat não afirma threads (nem zero)"
else
  fail "sem o status o campo foi enviado: $(head -c 200 "$TMP/hb3.json" 2>/dev/null)"
fi

# 4. `Threads:` não numérico não vira medida.
rm -rf "$PARENT/self"; mkdir -p "$PARENT/self"
printf 'Name:\tbash\nThreads:\t??\n' > "$PARENT/self/status"
HB_CAPTURE="$TMP/hb4.json" "$TMP/run-cycle.sh" "$TMP/runtime-threads.sh" "$TMP" || true
if [[ -s "$TMP/hb4.json" ]] && ! grep -q '"threads"' "$TMP/hb4.json"; then
  pass "Threads: ilegível NÃO vira medida"
else
  fail "Threads: ilegível foi enviado: $(head -c 200 "$TMP/hb4.json" 2>/dev/null)"
fi

# O caso 1 já expôs isto: com o /proc forjado o corpo saiu
# `"runningTasks":0,,"temperatureC"` — quando NÃO há memória medida (`mem_part` vazio), o fragmento
# seguinte carrega a vírgula e fica com duas. Antes o defeito era a falta de vírgula (ciclo 161,
# 400 do Control Plane); este é o irmão. O corpo em hb.json é o que o caso 1 gravou com o /proc
# forjado e SEM /proc/meminfo: é o estado normal de um container.
if [[ -s "$TMP/hb.json" ]]; then
  if json_ok "$TMP/hb.json"; then
    pass "corpo sem memória medida é JSON válido (sem vírgula dupla)"
  else
    fail "corpo sem memória medida não é JSON válido: $(head -c 260 "$TMP/hb.json" 2>/dev/null)"
  fi
else
  fail "o caso 1 não gravou corpo para conferir"
fi

# 5. O corpo inteiro continua JSON válido com o campo no lugar.
if json_ok "$TMP/hb.json" && json_ok "$TMP/hb2.json"; then
  pass "o corpo do heartbeat é JSON válido com threads ao lado do resto"
else
  fail "o corpo do heartbeat não é JSON válido: $(head -c 260 "$TMP/hb.json" 2>/dev/null)"
fi

printf 'node-threads: PASS %d | FAIL %d\n' "$PASS" "$FAIL"
[[ "$FAIL" -eq 0 ]]
