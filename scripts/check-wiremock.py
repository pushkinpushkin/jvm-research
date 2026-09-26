#!/usr/bin/env python3
"""Direct functional probe of all mappings, run before starting the measured application."""
import json
import socket
import sys
import time
import urllib.error
import urllib.request

base = sys.argv[1] if len(sys.argv)>1 else 'http://localhost:8089'
results=[]
for route,field,expected in [('fns-data/probe','externalStatus','FNS_OK'),('process/status/probe','status','DONE')]:
    for mode in ('ok','slow','long_delay','error','bad_response'):
        start=time.monotonic()
        status=None; body=b''; timed_out=False
        try:
            with urllib.request.urlopen(f'{base}/external/{route}?mode={mode}', timeout=2) as response:
                status=response.status; body=response.read()
        except urllib.error.HTTPError as error:
            status=error.code; body=error.read()
        except (socket.timeout,TimeoutError): timed_out=True
        elapsed=time.monotonic()-start
        if mode=='long_delay': assert timed_out and elapsed>=1.8, (route,mode,status,elapsed)
        elif mode=='error': assert status==503, (route,mode,status)
        elif mode=='bad_response':
            assert status==200, (route,mode,status)
            try: json.loads(body)
            except ValueError: pass
            else: raise AssertionError('Bad response is valid JSON')
        else:
            assert status==200 and json.loads(body)[field]==expected, (route,mode,status,body)
            if mode=='slow': assert elapsed>=1.1, elapsed
        results.append(dict(route=route,mode=mode,status=status,timedOut=timed_out,seconds=elapsed))
print(json.dumps({'passed':True,'checks':results},indent=2))
