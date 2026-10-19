#!/usr/bin/env python3
"""Pruebas E2E de conexión y llamadas con DOS dispositivos reales (adb + uiautomator).

Requisitos: dos dispositivos con la app instalada y los permisos (micrófono, Bluetooth, Wi-Fi/
ubicación cercana) ya concedidos, en la misma red Wi-Fi, pantalla desbloqueable sin PIN.

Uso:
    python3 scripts/e2e/two_device_e2e.py                      # todos los escenarios
    python3 scripts/e2e/two_device_e2e.py connect call         # solo algunos
    python3 scripts/e2e/two_device_e2e.py --list
    python3 scripts/e2e/two_device_e2e.py --caller SERIAL --callee SERIAL --call-seconds 30

"caller" es quien busca ("Quiero conectarme!") y llama; "callee" anuncia ("Espero una conexión").
Cada escenario arranca desde cero (force-stop de ambos). Salida: carpeta con logcat de cada lado,
`report.md` y `report.json`. Código de salida 1 si algún chequeo falla.
"""
import argparse
import json
import os
import re
import subprocess
import sys
import time
import traceback
from datetime import datetime

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from adbui import ADB, PKG, Device, connected_serials  # noqa: E402

DISCOVER_TIMEOUT = 75      # el S9 tardó ~33 s en descubrir al Motorola en pruebas manuales
POPUP_TIMEOUT = 25
CONNECT_TIMEOUT = 25
CALL_SETUP_TIMEOUT = 25
REMOTE_DROP_TIMEOUT = 45   # tiempo máximo para que un lado note que el otro murió


class Ctx:
    """Estado compartido de un escenario: dispositivos, resultados y logcat."""

    def __init__(self, caller, callee, out_dir, call_seconds):
        self.caller, self.callee = caller, callee
        self.out_dir = out_dir
        self.call_seconds = call_seconds
        self.checks, self.metrics, self.notes = [], {}, []
        self._logcats = []

    def check(self, name, ok, detail=""):
        self.checks.append({"name": name, "ok": bool(ok), "detail": str(detail)})
        print(f"    {'OK ' if ok else 'FALLO'} {name}" + (f" — {detail}" if detail and not ok else ""))
        return bool(ok)

    def metric(self, name, value):
        self.metrics[name] = value
        print(f"    · {name}: {value}")

    def note(self, text):
        self.notes.append(text)
        print(f"    · {text}")

    def start_logcat(self):
        for dev in (self.caller, self.callee):
            f = open(os.path.join(self.out_dir, f"{dev.label}.logcat"), "w")
            p = subprocess.Popen([ADB, "-s", dev.serial, "logcat", "-v", "threadtime"],
                                 stdout=f, stderr=subprocess.DEVNULL)
            self._logcats.append((p, f))

    def stop_logcat(self):
        for p, f in self._logcats:
            p.terminate()
            p.wait(timeout=5)
            f.close()
        self._logcats = []

    def logcat_text(self, dev):
        path = os.path.join(self.out_dir, f"{dev.label}.logcat")
        with open(path, errors="replace") as f:
            return f.read()


# ── Pasos reutilizables ───────────────────────────────────────────────────────

def tap_in_dialog(dev, marker, button, timeout):
    """Espera a que [marker] esté en pantalla y toca [button] en el mismo volcado (los popups expiran)."""
    end = time.time() + timeout
    while time.time() < end:
        nodes = dev.nodes()
        if any(n.text == marker for n in nodes):
            btn = next((n for n in nodes if n.text == button), None)
            if btn:
                dev.tap(btn)
                return True
        time.sleep(0.2)
    return False


def dismiss_error_dialog(dev):
    if dev.find(text="Hubo un Problema"):
        dev.tap_text("Aceptar", timeout=3)


def fresh_start(ctx):
    for d in (ctx.caller, ctx.callee):
        d.wake()
        d.launch()
    time.sleep(3)
    ctx.start_logcat()


def open_sheet(dev, attempts=3):
    """Abre la hoja de conexión; el primer toque tras el arranque a veces cae durante la animación."""
    dev.wait_for(text="VoLlama - Voice Chat", timeout=15)
    for _ in range(attempts):
        dev.tap_fab()
        if dev.wait_for(text="¡Estás listo para conectarte!", timeout=4):
            return True
    return False


def connect(ctx):
    """Conecta caller↔callee desde cero. Devuelve True si ambos terminan en 'Dispositivos Conectados'."""
    cl, ce = ctx.caller, ctx.callee
    if not ctx.check("ambos abren la hoja de conexión", open_sheet(cl) and open_sheet(ce)):
        return False
    ce.tap_text("Espero una conexión", timeout=10)

    t0 = time.time()
    node = cl.wait_for(text="Conectar", timeout=DISCOVER_TIMEOUT)
    if not ctx.check("el caller descubre al callee", node, f"sin resultados en {DISCOVER_TIMEOUT}s"):
        return False
    ctx.metric("t_descubrir_s", round(time.time() - t0, 1))
    cl.tap(node)

    t1 = time.time()
    if not ctx.check("el callee recibe el popup de conexión",
                     tap_in_dialog(ce, "Solicitud de conexión", "Aceptar", POPUP_TIMEOUT)):
        return False
    ctx.metric("t_popup_s", round(time.time() - t1, 1))

    t2 = time.time()
    # La hoja de conexión no se cierra sola y tapa la lista (uiautomator no ve lo que hay detrás).
    for d in (cl, ce):
        sheet = d.wait_for(desc="Cerrar hoja", timeout=CONNECT_TIMEOUT)
        if sheet:
            stale = d.find(text="Buscando dispositivos...") or d.find(text="Esperando que alguien se conecte...")
            if stale:
                ctx.note(f"[{d.label}] la hoja sigue mostrando '{stale.text}' tras conectar")
            d.tap(sheet)
    ok_cl = cl.wait_for(text="Dispositivos Conectados", timeout=CONNECT_TIMEOUT)
    ok_ce = ce.wait_for(text="Dispositivos Conectados", timeout=CONNECT_TIMEOUT)
    ctx.check("ambos lados muestran 'Dispositivos Conectados'", ok_cl and ok_ce,
              f"caller={bool(ok_cl)} callee={bool(ok_ce)}")
    ctx.metric("t_handshake_s", round(time.time() - t2, 1))
    return bool(ok_cl and ok_ce)


def place_call(ctx, from_dev, to_dev):
    """from_dev toca 'Llamar'; to_dev acepta. Devuelve True si ambos quedan en pantalla de llamada."""
    from_dev.tap_text("Llamar", timeout=10)
    t0 = time.time()
    if not ctx.check("el receptor ve 'Solicitud de llamada'",
                     tap_in_dialog(to_dev, "Solicitud de llamada", "Aceptar llamada", CALL_SETUP_TIMEOUT)):
        return False
    a = from_dev.wait_for(desc="Finalizar llamada", timeout=CALL_SETUP_TIMEOUT)
    b = to_dev.wait_for(desc="Finalizar llamada", timeout=CALL_SETUP_TIMEOUT)
    ok = ctx.check("ambos lados están en la pantalla de llamada", a and b, f"from={bool(a)} to={bool(b)}")
    ctx.metric("t_llamada_s", round(time.time() - t0, 1))
    return ok


def audio_state(dev):
    """Estado de audio del sistema relativo a la app: modo, player activo y grabación activa."""
    pid = dev.sh("shell", "pidof", PKG, check=False).strip().split()
    pid = pid[0] if pid else None
    dump = dev.sh("shell", "dumpsys", "audio", check=False)
    modes = re.findall(r"setMode\((MODE_\w+)\) from package=" + re.escape(PKG), dump)
    playing = bool(pid and re.search(r"AudioTrack[^\n]*/" + pid + r"\b[^\n]*state:started", dump))
    rec_events = re.findall(r"rec (update|start|stop) [^\n]*pack:" + re.escape(PKG), dump)
    return {"pid": pid, "mode": modes[-1] if modes else None, "playing": playing,
            "recording": bool(rec_events and rec_events[-1] != "stop")}


def check_audio_active(ctx, dev, when):
    st = audio_state(dev)
    ctx.check(f"[{dev.label}] {when}: MODE_IN_COMMUNICATION", st["mode"] == "MODE_IN_COMMUNICATION", st)
    ctx.check(f"[{dev.label}] {when}: AudioTrack reproduciendo", st["playing"], st)
    ctx.check(f"[{dev.label}] {when}: micrófono grabando", st["recording"], st)


def check_audio_idle(ctx, dev, when):
    st = audio_state(dev)
    ctx.check(f"[{dev.label}] {when}: audio liberado (MODE_NORMAL, sin player)",
              st["mode"] in (None, "MODE_NORMAL") and not st["playing"], st)


def in_call(dev):
    return bool(dev.find(desc="Finalizar llamada"))


def on_main_menu(dev, timeout=15):
    return bool(dev.wait_for(text="VoLlama - Voice Chat", timeout=timeout))


def scan_crashes(ctx):
    for dev in (ctx.caller, ctx.callee):
        text = ctx.logcat_text(dev)
        crashed = any(PKG in "\n".join(text[m.start():m.start() + 400].splitlines()[1:3])
                      for m in re.finditer(r"FATAL EXCEPTION", text))
        anr = f"ANR in {PKG}" in text
        ctx.check(f"[{dev.label}] sin crash ni ANR de la app", not crashed and not anr,
                  "ver logcat" if crashed or anr else "")


# ── Escenarios ────────────────────────────────────────────────────────────────

def sc_connect(ctx):
    """Descubrir, solicitar, aceptar y ver el dispositivo conectado en ambos lados."""
    connect(ctx)


def sc_call(ctx):
    """Llamada completa: establecer, sostener N segundos con el audio activo y colgar limpio."""
    if not connect(ctx):
        return
    if not place_call(ctx, ctx.caller, ctx.callee):
        return
    time.sleep(3)
    for d in (ctx.caller, ctx.callee):
        check_audio_active(ctx, d, "al inicio")
    time.sleep(max(0, ctx.call_seconds - 3))
    for d in (ctx.caller, ctx.callee):
        check_audio_active(ctx, d, f"tras {ctx.call_seconds}s")
    ctx.check("la llamada sigue activa en ambos lados", in_call(ctx.caller) and in_call(ctx.callee))

    ctx.caller.tap_text(desc="Finalizar llamada")
    t0 = time.time()
    ctx.check("el callee sale de la llamada al colgar el caller",
              ctx.callee.wait_for(text="VoLlama - Voice Chat", timeout=15))
    ctx.metric("t_colgar_remoto_s", round(time.time() - t0, 1))
    ctx.check("el caller vuelve al menú", on_main_menu(ctx.caller))
    time.sleep(1)
    for d in (ctx.caller, ctx.callee):
        check_audio_idle(ctx, d, "tras colgar")


def sc_callee_hangs_up(ctx):
    """Quien recibe cuelga: el que llamó debe salir de la llamada y liberar el audio."""
    if not connect(ctx) or not place_call(ctx, ctx.caller, ctx.callee):
        return
    time.sleep(3)
    ctx.callee.tap_text(desc="Finalizar llamada")
    t0 = time.time()
    ctx.check("el caller sale de la llamada", ctx.caller.wait_for(text="VoLlama - Voice Chat", timeout=15))
    ctx.metric("t_colgar_remoto_s", round(time.time() - t0, 1))
    time.sleep(1)
    for d in (ctx.caller, ctx.callee):
        check_audio_idle(ctx, d, "tras colgar")


def sc_reverse_call(ctx):
    """El que anuncia (callee) inicia la llamada hacia el que buscó."""
    if not connect(ctx) or not place_call(ctx, ctx.callee, ctx.caller):
        return
    time.sleep(3)
    for d in (ctx.caller, ctx.callee):
        check_audio_active(ctx, d, "llamada inversa")
    ctx.callee.tap_text(desc="Finalizar llamada")
    ctx.check("ambos vuelven al menú", on_main_menu(ctx.caller) and on_main_menu(ctx.callee))


def sc_reject_call(ctx):
    """Rechazar la llamada: el que llamó ve 'Llamada rechazada' y nadie queda con audio abierto."""
    if not connect(ctx):
        return
    ctx.caller.tap_text("Llamar", timeout=10)
    ok = tap_in_dialog(ctx.callee, "Solicitud de llamada", "Rechazar", CALL_SETUP_TIMEOUT)
    if not ctx.check("el receptor ve y rechaza la solicitud", ok):
        return
    ctx.check("el caller muestra 'Llamada rechazada'",
              ctx.caller.wait_for(text="Llamada rechazada", timeout=10))
    ctx.check("el caller vuelve al menú", on_main_menu(ctx.caller, timeout=10))
    for d in (ctx.caller, ctx.callee):
        check_audio_idle(ctx, d, "tras rechazo")


def sc_kill_during_call(ctx):
    """Matar el proceso del callee a media llamada: el caller no debe quedar en una llamada zombie."""
    if not connect(ctx) or not place_call(ctx, ctx.caller, ctx.callee):
        return
    time.sleep(4)
    ctx.callee.stop()
    t0 = time.time()
    left = False
    while time.time() - t0 < REMOTE_DROP_TIMEOUT:
        if not in_call(ctx.caller):
            left = True
            break
        time.sleep(1)
    ctx.metric("t_caller_detecta_caida_s", round(time.time() - t0, 1) if left else None)
    ctx.check(f"el caller sale de la llamada en <{REMOTE_DROP_TIMEOUT}s", left)
    ctx.check("el caller queda en el menú", on_main_menu(ctx.caller, timeout=10))
    check_audio_idle(ctx, ctx.caller, "tras caída del remoto")


def sc_reconnect(ctx):
    """Conectar, desconectar desde la UI y volver a conectar sin reiniciar la app."""
    if not connect(ctx):
        return
    ctx.caller.tap_text(desc="Desconectar", timeout=10)
    ctx.check("el caller deja de mostrar el dispositivo",
              ctx.caller.wait_for(text="No hay dispositivos conectados", timeout=15))
    ctx.check("el callee también (no queda enlace a medias)",
              ctx.callee.wait_for(text="No hay dispositivos conectados", timeout=30))
    dismiss_error_dialog(ctx.caller)
    dismiss_error_dialog(ctx.callee)
    ctx.note("segundo intento de conexión sin reiniciar la app")
    connect(ctx)


def sc_wifi_drop(ctx):
    """Apagar el Wi-Fi del callee en plena llamada. Informativo: solo exige que no haya crash ni llamada zombie."""
    if not connect(ctx) or not place_call(ctx, ctx.caller, ctx.callee):
        return
    time.sleep(4)
    try:
        ctx.callee.sh("shell", "svc", "wifi", "disable", check=False)
        t0 = time.time()
        left = False
        while time.time() - t0 < REMOTE_DROP_TIMEOUT:
            if not in_call(ctx.caller):
                left = True
                break
            time.sleep(1)
        ctx.metric("t_caller_detecta_corte_s", round(time.time() - t0, 1) if left else None)
        ctx.note("la llamada continuó (otro medio, p. ej. Bluetooth)" if not left
                 else "la llamada terminó al cortar el Wi-Fi")
    finally:
        ctx.callee.sh("shell", "svc", "wifi", "enable", check=False)
    if in_call(ctx.caller):
        ctx.caller.tap_text(desc="Finalizar llamada")
    ctx.check("el caller vuelve al menú", on_main_menu(ctx.caller, timeout=20))


SCENARIOS = {
    "connect": sc_connect,
    "call": sc_call,
    "callee_hangs_up": sc_callee_hangs_up,
    "reverse_call": sc_reverse_call,
    "reject_call": sc_reject_call,
    "kill_during_call": sc_kill_during_call,
    "reconnect": sc_reconnect,
    "wifi_drop": sc_wifi_drop,
}


def run_scenario(name, fn, caller, callee, root, call_seconds):
    out = os.path.join(root, name)
    os.makedirs(out, exist_ok=True)
    ctx = Ctx(caller, callee, out, call_seconds)
    print(f"\n▶ {name}: {(fn.__doc__ or '').strip()}")
    t0 = time.time()
    error = None
    try:
        fresh_start(ctx)
        fn(ctx)
    except Exception as e:  # un fallo de infraestructura/UI cuenta como fallo del escenario
        error = f"{type(e).__name__}: {e}"
        ctx.check("el escenario terminó sin excepciones", False, error)
        traceback.print_exc()
    finally:
        for d in (caller, callee):
            try:
                d.screenshot(os.path.join(out, f"{d.label}.png"))
            except Exception:
                pass
        ctx.stop_logcat()
    try:
        scan_crashes(ctx)
    except Exception as e:
        ctx.note(f"no se pudo analizar el logcat: {e}")
    for d in (caller, callee):
        d.stop()
    return {"name": name, "doc": (fn.__doc__ or "").strip(), "seconds": round(time.time() - t0, 1),
            "checks": ctx.checks, "metrics": ctx.metrics, "notes": ctx.notes, "error": error,
            "passed": all(c["ok"] for c in ctx.checks)}


def write_reports(results, root, caller, callee):
    with open(os.path.join(root, "report.json"), "w") as f:
        json.dump(results, f, indent=2, ensure_ascii=False)
    lines = [f"# E2E VoLlama — {datetime.now():%Y-%m-%d %H:%M}", "",
             f"- caller: {caller.label} (`{caller.serial}`)", f"- callee: {callee.label} (`{callee.serial}`)", "",
             "| Escenario | Resultado | Tiempo | Métricas |", "|---|---|---|---|"]
    for r in results:
        m = ", ".join(f"{k}={v}" for k, v in r["metrics"].items())
        lines.append(f"| {r['name']} | {'✅' if r['passed'] else '❌'} | {r['seconds']}s | {m} |")
    for r in results:
        failed = [c for c in r["checks"] if not c["ok"]]
        if failed:
            lines += ["", f"## {r['name']} — fallos"] + [f"- **{c['name']}**: {c['detail']}" for c in failed]
        if r["notes"]:
            lines += ["", f"### {r['name']} — notas"] + [f"- {n}" for n in r["notes"]]
    with open(os.path.join(root, "report.md"), "w") as f:
        f.write("\n".join(lines) + "\n")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("scenarios", nargs="*", help="escenarios a correr (por defecto, todos)")
    ap.add_argument("--list", action="store_true")
    ap.add_argument("--caller")
    ap.add_argument("--callee")
    ap.add_argument("--call-seconds", type=int, default=20)
    ap.add_argument("--out", default=None, help="carpeta de salida (por defecto scripts/e2e/runs/<fecha>)")
    args = ap.parse_args()

    if args.list:
        for n, fn in SCENARIOS.items():
            print(f"{n:18} {(fn.__doc__ or '').strip()}")
        return 0
    unknown = [s for s in args.scenarios if s not in SCENARIOS]
    if unknown:
        print(f"Escenarios desconocidos: {unknown}. Usa --list.")
        return 2

    serials = connected_serials()
    caller_s = args.caller or (serials[0] if serials else None)
    callee_s = args.callee or next((s for s in serials if s != caller_s), None)
    if not caller_s or not callee_s or caller_s == callee_s:
        print(f"Se necesitan dos dispositivos distintos por adb (vistos: {serials}).")
        return 2
    caller = Device(caller_s)
    callee = Device(callee_s)
    caller.label, callee.label = f"caller-{caller.model().replace(' ', '_')}", f"callee-{callee.model().replace(' ', '_')}"

    root = args.out or os.path.join(os.path.dirname(os.path.abspath(__file__)), "runs",
                                    datetime.now().strftime("%Y%m%d-%H%M%S"))
    os.makedirs(root, exist_ok=True)
    print(f"Salida: {root}")

    for d in (caller, callee):
        d.sh("shell", "svc", "power", "stayon", "true", check=False)
    results = []
    try:
        for name in (args.scenarios or list(SCENARIOS)):
            results.append(run_scenario(name, SCENARIOS[name], caller, callee, root, args.call_seconds))
    finally:
        for d in (caller, callee):
            d.sh("shell", "svc", "power", "stayon", "false", check=False)
            d.sh("shell", "svc", "wifi", "enable", check=False)
        write_reports(results, root, caller, callee)

    print("\n══ Resumen ══")
    for r in results:
        print(f"  {'OK   ' if r['passed'] else 'FALLO'} {r['name']} ({r['seconds']}s)")
    print(f"Reporte: {os.path.join(root, 'report.md')}")
    return 0 if all(r["passed"] for r in results) else 1


if __name__ == "__main__":
    sys.exit(main())
