import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from bwserver.config import Settings  # noqa: E402
from bwserver.engine import Engine  # noqa: E402
from bwserver.push import NullPush  # noqa: E402
from bwserver.quotes import DemoSource  # noqa: E402
from bwserver.store import Store  # noqa: E402


class SpyPush:
    configured = True

    def __init__(self):
        self.sent = []

    def send(self, tokens, data):
        self.sent.append((list(tokens), dict(data)))
        return []


@pytest.fixture
def eng(tmp_path):
    settings = Settings(tmp_path)
    settings.update(market_hours=False)
    e = Engine(settings, Store(), DemoSource(), SpyPush())
    for s, n in [("AAPL", "Apple"), ("TSLA", "Tesla"), ("ENI.MI", "Eni")]:
        e.store.add_ticker(s, n)
    e.refresh()
    return e
