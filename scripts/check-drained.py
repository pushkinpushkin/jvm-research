#!/usr/bin/env python3
import json
from pathlib import Path
import sys
state = json.loads(Path(sys.argv[1]).read_text())
bounds, counters = state['bounds'], state['counters']
ready = all(bounds[k] == 0 for k in ('pendingEvents','waiting','retryable'))
ready &= counters['events_enqueued'] == counters['events_published'] == counters['events_consumed']
sys.exit(0 if ready else 1)
