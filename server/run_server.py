"""Punto d'ingresso per PyInstaller (BorsaServer.exe)."""
import sys

if getattr(sys, "frozen", False) and sys.stdout is None:   # senza console
    import io
    sys.stdout = sys.stderr = io.StringIO()

from bwserver.main import main  # noqa: E402

if __name__ == "__main__":
    main()
