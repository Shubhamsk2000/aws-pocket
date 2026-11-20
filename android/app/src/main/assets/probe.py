#!/usr/bin/env python3
"""Pocket probe: the app uploads this to ~/.cache/aws-pocket/ and runs it over SSH.

Standard library only, prints one JSON object. Commands:
  stats                      cpu jiffies, memory, disks, load, network counters
  ports                      listening TCP ports + owning process + HTTP title
  procs                      processes with CPU % over a short sample
  services                   running systemd services (+ docker containers)
  logs <unit> <n>            last n journal lines of a unit (or docker:<name>)
  svc <action> <unit>        start | stop | restart a unit (sudo -n)
  kill <pid> <signal>        send TERM or KILL
"""
import json, os, re, socket, subprocess, sys, time
from concurrent.futures import ThreadPoolExecutor

HZ = os.sysconf("SC_CLK_TCK")


def sh(args, timeout=6):
    try:
        r = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
        return r.returncode, r.stdout, r.stderr
    except Exception as e:  # noqa: BLE001
        return 127, "", str(e)


def has_sudo():
    return sh(["sudo", "-n", "true"], 3)[0] == 0


def read(p, default=""):
    try:
        with open(p) as f:
            return f.read()
    except OSError:
        return default


# ---------------------------------------------------------------- stats
def stats():
    cpu = [int(x) for x in read("/proc/stat").splitlines()[0].split()[1:]]
    mem = {}
    for line in read("/proc/meminfo").splitlines():
        k, _, v = line.partition(":")
        mem[k] = int(v.split()[0]) * 1024
    disks, seen = [], set()
    for line in read("/proc/mounts").splitlines():
        dev, mnt, fs = line.split()[:3]
        if not dev.startswith("/dev/") or fs in ("squashfs", "tmpfs") or mnt.startswith(("/boot", "/snap")):
            continue
        try:
            s = os.statvfs(mnt)
        except OSError:
            continue
        if dev in seen:
            continue
        seen.add(dev)
        disks.append({"mount": mnt, "total": s.f_blocks * s.f_frsize, "free": s.f_bavail * s.f_frsize})
    rx = tx = 0
    for line in read("/proc/net/dev").splitlines()[2:]:
        name, _, rest = line.partition(":")
        if name.strip() == "lo":
            continue
        f = rest.split()
        rx += int(f[0]); tx += int(f[8])
    la = os.getloadavg()
    return {
        "host": socket.gethostname(),
        "os": (re.search(r'PRETTY_NAME="([^"]+)"', read("/etc/os-release")) or [None, "Linux"])[1],
        "kernel": os.uname().release,
        "cores": os.cpu_count(),
        "uptime": float(read("/proc/uptime", "0 0").split()[0]),
        "load": [round(x, 2) for x in la],
        "cpu": {"idle": cpu[3] + cpu[4], "total": sum(cpu[:8])},
        "mem": {"total": mem.get("MemTotal", 0), "available": mem.get("MemAvailable", 0)},
        "swap": {"total": mem.get("SwapTotal", 0), "free": mem.get("SwapFree", 0)},
        "disks": disks,
        "net": {"rx": rx, "tx": tx},
        "procs": sum(1 for d in os.listdir("/proc") if d.isdigit()),
        "time": time.time(),
    }


# ---------------------------------------------------------------- ports
def proc_info(pid):
    cmd = read(f"/proc/{pid}/cmdline").replace("\0", " ").strip()
    try:
        cwd = os.readlink(f"/proc/{pid}/cwd")
    except OSError:
        cwd = ""
    st = read(f"/proc/{pid}/status")
    rss = re.search(r"VmRSS:\s+(\d+)", st)
    uid = re.search(r"Uid:\s+(\d+)", st)
    user = ""
    if uid:
        try:
            import pwd
            user = pwd.getpwuid(int(uid[1])).pw_name
        except Exception:  # noqa: BLE001
            user = uid[1]
    return {"cmd": cmd[:400], "cwd": cwd, "rss": int(rss[1]) * 1024 if rss else 0, "user": user}


def http_probe(port):
    """(is_http, title) from a quick GET / on localhost."""
    try:
        s = socket.create_connection(("127.0.0.1", port), timeout=0.6)
        s.settimeout(0.8)
        s.sendall(b"GET / HTTP/1.0\r\nHost: localhost\r\nUser-Agent: pocket\r\nAccept: text/html\r\n\r\n")
        buf = b""
        while len(buf) < 32768:
            chunk = s.recv(8192)
            if not chunk:
                break
            buf += chunk
            if b"</title>" in buf.lower():
                break
        s.close()
    except Exception:  # noqa: BLE001
        return False, None
    if not buf.startswith(b"HTTP/"):
        return False, None
    m = re.search(rb"<title[^>]*>(.*?)</title>", buf, re.I | re.S)
    title = m[1].decode("utf-8", "replace").strip()[:80] if m else None
    if not title:
        sm = re.search(rb"\r\nServer:\s*([^\r\n]+)", buf, re.I)
        title = sm[1].decode("latin-1").strip()[:60] if sm else None
    return True, title


def ports():
    sudo = has_sudo()
    rc, out, _ = sh((["sudo", "-n"] if sudo else []) + ["ss", "-tlnpH"])
    if rc != 0:
        rc, out, _ = sh(["ss", "-tlnpH"])
    rows = {}
    for line in out.splitlines():
        f = line.split()
        if len(f) < 4:
            continue
        local = f[3]
        addr, _, port = local.rpartition(":")
        if not port.isdigit():
            continue
        port = int(port)
        addr = addr.strip("[]").split("%")[0]
        m = re.search(r'\("([^"]+)",pid=(\d+)', line)
        r = rows.setdefault(port, {"port": port, "addrs": [], "process": None, "pid": None})
        if addr not in r["addrs"]:
            r["addrs"].append(addr)
        if m and not r["pid"]:
            r["process"], r["pid"] = m[1], int(m[2])
    items = sorted(rows.values(), key=lambda r: r["port"])
    skip = {22, 53}
    with ThreadPoolExecutor(16) as ex:
        probes = dict(zip([r["port"] for r in items],
                          ex.map(lambda r: http_probe(r["port"]) if r["port"] not in skip else (False, None), items)))
    for r in items:
        r["http"], r["title"] = probes[r["port"]]
        r["public"] = any(a in ("0.0.0.0", "::", "*") for a in r["addrs"])
        if r["pid"]:
            r.update(proc_info(r["pid"]))
    return {"ports": items, "sudo": sudo}


# ---------------------------------------------------------------- procs
def _ticks():
    out = {}
    for d in os.listdir("/proc"):
        if not d.isdigit():
            continue
        s = read(f"/proc/{d}/stat")
        if not s:
            continue
        rp = s.rfind(")")
        f = s[rp + 2:].split()
        out[int(d)] = (s[s.find("(") + 1:rp], int(f[11]) + int(f[12]), int(f[19]))
    return out


def procs():
    a = _ticks(); t0 = time.time()
    time.sleep(0.5)
    b = _ticks(); dt = time.time() - t0
    uptime = float(read("/proc/uptime", "0 0").split()[0])
    rows = []
    for pid, (name, ticks, start) in b.items():
        prev = a.get(pid)
        cpu = (ticks - prev[1]) / HZ / dt * 100 if prev else 0.0
        info = proc_info(pid)
        if not info["cmd"]:
            continue  # kernel threads
        rows.append({"pid": pid, "name": name, "cpu": round(cpu, 1), "rss": info["rss"], "user": info["user"],
                     "cmd": info["cmd"][:200], "age": round(uptime - start / HZ)})
    rows.sort(key=lambda r: (r["cpu"], r["rss"]), reverse=True)
    return {"procs": rows[:80], "me": os.getuid()}


# ---------------------------------------------------------------- services
def services():
    rc, out, _ = sh(["systemctl", "list-units", "--type=service", "--all", "--no-legend", "--plain", "--no-pager"])
    items = []
    for line in out.splitlines():
        f = line.split(None, 4)
        if len(f) < 4 or f[2] == "inactive" and f[1] != "loaded":
            continue
        if f[2] == "inactive" and f[3] == "dead":
            continue
        items.append({"unit": f[0], "load": f[1], "active": f[2], "sub": f[3], "desc": f[4] if len(f) > 4 else ""})
    containers = []
    rc, out, _ = sh(["docker", "ps", "-a", "--format", "{{json .}}"], 5)
    if rc != 0:
        rc, out, _ = sh(["sudo", "-n", "docker", "ps", "-a", "--format", "{{json .}}"], 5)
    if rc == 0:
        for line in out.splitlines():
            try:
                c = json.loads(line)
                containers.append({"name": c.get("Names"), "image": c.get("Image"), "state": c.get("State"),
                                   "status": c.get("Status"), "ports": c.get("Ports")})
            except ValueError:
                pass
    return {"services": items, "containers": containers, "sudo": has_sudo()}


def logs(unit, n):
    n = str(max(10, min(int(n), 2000)))
    if unit.startswith("docker:"):
        name = unit[7:]
        rc, out, err = sh(["docker", "logs", "--tail", n, "--timestamps", name], 10)
        if rc != 0:
            rc, out, err = sh(["sudo", "-n", "docker", "logs", "--tail", n, "--timestamps", name], 10)
        return {"lines": (out + err).splitlines()[-int(n):]}
    args = ["journalctl", "-u", unit, "-n", n, "--no-pager", "-o", "short-iso"]
    rc, out, err = sh(args, 10)
    if rc != 0 or "No journal files" in out or not out.strip():
        rc2, out2, err2 = sh(["sudo", "-n"] + args, 10)
        if rc2 == 0:
            out, err = out2, err2
    return {"lines": out.splitlines() or err.splitlines()}


def svc(action, unit):
    if action not in ("start", "stop", "restart"):
        return {"ok": False, "error": "bad action"}
    if unit.startswith("docker:"):
        rc, out, err = sh(["docker", action, unit[7:]], 30)
        if rc != 0:
            rc, out, err = sh(["sudo", "-n", "docker", action, unit[7:]], 30)
    else:
        rc, out, err = sh(["sudo", "-n", "systemctl", action, unit], 30)
    return {"ok": rc == 0, "error": (err or out).strip()[-400:] if rc else None}


def kill(pid, sig):
    import signal
    s = signal.SIGKILL if sig == "KILL" else signal.SIGTERM
    try:
        os.kill(int(pid), s)
        return {"ok": True}
    except PermissionError:
        rc, _, err = sh(["sudo", "-n", "kill", "-" + ("9" if sig == "KILL" else "15"), str(int(pid))])
        return {"ok": rc == 0, "error": err.strip() or None}
    except ProcessLookupError:
        return {"ok": True}
    except Exception as e:  # noqa: BLE001
        return {"ok": False, "error": str(e)}


CMDS = {"stats": stats, "ports": ports, "procs": procs, "services": services, "logs": logs, "svc": svc, "kill": kill}

if __name__ == "__main__":
    try:
        res = CMDS[sys.argv[1]](*sys.argv[2:])
    except Exception as e:  # noqa: BLE001
        res = {"error": f"{type(e).__name__}: {e}"}
    sys.stdout.write(json.dumps(res, separators=(",", ":")))
