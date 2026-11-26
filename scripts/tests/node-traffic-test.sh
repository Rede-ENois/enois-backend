#!/usr/bin/env bash
# Tráfego medido pelo runtime do nó (brief §8.2, "uso de rede"; §18, "consultar tráfego").
#
# O que estava errado: o Control Plane aceitava `bytesInMb`/`bytesOutMb` no heartbeat desde a V345
# e o banco tinha as colunas, mas `deploy/node-runtime/node-runtime.sh` nunca lia `/proc/net/dev` —
# o painel do participante e o do admin mostravam tráfego nenhum para todo nó da rede.
#
# O que aqui se mede, rodando o runtime REAL (`cycle`) com um `curl` que captura o corpo e um
# `/proc/net/dev` forjado:
#   1. o acumulado de entrada/saída soma as interfaces que carregam tráfego de FORA e ignora
#      loopback, ponte e veth de container — somar a ponte contaria o mesmo pacote duas vezes;
#   2. o parser não depende da largura do espaço depois de `:` (o defeito clássico é contar
#      posições fixas e a 2ª coluna vazia empurrar todos os índices);
#   3. o valor é o ACUMULADO desde o boot, em MiB, e não a taxa da última volta;
#   4. sem `/proc/net/dev` o campo NÃO vai — ausente é "não medido", nunca zero.

set -uo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
RUNTIME="$ROOT/deploy/node-runtime/node-runtime.sh"

PASS=0
FAIL=0
pass() { printf '[PASS] %s\n' "$1"; PASS=$((PASS + 1)); }
fail() { printf '[FAIL] %s\n' "$1" >&2; FAIL=$((FAIL + 1)); }

[[ -r "$RUNTIME" ]] || { fail "runtime ausente: $RUNTIME"; exit 1; }

TMP=$(mktemp -d "${TMPDIR:-/tmp}/enois-net-XXXXXX")
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

# --- /proc/net/dev do kernel tem esta forma; o cabeçalho começa à esquerda e a linha, com espaço -
netdev() {
  cat > "$1" <<EOF
Inter-|   Receive                                                |  Transmit
 face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed
    lo: 1229261013 1236602    0    0    0     0          0         0 1229261013 1236602    0    0    0     0       0          0
  eth0: 20971520 2492700    0   12    0     0          0         40694 10485760 2205927    0    0    0     0       0          0
 br-8ceac: 5242880 100    0    0    0     0          0         0 5242880   100    0    0    0     0       0          0
docker0: 3145728 60    0    0    0     0          0         0 3145728    60    0    0    0     0       0          0
EOF
}

# Valor numérico de um campo do heartbeat capturado. O corpo é lido como TEXTO aqui de propósito:
# o caso 4 mede, com o leitor de JSON de verdade, que ele é um documento válido.
hb_field() {
  grep -o "\"$1\":[0-9]*" "${2:-$TMP/hb.json}" | head -n 1 | cut -d: -f2
}

# O corpo enviado é JSON válido? Um separador faltando (`0"memoryMb"`) é 400 do Control Plane, e o
# nó que não percebe perde o heartbeat a cada ciclo.
json_ok() {
  python3 -c 'import json,sys; json.load(open(sys.argv[1]))' "$1" 2>/dev/null
}

# 1. Acumulado somando só as interfaces externas. O runtime lê `/proc/net/dev` fixo (é o do
# kernel); a teste usa uma cópia trocando SÓ esse caminho pelo forjado — o `awk` é o real, e é ele
# que está sendo provado.
netdev "$TMP/net"
sed 's|/proc/net/dev|'"$TMP/net"'|g' "$RUNTIME" > "$TMP/runtime-net.sh"
# A substituição não pode deixar nenhuma referência ao arquivo real: tudo tem de apontar ao forjado.
if ! grep -q '/proc/net/dev' "$TMP/runtime-net.sh"; then
  pass "o teste aponta o medidor para um /proc/net/dev forjado, sem deixar o real"
else
  fail "sobrou referência ao /proc/net/dev real na cópia de teste"
fi

HB_CAPTURE="$TMP/hb.json" "$TMP/run-cycle.sh" "$TMP/runtime-net.sh" "$TMP" || true
in_mb=$(hb_field bytesInMb)
out_mb=$(hb_field bytesOutMb)
# eth0 20 MiB entrada / 10 MiB saída; lo, ponte e docker0 ficam de fora.
if [[ "$in_mb" == "20" && "$out_mb" == "10" ]]; then
  pass "acumulado soma as interfaces externas (entrada 20 MiB, saída 10 MiB)"
else
  fail "esperado entrada=20 saída=10; veio entrada=${in_mb:-ausente} saída=${out_mb:-ausente}"
fi
# Uma ponte somada daria 25 MiB e docker0 mais 3 — o número errado por excesso.
if [[ "$in_mb" != "28" && "$in_mb" != "25" ]]; then
  pass "ponte (br-*) e docker0 não entram na conta"
else
  fail "o pacote dos containers foi contado mais de uma vez (entrada=$in_mb)"
fi
# Uma taxa da última volta seria um número pequeno; o acumulado do uptime é 20.
if [[ "${in_mb:-0}" -ge 20 ]]; then
  pass "o valor é o acumulado desde o boot, não a taxa de uma volta"
else
  fail "o campo parece taxa por ciclo (entrada=${in_mb:-ausente})"
fi

# 2. Largura do espaço: o kernel publica `  eth0: <bytes>` e o mesmo medidor tem de acertar quando
# a linha vem sem o espaço depois do `:` (a linha do `cat /proc/net/dev` de algumas versões). A
# segunda forma não pode zerar nem deslocar o índice.
sed 's/  eth0: /  eth0:/' "$TMP/net" > "$TMP/net-justo"
sed 's|'"$TMP/net"'|'"$TMP/net-justo"'|g' "$TMP/runtime-net.sh" > "$TMP/runtime-justo.sh"
HB_CAPTURE="$TMP/hb2.json" "$TMP/run-cycle.sh" "$TMP/runtime-justo.sh" "$TMP" || true
in2=$(grep -o '"bytesInMb":[0-9]*' "$TMP/hb2.json" | head -n 1 | cut -d: -f2)
out2=$(grep -o '"bytesOutMb":[0-9]*' "$TMP/hb2.json" | head -n 1 | cut -d: -f2)
if [[ "$in2" == "20" && "$out2" == "10" ]]; then
  pass "o parser não depende da largura do espaço depois de ':'"
else
  fail "com ':' colado veio entrada=${in2:-ausente} saída=${out2:-ausente} (esperado 20/10)"
fi

# 3. Sem /proc/net/dev, o campo NÃO vai (e não vai como zero).
sed 's|'"$TMP/net"'|'"$TMP/nao-existe"'|g' "$TMP/runtime-net.sh" > "$TMP/runtime-sem.sh"
HB_CAPTURE="$TMP/hb3.json" "$TMP/run-cycle.sh" "$TMP/runtime-sem.sh" "$TMP" || true
if [[ -s "$TMP/hb3.json" ]] && ! grep -q 'bytesInMb\|bytesOutMb' "$TMP/hb3.json"; then
  pass "sem /proc/net/dev o heartbeat não afirma tráfego (nem zero)"
else
  fail "sem /proc/net/dev o campo foi enviado: $(head -c 200 "$TMP/hb3.json" 2>/dev/null)"
fi

# 4. O corpo inteiro é JSON válido, com o tráfego ao lado do resto.
if json_ok "$TMP/hb.json"; then
  pass "o corpo do heartbeat é JSON válido com memória, disco, tráfego e temperatura no mesmo objeto"
else
  fail "o corpo do heartbeat não é JSON válido: $(head -c 260 "$TMP/hb.json" 2>/dev/null)"
fi
if grep -q '"cpuPercent":' "$TMP/hb.json" && grep -q '"runningTasks":' "$TMP/hb.json" \
   && grep -q '"runtimeVersion":' "$TMP/hb.json"; then
  pass "o corpo do heartbeat mantém cpu, tarefas e versão ao lado do tráfego"
else
  fail "o corpo do heartbeat perdeu campos: $(head -c 220 "$TMP/hb.json" 2>/dev/null)"
fi

printf 'node-traffic: PASS %d | FAIL %d\n' "$PASS" "$FAIL"
[[ "$FAIL" -eq 0 ]]