#!/usr/bin/env python3
# Painel de observabilidade local do nó Énóis (TUI, stdlib only — sem instalar nada).
#
#   python3 painel.py            → abre o painel; Q sai; R força atualizar agora
#
# Tudo read-only: config assinada do CP, docker stats, log do executor e saúde
# do host. NÃO manda heartbeat — esse é do runtime; o painel só olha.
#
# Manutenção (regra do projeto): este painel acompanha a EVOLUÇÃO DA REDE.
# Quando um PR mudar o contrato do nó — novos campos em /network/nodes/me/config,
# novos tipos de tarefa (allowedTasks), allowedRoutes, formatVersion da config
# assinada, nomes de eventos no log do runtime — o MESMO PR atualiza este painel.
# Ele é parte da superfície operacional do nó, não um acessório: painel que mostra
# config velha é pior do que nenhum painel. O teste de fumaça é rodar contra o
# ambiente local (ou QA) e conferir que os campos novos aparecem.
import curses
import json
import os
import re
import subprocess
import time
import urllib.error
import urllib.request

NODE_ID = os.environ.get("ENOIS_NODE_ID", "")
CP = os.environ.get("ENOIS_CONTROL_PLANE", "https://api.enois.ia.br")
CONTAINER = os.environ.get("ENOIS_NODE_CONTAINER", "enois-node")
ORIGIN = os.environ.get("ENOIS_ORIGIN", "https://enois.ia.br")
REFRESH_S = float(os.environ.get("ENOIS_PANEL_REFRESH", "3"))
HISTORY_MAX = 120  # pontos do sparkline (~6 min a 3 s)

# Fallbacks para rodar num nó já registrado: lê a identidade do node.json.
NODE_JSON = os.environ.get("ENOIS_NODE_HOME", "/home/mel/enois-node/home.novo") + "/node.json"


def load_node_id():
    if NODE_ID:
        return NODE_ID
    # node.json é 600 do uid 10001: fora do container, o caminho legível é o docker.
    try:
        out = subprocess.run(
            ["docker", "exec", CONTAINER, "cat", "/var/lib/enois-node/node.json"],
            capture_output=True, text=True, timeout=5)
        if out.returncode == 0 and out.stdout.strip():
            return json.loads(out.stdout).get("nodeId", "")
    except Exception:
        pass
    for path in (NODE_JSON, "/var/lib/enois-node/node.json"):
        try:
            with open(path) as f:
                return json.load(f).get("nodeId", "")
        except Exception:
            continue
    return ""


NODE_ID = load_node_id()


def read_api_key():
    # A chave é 600 do uid 10001; o caminho sem sudo é pelo container (o operador
    # do nó está no grupo docker). sudo -n falha sem tty: o timestamp do sudo
    # é por-terminal e o painel roda dentro de um pty sem asking agent.
    for src in (
        ("docker", "exec", CONTAINER, "cat", "/var/lib/enois-node/api-key"),
    ):
        try:
            out = subprocess.run(src, capture_output=True, text=True, timeout=5)
            if out.returncode == 0 and out.stdout.strip():
                return out.stdout.strip()
        except Exception:
            continue
    for path in (NODE_JSON.replace("node.json", "api-key"), "/var/lib/enois-node/api-key"):
        try:
            with open(path) as f:
                key = f.read().strip()
                if key:
                    return key
        except Exception:
            continue
    return None


def fetch_config(key):
    try:
        req = urllib.request.Request(
            f"{CP}/network/nodes/me/config",
            # User-Agent de navegador: a prod fica atrás do Cloudflare, que bloqueia
            # o Python-urllib com "error code: 1010" (403) antes de chegar ao CP.
            headers={"X-Node-Id": NODE_ID, "X-Node-Key": key or "", "Origin": ORIGIN,
                     "User-Agent": "Mozilla/5.0 (X11; Linux x86_64) enois-node-panel"},
        )
        with urllib.request.urlopen(req, timeout=8) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        if e.code == 401:
            return {"_erro": "401 — credencial recusada (a key mudou? gere outra no painel do CP)"}
        return {"_erro": f"HTTP {e.code}"}
    except Exception as e:
        return {"_erro": str(e)[:70]}


def docker_stats():
    try:
        out = subprocess.run(
            ["docker", "stats", "--no-stream", "--format",
             "{{.CPUPerc}}|{{.MemUsage}}|{{.NetIO}}|{{.BlockIO}}", CONTAINER],
            capture_output=True, text=True, timeout=10)
        cpu, mem, net, blk = out.stdout.strip().split("|")
        return {"cpu": cpu, "mem": mem, "net": net, "blk": blk,
                "cpu_pct": float(cpu.rstrip('%').replace(',', '.')) if cpu else 0.0}
    except Exception:
        return {"cpu": "?", "mem": "?", "net": "?", "blk": "?", "cpu_pct": 0.0}


def host_health():
    load = open("/proc/loadavg").read().split()[0:3]
    meminfo = {}
    for line in open("/proc/meminfo"):
        k, v = line.split(":")
        meminfo[k] = int(v.strip().split()[0])  # kB
    total = meminfo.get("MemTotal", 0) / 1048576
    avail = meminfo.get("MemAvailable", 0) / 1048576
    up_s = int(float(open("/proc/uptime").read().split()[0]))
    d, h = divmod(up_s, 86400)
    h, m = divmod(h, 3600)
    ncpu = os.cpu_count() or 1
    load1 = float(load[0])
    return {
        "load": " ".join(load),
        "load_pct": min(100, load1 / ncpu * 100),
        "mem": f"{avail:.1f} livre / {total:.1f} GiB",
        "uptime": f"{d}d {h}h {m}m",
    }


def node_content():
    def du_mb(path):
        try:
            out = subprocess.run(["du", "-sm", path], capture_output=True, text=True, timeout=5)
            return int(out.stdout.split()[0]) if out.returncode == 0 else 0
        except Exception:
            return 0
    home = os.environ.get("ENOIS_NODE_HOME", "/home/mel/enois-node/home.novo")
    return du_mb(f"{home}/edge"), du_mb(f"{home}/replica")


def cursor():
    try:
        out = subprocess.run(
            ["docker", "exec", CONTAINER, "cat", "/var/lib/enois-node/edge/cursor"],
            capture_output=True, text=True, timeout=5)
        return out.stdout.strip() or "0"
    except Exception:
        return "?"


def log_lines(n=160):
    try:
        out = subprocess.run(
            ["docker", "logs", "--tail", str(n), CONTAINER],
            capture_output=True, text=True, timeout=10)
        return [l for l in (out.stdout + out.stderr).splitlines() if l.strip()]
    except Exception:
        return ["(sem acesso ao log — docker indisponível?)"]


TASK_RE = re.compile(r"tarefa (tk_\S+) tipo=(\S+) tentativa=(\d+)")


def parse_events(lines):
    events = []
    for l in lines:
        m = TASK_RE.search(l)
        if m:
            events.append({"id": m.group(1)[-12:], "kind": m.group(2),
                           "flag": f"tent.{m.group(3)}", "detail": ""})
        elif "aceito pela rede" in l:
            events.append({"id": "", "kind": "rede", "flag": "ACEITO",
                           "detail": l.split("aceito pela rede ")[1][:44]})
        elif "recusada" in l or "recusado" in l:
            events.append({"id": "", "kind": "—", "flag": "RECUSADO",
                           "detail": l.split("]")[-1].strip()[:44]})
        elif "assinatura" in l and "não confere" in l:
            events.append({"id": "", "kind": "—", "flag": "SIG FAIL",
                           "detail": "assinatura não confere com o pino"})
        elif "DONE" in l:
            events.append({"id": "", "kind": "tarefa", "flag": "DONE", "detail": ""})
    return events[-8:]


def count_results(lines):
    done = fail = 0
    for l in lines:
        if "DONE" in l:
            done += 1
        elif "FAILED" in l or "TASK_PAYLOAD_INVALID" in l or "EDGE_FEED_FAILED" in l \
                or "API_REQUEST_FAILED" in l:
            fail += 1
    return done, fail


def sparkline(values, width):
    blocks = "▁▂▃▄▅▆▇█"
    if not values:
        return "(sem dados ainda)"
    vals = values[-width:]
    peak = max(vals) or 1.0
    return "".join(blocks[min(7, int(v / peak * 7.999))] for v in vals)


class History:
    def __init__(self):
        self.cpu = []

    def push(self, v):
        self.cpu.append(v)
        del self.cpu[:-HISTORY_MAX]


def fit(s, w):
    return (s[: w - 1] + "…") if len(s) > w else s


def draw(stdscr, st, hist):
    stdscr.erase()
    H, W = stdscr.getmaxyx()
    y = 0

    def put(x, s, attr=0):
        if 0 <= y < H:
            stdscr.addnstr(y, x, fit(s, max(1, W - x - 1)), max(1, W - x - 1), attr)

    A_TITLE = curses.color_pair(1) | curses.A_BOLD
    A_OK = curses.color_pair(2) | curses.A_BOLD
    A_BAD = curses.color_pair(3) | curses.A_BOLD
    A_WARN = curses.color_pair(4) | curses.A_BOLD
    A_DIM = curses.A_DIM
    A_KEY = curses.A_BOLD

    put(0, " ÉNÓIS NODE — painel local ", A_TITLE)
    put(W - 12, time.strftime("%H:%M:%S"), A_DIM)
    y += 1
    if y < H:
        stdscr.addnstr(y, 0, "─" * (W - 1), W - 1, A_DIM)
    y += 1

    cfg = st["cfg"]
    err = cfg.get("_erro")
    if err:
        put(0, f" ✗ Control Plane ({CP}): {err}", A_BAD)
        y += 2
    else:
        status = cfg.get("status", "?")
        attr = A_OK if status == "ACTIVE" else A_BAD
        signed = cfg.get("signed")
        stdscr.addnstr(y, 0, fit(" Nó: ", W - 1), W - 1, A_KEY)
        x = 6
        stdscr.addnstr(y, x, fit(NODE_ID, W - x - 1), W - x - 1)
        x += len(NODE_ID) + 2
        stdscr.addnstr(y, x, fit("Estado:", W - x - 1), W - x - 1, A_KEY)
        x += 8
        stdscr.addnstr(y, x, fit(status, W - x - 1), W - x - 1, attr)
        x += len(status) + 3
        stdscr.addnstr(y, x, fit("assinada:", W - x - 1), W - x - 1, A_KEY)
        x += 10
        sig_txt = "✓ Ed25519" if signed else "✗ SEM ASSINATURA"
        stdscr.addnstr(y, x, fit(sig_txt, W - x - 1), W - x - 1, A_OK if signed else A_BAD)
        y += 1
        put(0, f" config v{cfg.get('configVersion','?')} (formato {cfg.get('formatVersion','?')})   "
               f"termo {cfg.get('term','?')}   emitida {cfg.get('issuedAt','?')}", A_DIM)
        y += 2

        lim = cfg.get("limits", {})
        put(0, " Perfis: ", A_KEY)
        put(9, ", ".join(cfg.get("profiles", [])))
        y += 1
        put(0, " Tarefas: ", A_KEY)
        put(10, ", ".join(cfg.get("allowedTasks", [])))
        y += 1
        routes = cfg.get("allowedRoutes", [])
        put(0, " Rotas:   ", A_KEY)
        put(10, ", ".join(routes) if routes else "(nenhuma declarada na rede)",
            A_DIM if not routes else 0)
        y += 1
        put(0, " Limites: ", A_KEY)
        put(10, f"{lim.get('cpuCores','?')} cores   mem {lim.get('memoryMb','?')} MB   "
                f"disco {lim.get('diskMb','?')/1024:.0f} GiB   banda {lim.get('bandwidthMbps','?')} Mbps")
        y += 1
        free = lim.get("diskFreeMb")
        put(0, " Disco livre medido: ", A_KEY)
        put(21, f"{free/1024:.0f} GiB" if free else "(não medido)")
        y += 2

    s = st["stats"]
    hist.push(s["cpu_pct"])
    put(0, f" Container {CONTAINER}  (histórico CPU ~6 min)", A_KEY)
    y += 1
    put(2, f"CPU {s['cpu']:>7}  {sparkline(hist.cpu, max(10, W - 30))}")
    y += 1
    put(2, f"mem {s['mem']}   rede {s['net']}   bloco {s['blk']}")
    y += 1
    edge, replica = st["content"]
    put(2, f"cache de borda {edge} MB   réplica {replica} MB   cursor seq={st['cursor']}")
    y += 2

    hh = st["host"]
    put(0, f" Host: load {hh['load']}  {sparkline([hh['load_pct']], 6)}   mem {hh['mem']}   uptime {hh['uptime']}", A_DIM)
    y += 1
    done, fail = st["counts"]
    attr = A_OK if fail == 0 else A_WARN
    put(0, f" Janela do log: {done} DONE / {fail} FAILED", attr)
    y += 2

    put(0, " Eventos recentes (do log) ", A_TITLE)
    y += 1
    if y < H:
        stdscr.addnstr(y, 0, "─" * (W - 1), W - 1, A_DIM)
    y += 1
    for ev in st["events"]:
        if y >= H - 6:
            break
        flag = ev["flag"]
        attr = A_OK if flag in ("ACEITO", "DONE") else (
            A_BAD if flag in ("RECUSADO", "SIG FAIL") else 0)
        put(2, f"{ev['id']:<14} {ev['kind']:<18} {flag:<10} {ev['detail']}", attr)
        y += 1
    y += 1

    put(0, " Log do executor (tail) ", A_TITLE)
    y += 1
    if y < H:
        stdscr.addnstr(y, 0, "─" * (W - 1), W - 1, A_DIM)
    y += 1
    for l in st["log"][-max(0, H - y - 1):]:
        if y >= H - 1:
            break
        attr = A_DIM
        if "recusada" in l or "FAIL" in l or "falhou" in l or "400" in l or "502" in l:
            attr = A_BAD
        elif "aceito" in l or "DONE" in l or "em dia" in l:
            attr = A_OK
        put(0, f" {l}", attr)
        y += 1

    put(0, f" [Q] sair  [R] atualizar agora   {REFRESH_S:.0f}s   ", curses.A_REVERSE)


def collect():
    key = read_api_key()
    log = log_lines(160)
    return {
        "cfg": fetch_config(key),
        "stats": docker_stats(),
        "host": host_health(),
        "content": node_content(),
        "cursor": cursor(),
        "log": log,
        "events": parse_events(log),
        "counts": count_results(log),
    }


def main(stdscr):
    curses.curs_set(0)
    curses.start_color()
    curses.use_default_colors()
    for i, fg in enumerate((curses.COLOR_CYAN, curses.COLOR_GREEN, curses.COLOR_RED,
                            curses.COLOR_YELLOW), start=1):
        curses.init_pair(i, fg, -1)
    stdscr.nodelay(True)
    stdscr.timeout(int(REFRESH_S * 1000))

    hist = History()
    while True:
        st = collect()
        draw(stdscr, st, hist)
        ch = stdscr.getch()
        if ch in (ord("q"), ord("Q")):
            return
        if ch in (ord("r"), ord("R")):
            stdscr.timeout(50)  # força o próximo ciclo quase imediato
        else:
            stdscr.timeout(int(REFRESH_S * 1000))


if __name__ == "__main__":
    curses.wrapper(main)
