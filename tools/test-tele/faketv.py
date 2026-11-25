"""Fausse télé Philips Titan OS (DMR) pour tester Castor sans la vraie télé."""
import os, http.server, socketserver, threading, time, re, html, subprocess, urllib.request, sys
DUR = float(sys.argv[1]) if len(sys.argv) > 1 else 180.0
PATIENCE = float(sys.argv[2]) if len(sys.argv) > 2 else 10.0
ADB = [os.environ.get('ADB', 'adb'), '-s', 'emulator-5554']
LOCK = threading.Lock()
S = {'state': 'NO_MEDIA_PRESENT', 'uri': None, 'pos': 0.0, 't': None, 'total': None, 'gen': 0}
STATS = {'concurrent': 0, 'max_concurrent': 0, 'soap': 0}
def log(*a): print(time.strftime('%H:%M:%S'), *a, flush=True)
def now_pos():
    if S['state'] == 'PLAYING' and S['t']:
        p = S['pos'] + time.time() - S['t']
        if p >= DUR and not (S['uri'] or '').endswith('.jpg'):  # fin de la vidéo : la vraie télé repart sur HDMI
            hdmi('fin de la vidéo'); S['pos'] = 0.0
            # Comme la Philips : le lecteur se rouvre, la prochaine commande Play se perd (FAKE_REOPEN=1).
            if os.environ.get('FAKE_REOPEN'): S['reopen'] = True
            return 0.0
        return min(DUR, p)
    return S['pos']
def local(url):
    m = re.match(r'http://[^:/]+:(\d+)(/.*)', url); port = m.group(1)
    subprocess.run(ADB + ['forward', f'tcp:{port}', f'tcp:{port}'], capture_output=True)
    return f'http://127.0.0.1:{port}{m.group(2)}'
def hdmi(why):
    log('*** LECTEUR FERMÉ → la vraie télé partirait sur HDMI :', why)
    S['state'] = 'STOPPED'; S['t'] = None
def player(gen, target):
    """Le lecteur GStreamer : demande la vidéo à partir de la position voulue, abandonne après 10 s."""
    url = local(S['uri']); total = S['total'] or 0
    off = int(total * target / DUR) if total else 0
    t0 = time.time()
    try:
        req = urllib.request.Request(url, headers={'Range': f'bytes={off}-', 'User-Agent': 'GStreamer souphttpsrc'})
        r = urllib.request.urlopen(req, timeout=PATIENCE); r.read(65536); r.close()
    except Exception as e:
        if gen == S['gen']: hdmi(f'vidéo pas arrivée en 10 s ({type(e).__name__}) à {target:.0f}s')
        return
    if gen != S['gen']: return
    log(f'   lecture à {target:.0f}s (octet {off}) prête en {time.time()-t0:.1f}s')
    S['pos'] = target; S['t'] = time.time(); S['state'] = 'PLAYING'
def reply(h, body, code=200):
    x = f'<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>{body}</s:Body></s:Envelope>'.encode()
    h.send_response(code); h.send_header('Content-Type', 'text/xml'); h.send_header('Content-Length', str(len(x))); h.end_headers(); h.wfile.write(x)
def err(h, c, d): reply(h, f'<s:Fault><detail><UPnPError><errorCode>{c}</errorCode><errorDescription>{d}</errorDescription></UPnPError></detail></s:Fault>', 500)
def hms(t): t = int(t); return f'{t//3600}:{t//60%60:02d}:{t%60:02d}'
DESC = '''<?xml version="1.0"?><root xmlns="urn:schemas-upnp-org:device-1-0"><device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
<friendlyName>55OLED760/12 Test(10.0.2.2)</friendlyName><manufacturer>Smart TV</manufacturer><modelName>Smart TV</modelName><UDN>uuid:fake-philips</UDN><serviceList>
<service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType><controlURL>/rcr_control</controlURL></service>
<service><serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType><controlURL>/cmr_control</controlURL></service>
<service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>/avt_control</controlURL></service></serviceList></device></root>'''
SINKS = ','.join(f'http-get:*:{m}:*' for m in ['video/mpeg','video/mp4','video/x-mkv','video/avi','audio/mpeg','audio/mp4','video/vnd.dlna.mpeg-tts'])
class H(http.server.BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    def log_message(self, *a): pass
    def do_GET(self):
        b = DESC.encode(); self.send_response(200); self.send_header('Content-Type', 'text/xml'); self.send_header('Content-Length', str(len(b))); self.end_headers(); self.wfile.write(b)
    def do_POST(self):
        with LOCK:
            STATS['concurrent'] += 1; STATS['max_concurrent'] = max(STATS['max_concurrent'], STATS['concurrent']); STATS['soap'] += 1
        try: self.soap()
        finally:
            with LOCK: STATS['concurrent'] -= 1
    def soap(self):
        body = self.rfile.read(int(self.headers.get('Content-Length', 0))).decode()
        action = re.search(r'#(\w+)', self.headers.get('SOAPACTION', '')).group(1)
        if action not in ('GetPositionInfo', 'GetTransportInfo', 'GetVolume'): log('SOAP', action, S['state'])
        if action == 'SetAVTransportURI':
            if S['state'] in ('PLAYING', 'TRANSITIONING'): log('   refus : Transport locked'); return err(self, 705, 'Transport locked')
            uri = html.unescape(re.search(r'<CurrentURI>(.*?)</CurrentURI>', body).group(1))
            meta = html.unescape(re.search(r'<CurrentURIMetaData>(.*?)</CurrentURIMetaData>', body, re.S).group(1))
            for tag in ('albumArtURI', 'CaptionInfoEx'):
                m2 = re.search(r'<[^>]*' + tag + r'[^>]*>(.*?)<', meta)
                if m2:
                    try: n = len(urllib.request.urlopen(local(m2.group(1)), timeout=5).read()); log(f'   {tag} servi : {n} octets')
                    except Exception as e: log(f'   {tag} ERREUR {e}')
            d = re.search(r'duration="([^"]+)"', meta); log('   durée annoncée', d.group(1) if d else '—')
            t0 = time.time()
            try:  # la vraie télé interroge la vidéo AVANT de répondre
                r = urllib.request.urlopen(urllib.request.Request(local(uri), method='HEAD'), timeout=20)
                total = r.headers.get('Content-Length'); r.close()
            except Exception as e:
                log('   HEAD échoué', e); return err(self, 716, 'Resource not found')
            log(f'   HEAD de la vidéo en {time.time()-t0:.1f}s, taille={total}')
            S.update(uri=uri, total=int(total) if total else None, state='STOPPED', pos=0.0, t=None); S['gen'] += 1
            return reply(self, '<u:SetAVTransportURIResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"/>')
        if action == 'Play':
            if S.get('reopen'):
                S['reopen'] = False; log('   Play perdu (lecteur en réouverture)')
                return reply(self, '<u:PlayResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"/>')
            if S['state'] == 'PAUSED_PLAYBACK': S['t'] = time.time(); S['state'] = 'PLAYING'
            elif S['state'] in ('STOPPED',) and S['uri']:
                S['gen'] += 1; S['state'] = 'TRANSITIONING'; threading.Thread(target=player, args=(S['gen'], 0.0), daemon=True).start()
            return reply(self, '<u:PlayResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"/>')
        if action == 'Pause':
            S['pos'] = now_pos(); S['t'] = None; S['state'] = 'PAUSED_PLAYBACK'
            return reply(self, '<u:PauseResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"/>')
        if action == 'Stop':
            if S['state'] != 'NO_MEDIA_PRESENT': hdmi('ordre Stop reçu')
            S['gen'] += 1
            return reply(self, '<u:StopResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"/>')
        if action == 'Seek':
            if S['state'] == 'TRANSITIONING': log('   refus 710 (déjà en déplacement)'); return err(self, 710, 'Seek mode not supported')
            m = re.search(r'<Target>(\d+):(\d+):(\d+)', body); target = int(m.group(1))*3600 + int(m.group(2))*60 + int(m.group(3))
            log(f'   déplacement vers {target}s')
            S['gen'] += 1; S['pos'] = target; S['state'] = 'TRANSITIONING'
            threading.Thread(target=player, args=(S['gen'], float(target)), daemon=True).start()
            return reply(self, '<u:SeekResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"/>')
        if action == 'GetPositionInfo':
            return reply(self, f'<u:GetPositionInfoResponse xmlns:u="x"><TrackDuration>{'0:00:00' if os.environ.get('FAKE_NODUR') else hms(DUR)}</TrackDuration><RelTime>{hms(now_pos())}</RelTime></u:GetPositionInfoResponse>')
        if action == 'GetTransportInfo':
            now_pos()
            return reply(self, f'<u:GetTransportInfoResponse xmlns:u="x"><CurrentTransportState>{S["state"]}</CurrentTransportState></u:GetTransportInfoResponse>')
        if action == 'GetVolume': return reply(self, '<u:R xmlns:u="x"><CurrentVolume>12</CurrentVolume></u:R>')
        if action == 'SetVolume': return reply(self, '<u:R xmlns:u="x"/>')
        if action == 'GetProtocolInfo': return reply(self, f'<u:R xmlns:u="x"><Source></Source><Sink>{SINKS}</Sink></u:R>')
        err(self, 401, 'Invalid Action')
class Srv(socketserver.ThreadingMixIn, http.server.HTTPServer): daemon_threads = True; allow_reuse_address = True
srv = Srv(('0.0.0.0', 49152), H)
def stats():
    while True:
        time.sleep(15); log(f'[stats] requêtes SOAP={STATS["soap"]} simultanées max={STATS["max_concurrent"]}')
threading.Thread(target=stats, daemon=True).start()
log('fausse télé prête sur :49152'); srv.serve_forever()
