"""Faux site lent façon torrent : Referer exigé, plages lointaines servies après un délai."""
import http.server, socketserver, sys, os, re, time
ROOT, DELAY = sys.argv[1], float(sys.argv[2])
SEEN = {}
class H(http.server.BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    def log_message(self, *a): pass
    def do_HEAD(self): self.go(True)
    def do_GET(self): self.go(False)
    def go(self, head):
        if self.headers.get('Referer') != 'https://site.example/':
            self.send_response(403); self.send_header('Content-Length', '0'); self.end_headers(); return
        data = open(os.path.join(ROOT, os.path.basename(self.path.split('?')[0])), 'rb').read(); total = len(data)
        rg = self.headers.get('Range'); start, end, code = 0, total - 1, 200
        if rg:
            m = re.match(r'bytes=(\d*)-(\d*)', rg)
            if m.group(1): start = int(m.group(1)); end = int(m.group(2)) if m.group(2) else total - 1; code = 206
        if start > total * 0.05 and not head:
            b = start // 1_000_000; first = SEEN.setdefault(b, time.time()); left = first + DELAY - time.time()
            if left > 0:
                print(time.strftime('%H:%M:%S'), f'source : morceau {b} pas encore téléchargé → attente {left:.0f}s', flush=True); time.sleep(left)
            else:
                print(time.strftime('%H:%M:%S'), f'source : morceau {b} déjà téléchargé → immédiat', flush=True)
        self.send_response(code); self.send_header('Content-Type', 'video/mp4'); self.send_header('Accept-Ranges', 'bytes')
        self.send_header('Content-Length', str(end - start + 1))
        if code == 206: self.send_header('Content-Range', f'bytes {start}-{end}/{total}')
        self.end_headers()
        if not head:
            try: self.wfile.write(data[start:end + 1])
            except Exception: pass
class Srv(socketserver.ThreadingMixIn, http.server.HTTPServer): daemon_threads = True; allow_reuse_address = True
Srv(('0.0.0.0', 8765), H).serve_forever()
