#!/usr/bin/env bash
# Throughput de rede MEDIDO — taxa do intervalo, não o acumulado (brief §8.2, "Sistema operacional":
# "Throughput de entrada e saída"; §8.3: "Throughput global").
#
# O que faltava: o nó manda `bytesInMb`/`bytesOutMb` desde a V345, e esse número é o ACUMULADO desde o
# boot — um contador que só sobe. Ele responde "quanto já passou por aqui" (o painel do participante,
# §18) e não "quanto está passando agora", que é o que o §8.2 pede. A diferença entre dois acumulados
# dividida pelo intervalo entre eles não pode ser refeita pelo Control Plane: o intervalo de cada
# amostra não fica gravado. Por isso a taxa é calculada no runtime, que guarda o instante da medida
# anterior.
#
# O que aqui se mede, rodando o ciclo REAL (`cycle`) com `/proc/net/dev` forjado e o `curl` do sistema
# capturando o corpo:
#   1. sem medida anterior (primeiro ciclo) a taxa NÃO vai — ela exigiria um intervalo que ninguém
#      viu, e o acumulado vai;
#   2. com o intervalo conhecido, a taxa é a diferença dividida pelos segundos, em MiB/s, e não o
#      acumulado;
#   3. uma volta sem tráfego novo diz 0.000 — medição de agora, não repetição do último número;
#   4. contador que RETROCEDEU (interface recriada) não vira taxa: o campo não vai;
#   5. o acumulado continua indo em MiB e a taxa em campo próprio — os dois no mesmo corpo, JSON
#      válido;
#   6. o estado da próxima volta é o de agora, mesmo quando esta não mediu (senão um contador
#      reiniciado travaria a taxa para sempre).

set -uo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
RUNTIME="$ROOT/deploy/node-runtime/node-runtime.sh"

PASS=0
FAIL=0
pass() { printf '[PASS] %s\n' "$1"; PASS=$((PASS + 1)); }
fail() { printf '[FAIL] %s\n' "$1" >&2; FAIL=$((FAIL + 1)); }

[[ -r "$RUNTIME" ]] || { fail "runtime ausente: $RUNTIME"; exit 1; }

TMP=$(mktemp -d "${TMPDIR:-/tmp}/enois-rate-XXXXXX")
cleanup() { [[ -n "${KEEP:-}" ]] || rm -rf "$TMP"; }
trap cleanup EXIT

# --- ambiente de uma volta do ciclo --------------------------------------------------------------
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
for dir in /usr/bin /bin /usr/sbin /sbin; do
  [[ -d "$dir" ]] || continue
  for base in bash sh awk sed grep cat df date chmod head tail cut tr od wc mkdir rm sleep env dirname basename realpath find openssl; do
    [[ -e "$LINK/$base" || -L "$LINK/$base" || ! -x "$dir/$base" ]] && continue
    ln -s "$dir/$base" "$LINK/$base"
  done
done
rm -f "$LINK/curl"
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

printf '{"nodeId":"nd_taxa"}\n' > "$TMP/home/node.json"
printf 'chave-de-teste\n' > "$TMP/home/api-key"

# Uma volta do `cycle()` e para. `NET_DEV_FILE` e `NETSTATE_FILE` vêm de fora: o runtime lê `/proc`
# fixo em produção, e o teste serve contadores e estado conhecidos.
cat > "$TMP/run-cycle.sh" <<'RUNNER'
#!/bin/bash
script="$1"
tmp="$2"
sed 's/^while true; do$/cycle; exit 0/' "$script" > "$tmp/one-cycle.sh"
grep -q '^cycle; exit 0' "$tmp/one-cycle.sh" || { printf '[FAIL] laço final não achado\n' >&2; exit 1; }
NODE_HOME="$tmp/home" CONTROL_PLANE_URL="http://stub" NODE_POLL_SECONDS=0 PATH="$tmp/link" \
  bash "$tmp/one-cycle.sh" >"$tmp/out.log" 2>&1
RUNNER
chmod +x "$TMP/run-cycle.sh"

# /proc/net/dev forjado. Só `eth0` conta (as internas ficam de fora); o valor é o acumulado em BYTES.
netdev() { # netdev <arquivo> <rx_bytes> <tx_bytes>
  cat > "$1" <<EOF
Inter-|   Receive                                                |  Transmit
 face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed
    lo: 999999999 1236602    0    0    0     0          0         0 999999999 1236602    0    0    0     0       0          0
  eth0: $2 $(( $2 / 800 ))    0   12    0     0          0         40694 $3 $(( $3 / 800 ))    0    0    0     0       0          0
 br-8ceac: 5242880 100    0    0    0     0          0         0 5242880   100    0    0    0     0       0          0
EOF
}

hb_field() { grep -o "\"$1\":[-0-9.]*" "${2:-$TMP/hb.json}" | head -n 1 | cut -d: -f2; }
json_ok() { python3 -c 'import json,sys; json.load(open(sys.argv[1]))' "$1" 2>/dev/null; }

volta() { # volta <rx> <tx>  -> preenche $TMP/hb.json
  netdev "$TMP/net" "$1" "$2"
  NET_DEV_FILE="$TMP/net" NETSTATE_FILE="$TMP/home/net-prev" HB_CAPTURE="$TMP/hb.json" \
    "$TMP/run-cycle.sh" "$RUNTIME" "$TMP" || true
}

MIB=1048576

# --- 1. primeiro ciclo: sem anterior, a taxa não vai; o acumulado vai ---------------------------
rm -f "$TMP/home/net-prev"
volta $((10 * MIB)) $((4 * MIB))
if [[ -z "$(hb_field netInMbps)" && -z "$(hb_field netOutMbps)" ]]; then
  pass "sem medida anterior a taxa não vai (intervalo não medido, ninguém adivinha)"
else
  fail "no primeiro ciclo veio taxa: $(cat "$TMP/hb.json")"
fi
if [[ "$(hb_field bytesInMb)" == "10" && "$(hb_field bytesOutMb)" == "4" ]]; then
  pass "no primeiro ciclo o acumulado continua indo, em MiB"
else
  fail "acumulado do primeiro ciclo: entrada=$(hb_field bytesInMb) saída=$(hb_field bytesOutMb)"
fi

# --- 2. intervalo de ~10 s e 2 MiB de entrada: 0.200 MiB/s ---------------------------------------
# O instante anterior é fixado 10,5 s atrás: o intervalo real fica entre 10 e 10,5 s (o `cycle`
# leva alguns décimos entre o início e a leitura do relógio), e 2 MiB divididos por ele caem em
# 0.190–0.200 MiB/s. Fixar exatamente 10 s mediria também a subida do ciclo, que não é do nó.
printf '%s %s %s\n' "$(( $(date +%s%N) - 10500000000 ))" $((10 * MIB)) $((4 * MIB)) > "$TMP/home/net-prev"
volta $((12 * MIB)) $((4 * MIB))
in_rate=$(hb_field netInMbps)
out_rate=$(hb_field netOutMbps)
if awk -v v="${in_rate:-0}" 'BEGIN {exit !(v >= 0.190 && v <= 0.200)}'; then
  pass "com ~10 s de intervalo e 2 MiB recebidos a entrada é ~0.200 MiB/s (diferença, não acumulado)"
else
  fail "entrada esperada ~0.200 MiB/s; veio ${in_rate:-ausente}"
fi
if [[ "$out_rate" == "0.000" ]]; then
  pass "saída parada no intervalo é 0.000 medido — não a repetição do último número"
else
  fail "saída esperada 0.000; veio ${out_rate:-ausente}"
fi
json_ok "$TMP/hb.json" && pass "o corpo com as duas medidas continua JSON válido" \
  || fail "corpo inválido: $(cat "$TMP/hb.json")"

# --- 3. o acumulado não é a taxa ---------------------------------------------------------------
if [[ "$(hb_field bytesInMb)" == "12" ]] && awk -v v="${in_rate:-0}" 'BEGIN {exit !(v > 0.1)}'; then
  pass "o acumulado (12 MiB) e a taxa (~0.2 MiB/s) convivem em campos próprios"
else
  fail "acumulado e taxa se confundiram: bytesInMb=$(hb_field bytesInMb) netInMbps=$in_rate"
fi

# --- 4. contador que retrocedeu não vira taxa ----------------------------------------------------
printf '%s %s %s\n' "$(( $(date +%s%N) - 5000000000 ))" $((500 * MIB)) $((500 * MIB)) > "$TMP/home/net-prev"
volta $((1 * MIB)) $((1 * MIB))
if [[ -z "$(hb_field netInMbps)" && -z "$(hb_field netOutMbps)" ]]; then
  pass "contador que retrocedeu (interface recriada) não vira taxa"
else
  fail "contador reiniciado produziu taxa: $(cat "$TMP/hb.json")"
fi

# --- 4.1 o ciclo SEGUINTE já mede: o estado avançou no ciclo que não mediu -------------------------
# Se o estado só fosse gravado nas voltas que medem, este segundo ciclo veria de novo o contador de
# 500 MiB de antes da recriação — e ficaria sem taxa para sempre, um ciclo após o outro.
sleep 1
volta $((2 * MIB)) $((1 * MIB))
in_rate=$(hb_field netInMbps)
if awk -v v="${in_rate:-0}" 'BEGIN {exit !(v > 0)}'; then
  pass "no ciclo seguinte à recriação a taxa volta ($in_rate MiB/s), sem esperar reinício"
else
  fail "o ciclo seguinte à recriação ficou sem taxa: ${in_rate:-ausente}"
fi

# --- 5. e a taxa continua sendo medida nas voltas seguintes ----------------------------------
printf '%s %s %s\n' "$(( $(date +%s%N) - 4000000000 ))" $((1 * MIB)) $((1 * MIB)) > "$TMP/home/net-prev"
volta $((1 * MIB + 104858)) $((1 * MIB))
in_rate=$(hb_field netInMbps)
# 104858 bytes = 0.100 MiB em 4 s ≈ 0.025 MiB/s (a tolerância fica na casa dos milésimos)
if [[ "$in_rate" =~ ^0\.02[0-9]$ ]]; then
  pass "depois do contador reiniciado a taxa volta a ser medida ($in_rate MiB/s)"
else
  fail "taxa depois do reinício: ${in_rate:-ausente}"
fi

# --- 6. o ciclo que MEDIU deixou o estado dele ---------------------------------------------------
# Logo depois de uma volta com taxa, o estado tem de ser o de agora (1 MiB + 104858 bytes). Se o
# estado só avançasse nos ciclos sem medida, este arquivo ainda seria o da recriação (1 MiB) — e a
# diferença de 104858 bytes teria ido embora sem ninguém a ver.
prev=$(cut -d' ' -f2 "$TMP/home/net-prev")
if [[ "$prev" == "$((1 * MIB + 104858))" ]]; then
  pass "o estado avança também no ciclo que mediu (a próxima volta parte de agora)"
else
  fail "estado depois de um ciclo que mediu: [$prev]"
fi

# --- 7. /proc/net/dev ilegível: nem acumulado nem taxa, e nada de zero -------------------------
rm -f "$TMP/home/net-prev"
NET_DEV_FILE="$TMP/nao-existe" NETSTATE_FILE="$TMP/home/net-prev" HB_CAPTURE="$TMP/hb.json" \
  "$TMP/run-cycle.sh" "$RUNTIME" "$TMP" || true
if [[ -z "$(hb_field bytesInMb)" && -z "$(hb_field netInMbps)" ]] && json_ok "$TMP/hb.json"; then
  pass "sem /proc/net/dev não vai acumulado nem taxa, e o corpo segue válido"
else
  fail "sem /proc/net/dev o corpo mudou: $(cat "$TMP/hb.json")"
fi

printf '\n'
if (( FAIL == 0 )); then
  printf 'Todos os cenarios passaram (%d assercoes).\n' "$PASS"
  exit 0
fi
printf 'Falhas: %d de %d assercoes.\n' "$FAIL" "$((PASS + FAIL))"
exit 1