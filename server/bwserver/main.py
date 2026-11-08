"""Avvio del server:  python -m bwserver   (oppure BorsaServer.exe)"""
import argparse
import logging
import sys
import threading
from pathlib import Path

from . import tools as T
from .api import local_ips, make_server
from .config import VERSION, Settings, data_dir
from .engine import Engine
from .push import make_push
from .quotes import DemoSource, YahooSource
from .store import Store


def build(folder: Path, demo=False):
    settings = Settings(folder)
    store = Store(folder / "borsa.db")
    source = DemoSource() if demo else YahooSource()
    eng = Engine(settings, store, source, make_push(settings))
    # alla prima accensione, se accanto al programma c'è tickers.txt, lo importa
    if not store.tickers():
        for cand in (folder / "tickers.txt", Path(sys.argv[0]).resolve().parent / "tickers.txt"):
            if cand.exists():
                T.REGISTRY["import_tickers"]["run"](eng, cand.read_text(encoding="utf-8-sig"))
                break
        else:
            if demo:
                for sym, name in DemoSource.CATALOG.items():
                    store.add_ticker(sym, name)
    return eng


def main(argv=None):
    ap = argparse.ArgumentParser(description="Borsa Watch - server")
    ap.add_argument("--demo", action="store_true", help="prezzi inventati, per provare senza Yahoo")
    ap.add_argument("--port", type=int)
    ap.add_argument("--data", help="cartella dei dati")
    a = ap.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s")
    folder = Path(a.data) if a.data else data_dir()
    folder.mkdir(parents=True, exist_ok=True)
    eng = build(folder, demo=a.demo)
    if a.port:
        eng.settings.update(port=a.port)
    srv = make_server(eng)
    stop = threading.Event()
    threading.Thread(target=eng.run, args=(stop,), daemon=True).start()
    port = eng.settings["port"]
    print(f"Borsa Watch server {VERSION}{' (DEMO)' if a.demo else ''}")
    print(f"  Codice di abbinamento: {eng.settings['token']}")
    for ip in local_ips():
        print(f"  Indirizzo: http://{ip}:{port}")
    print(f"  Pagina di stato sul PC: http://localhost:{port}/")
    print(f"  Dati in: {folder}")
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        stop.set()
        srv.server_close()


if __name__ == "__main__":
    main()
