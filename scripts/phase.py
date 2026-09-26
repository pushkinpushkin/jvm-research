#!/usr/bin/env python3
import json
from pathlib import Path
import sys
import time
from experiment_support import utc_now
folder, name = Path(sys.argv[1]), sys.argv[2]
path = folder/'phases.json'
phases = json.loads(path.read_text()) if path.exists() else []
phases.append(dict(name=name, epoch=time.time(), timestamp=utc_now()))
tmp = path.with_suffix('.tmp')
tmp.write_text(json.dumps(phases, indent=2)+'\n')
tmp.replace(path)
(folder/'phase.txt').write_text(name+'\n')
