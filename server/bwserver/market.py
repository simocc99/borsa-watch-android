"""Orari di borsa (ora italiana)."""
from datetime import datetime, time

try:
    from zoneinfo import ZoneInfo
    TZ = ZoneInfo("Europe/Rome")
except Exception:  # senza database dei fusi si usa l'ora della macchina
    TZ = None

OPEN = time(9, 0)
CLOSE = time(22, 5)


def now() -> datetime:
    return datetime.now(TZ) if TZ else datetime.now()


def is_open(dt: datetime | None = None) -> bool:
    dt = dt or now()
    return dt.weekday() < 5 and OPEN <= dt.time() <= CLOSE


def after_close(dt: datetime | None = None) -> bool:
    dt = dt or now()
    return dt.weekday() < 5 and dt.time() > CLOSE
