import os, sys, json, time, ctypes, getpass, threading, subprocess, webbrowser
import urllib.request, urllib.parse, urllib.error

try:
    import winsound
except ImportError:
    winsound = None

CARTELLA = os.path.dirname(sys.executable if getattr(sys, "frozen", False) else os.path.abspath(__file__))
FILE_CONFIG = os.path.join(CARTELLA, "config.json")
PROGRAMMI = {"calc": "calc.exe", "notepad": "notepad.exe", "explorer": "explorer.exe", "mspaint": "mspaint.exe"}
stop_suono = threading.Event()

def carica_config():
    try:
        with open(FILE_CONFIG, encoding="utf-8") as f:
            c = json.load(f)
        if c.get("indirizzo") and c.get("nome") and c.get("chiave"):
            return c
    except Exception:
        pass
    print("Prima configurazione di Jarvis PC")
    ind = input("Indirizzo del cervello (Invio per http://100.96.189.65:5000/): ").strip() or "http://100.96.189.65:5000/"
    if not ind.endswith("/"):
        ind += "/"
    nome = input("Nome di questo PC (Invio per PC): ").strip() or "PC"
    chiave = getpass.getpass("Chiave (non compare mentre la incolli): ").strip()
    c = {"indirizzo": ind, "nome": nome, "chiave": chiave}
    with open(FILE_CONFIG, "w", encoding="utf-8") as f:
        json.dump(c, f)
    return c

def chiedi(c, percorso, corpo=None):
    dati = json.dumps(corpo).encode("utf-8") if corpo is not None else None
    req = urllib.request.Request(c["indirizzo"] + percorso, data=dati)
    req.add_header("X-Chiave", c["chiave"])
    if dati is not None:
        req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, timeout=8) as r:
        return json.loads(r.read().decode("utf-8"))

def tasto(vk, n=1):
    for _ in range(n):
        ctypes.windll.user32.keybd_event(vk, 0, 0, 0)
        ctypes.windll.user32.keybd_event(vk, 0, 2, 0)
        time.sleep(0.03)

def suona():
    stop_suono.clear()
    def corpo():
        fine = time.time() + 20
        while time.time() < fine and not stop_suono.is_set():
            if winsound:
                winsound.Beep(1200, 350)
            time.sleep(0.15)
    threading.Thread(target=corpo, daemon=True).start()

def avvisa(testo):
    def corpo():
        if winsound:
            for _ in range(4):
                winsound.Beep(1000, 300)
                time.sleep(0.1)
        ctypes.windll.user32.MessageBoxW(0, testo, "Jarvis", 0x40 | 0x40000)
    threading.Thread(target=corpo, daemon=True).start()

def timer(sec):
    t = threading.Timer(sec, avvisa, ["Timer terminato, signore."])
    t.daemon = True
    t.start()

def esegui(cmd):
    tipo, arg = cmd.get("tipo", ""), str(cmd.get("arg", ""))
    print(time.strftime("%H:%M:%S"), "comando:", tipo, arg)
    if tipo == "apri_sito" and arg.startswith("https://"):
        webbrowser.open(arg)
    elif tipo == "apri_prog" and arg in PROGRAMMI:
        subprocess.Popen([PROGRAMMI[arg]])
    elif tipo == "volume_su":
        tasto(0xAF, 5)
    elif tipo == "volume_giu":
        tasto(0xAE, 5)
    elif tipo == "volume_muto":
        tasto(0xAD)
    elif tipo == "blocca":
        ctypes.windll.user32.LockWorkStation()
    elif tipo == "suona":
        suona()
    elif tipo == "suona_stop":
        stop_suono.set()
    elif tipo == "timer":
        timer(int(arg))

def main():
    c = carica_config()
    print("Jarvis PC attivo come '%s'. Lascia aperta questa finestra (puoi ridurla a icona)." % c["nome"])
    ultima = 0
    ultimo_errore = ""
    while True:
        try:
            if time.time() - ultima > 20:
                chiedi(c, "api/dispositivi", {"nome": c["nome"]})
                ultima = time.time()
            r = chiedi(c, "api/comandi?nome=" + urllib.parse.quote(c["nome"]))
            ultimo_errore = ""
            for cmd in r.get("comandi", []):
                try:
                    esegui(cmd)
                except Exception as e:
                    print("errore nel comando:", e)
        except urllib.error.HTTPError as e:
            if e.code == 401:
                print("Chiave non valida: cancello la configurazione. Riavvia Jarvis PC e riscrivi la chiave.")
                try:
                    os.remove(FILE_CONFIG)
                except Exception:
                    pass
                input("Premi Invio per chiudere...")
                return
            ultimo_errore = "errore " + str(e.code)
        except Exception as e:
            if str(e) != ultimo_errore:
                print("In attesa del cervello:", e)
            ultimo_errore = str(e)
        time.sleep(3)

if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        print("Errore:", e)
        input("Premi Invio per chiudere...")
