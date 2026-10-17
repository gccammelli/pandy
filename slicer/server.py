"""Pandy Slicer: slices 3MF projects (MakerWorld print profiles) with Bambu Studio's command line, for Pandy.

GET  /health                   -> {"ok": true, "version": "..."}
POST /slice?plate=1            body: the .3mf -> the sliced .gcode.3mf (headers X-Pandy-Result: JSON summary)
POST /slice?plate=1&upload=1   same, then puts the file on the printer over FTPS and answers JSON
                               {"path": "cache/<name>.gcode.3mf", "plate": 1, ...}; the printer's address, serial and
                               access code come in the X-Printer-Ip, X-Printer-Serial and X-Printer-Code headers.

If PANDY_SLICER_TOKEN is set, requests need "Authorization: Bearer <token>". One slice runs at a time.
Standard library only.
"""
import ftplib
import glob
import io
import json
import os
import re
import shutil
import ssl
import subprocess
import tempfile
import threading
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

BAMBU = os.environ.get("BAMBU_STUDIO", "/opt/bambu-studio/AppRun")
PORT = int(os.environ.get("PANDY_SLICER_PORT", "8737"))
TOKEN = os.environ.get("PANDY_SLICER_TOKEN", "").strip()
# As a Home Assistant add-on, the token is set in the add-on's Configuration tab.
if os.path.exists("/data/options.json"):
    with open("/data/options.json") as _f:
        TOKEN = (json.load(_f).get("token") or TOKEN).strip()
MAX_BYTES = 200 * 1024 * 1024
SLICE_TIMEOUT = int(os.environ.get("PANDY_SLICER_TIMEOUT", "900"))
LOCK = threading.Lock()


def version():
    try:
        out = subprocess.run([BAMBU, "--help"], capture_output=True, text=True, timeout=60).stdout
        m = re.search(r"BambuStudio[- ]?(\d+\.\d+\.\d+\.\d+)", out)
        return m.group(1) if m else "unknown"
    except Exception as e:  # noqa: BLE001 - reported to the caller
        return f"unavailable: {e}"


PROFILES = os.path.join(os.path.dirname(BAMBU), "resources", "profiles", "BBL", "machine")
GCODE_KEYS = ("machine_start_gcode", "machine_end_gcode", "change_filament_gcode", "layer_change_gcode", "time_lapse_gcode")


def system_gcodes(machine: str) -> dict:
    """The custom G-codes of a Bambu system printer preset ("Bambu Lab X1 Carbon 0.4 nozzle"), following its includes
    (newer presets keep them in separate template files, which the command line doesn't read)."""
    out = {}
    try:
        with open(os.path.join(PROFILES, f"{machine}.json")) as f:
            d = json.load(f)
        for inc in d.get("include", []):
            with open(os.path.join(PROFILES, f"{inc}.json")) as f:
                out.update({k: v for k, v in json.load(f).items() if k in GCODE_KEYS})
        out.update({k: v for k, v in d.items() if k in GCODE_KEYS and v})
    except (OSError, ValueError):
        pass
    return out


def heal(data: bytes) -> bytes:
    """Fills in a project's empty printer G-codes (start, end, filament change…) from its system printer preset. Projects
    built on the command line from stock presets miss them, and printing without start G-code skips homing, leveling
    and the purge line."""
    z = zipfile.ZipFile(io.BytesIO(data))
    try:
        cfg = json.loads(z.read("Metadata/project_settings.config"))
    except (KeyError, ValueError):
        return data
    missing = [k for k in GCODE_KEYS if len(str(cfg.get(k) or "")) < 100]
    if not missing:
        return data
    fill = system_gcodes(cfg.get("printer_settings_id", ""))
    changed = {k: fill[k] for k in missing if fill.get(k)}
    if not changed:
        return data
    print(f"Filled in {', '.join(changed)} from '{cfg.get('printer_settings_id')}'")
    cfg.update(changed)
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as out:
        for info in z.infolist():
            body = json.dumps(cfg, indent=4).encode() if info.filename == "Metadata/project_settings.config" else z.read(info.filename)
            out.writestr(info, body)
    return buf.getvalue()


def check_safe(sliced: bytes):
    """Refuses a sliced file whose plates lack Bambu's printer start sequence (homing, leveling, purge line,
    calibrations), recognised by its G29 and M1002 commands: a placeholder start G-code makes the printer go straight
    to the first layer."""
    z = zipfile.ZipFile(io.BytesIO(sliced))
    for n in z.namelist():
        if re.fullmatch(r"Metadata/plate_\d+\.gcode", n):
            g = z.read(n).decode(errors="replace")
            first = g.find("; CHANGE_LAYER")
            head = g[:first if first > 0 else len(g)]
            if not (re.search(r"^G28\b", head, re.M) and re.search(r"^(G29|M1002)\b", head, re.M)):
                raise RuntimeError("The sliced file lacks the printer's start sequence (homing, bed leveling, purge "
                                   "line), so it isn't safe to print. The profile is missing its printer settings.")


def slice_3mf(data: bytes, plate: int):
    """Slices one plate (0 = all) and returns (sliced 3mf bytes, summary dict). Raises RuntimeError with Bambu's message."""
    work = tempfile.mkdtemp(prefix="pandy-")
    try:
        src = os.path.join(work, "model.3mf")
        out_dir = os.path.join(work, "out")
        os.makedirs(out_dir)
        with open(src, "wb") as f:
            f.write(heal(data))
        out = os.path.join(out_dir, "sliced.gcode.3mf")
        # --export-3mf takes a bare name: Bambu Studio puts it in --outputdir.
        cmd = [BAMBU, "--slice", str(plate), "--export-3mf", "sliced.gcode.3mf", "--outputdir", out_dir, "--uptodate", src]
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=SLICE_TIMEOUT, cwd=work)
        result = {}
        rj = os.path.join(out_dir, "result.json")
        if os.path.exists(rj):
            with open(rj) as f:
                result = json.load(f)
        if not os.path.exists(out) or result.get("return_code", p.returncode) not in (0, None):
            msg = result.get("error_string") or (p.stderr or p.stdout or "").strip().splitlines()[-5:]
            raise RuntimeError(f"Bambu Studio couldn't slice it: {msg}")
        with open(out, "rb") as f:
            sliced = f.read()
        sliced = keep_pictures(data, sliced)
        check_safe(sliced)
        return sliced, summary(sliced, result)
    finally:
        shutil.rmtree(work, ignore_errors=True)


def keep_pictures(source: bytes, sliced: bytes) -> bytes:
    """Bambu Studio's command line can't draw plate pictures without a real screen (its OpenGL setup needs one), so the
    pictures saved in the project (MakerWorld projects have them) are copied into the sliced file when it has none."""
    src = zipfile.ZipFile(io.BytesIO(source))
    out = zipfile.ZipFile(io.BytesIO(sliced))
    have = set(out.namelist())
    pics = [n for n in src.namelist() if re.fullmatch(r"Metadata/(plate|plate_no_light|top|pick)_\d+(_small)?\.png", n) and n not in have]
    if not pics:
        return sliced
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        for info in out.infolist():
            z.writestr(info, out.read(info.filename))
        for n in pics:
            z.writestr(n, src.read(n))
    return buf.getvalue()


def summary(sliced: bytes, result: dict):
    """Plates, time and filament from the sliced file's slice_info.config."""
    plates = []
    try:
        with zipfile.ZipFile(io.BytesIO(sliced)) as z:
            xml = z.read("Metadata/slice_info.config").decode()
            for p in xml.split("<plate>")[1:]:
                meta = dict(re.findall(r'<metadata key="([^"]+)" value="([^"]*)"', p))
                fil = [dict(re.findall(r'(\w+)="([^"]*)"', m)) for m in re.findall(r"<filament ([^>]*)/?>", p)]
                plates.append({"index": int(meta.get("index", 1)), "seconds": int(float(meta.get("prediction", 0) or 0)),
                               "grams": float(meta.get("weight", 0) or 0), "printer": meta.get("printer_model_id", ""),
                               "nozzle": meta.get("nozzle_diameters", ""), "filaments": fil,
                               "has_picture": f"Metadata/plate_{meta.get('index', 1)}.png" in z.namelist()})
    except Exception:  # noqa: BLE001 - the summary is a nicety
        pass
    return {"plates": plates, "bambu": {k: result.get(k) for k in ("return_code", "error_string") if k in result}}


class ImplicitFtps(ftplib.FTP_TLS):
    """FTPS on port 990 with TLS from the first byte, data connections resuming the control session (the printer insists)."""

    @property
    def sock(self):
        return self._sock

    @sock.setter
    def sock(self, value):
        if value is not None and not isinstance(value, ssl.SSLSocket):
            value = self.context.wrap_socket(value)
        self._sock = value

    def ntransfercmd(self, cmd, rest=None):
        conn, size = ftplib.FTP.ntransfercmd(self, cmd, rest)
        if self._prot_p:
            conn = self.context.wrap_socket(conn, server_hostname=self.host, session=self.sock.session)
        return conn, size


def printer_context(serial: str):
    """Checks the printer's certificate against Bambu's CAs (shipped next to this file) and its serial."""
    ctx = ssl.create_default_context()
    for c in glob.glob(os.path.join(os.path.dirname(os.path.abspath(__file__)), "certs", "*.cert")):
        ctx.load_verify_locations(c)
    ctx.check_hostname = False
    ctx.verify_flags &= ~ssl.VERIFY_X509_STRICT
    return ctx


def upload(ip: str, serial: str, code: str, name: str, data: bytes) -> str:
    ftp = ImplicitFtps(context=printer_context(serial))
    ftp.connect(ip, 990, timeout=20)
    cn = dict(x[0] for x in ftp.sock.getpeercert()["subject"]).get("commonName", "")
    if cn.upper() != serial.upper():
        raise RuntimeError(f"That printer's certificate is for {cn}, not {serial}")
    ftp.login("bblp", code)
    ftp.prot_p()
    ftp.storbinary(f"STOR /cache/{name}", io.BytesIO(data))
    ftp.quit()
    return f"cache/{name}"


def safe_name(name: str) -> str:
    name = re.sub(r"[^\w .()+-]", "_", os.path.basename(name or "model")).strip() or "model"
    name = re.sub(r"(\.gcode)?\.3mf$", "", name, flags=re.I)
    return f"{name[:80]}.gcode.3mf"


class Handler(BaseHTTPRequestHandler):
    server_version = "PandySlicer/1"

    def _send(self, code, body: bytes, ctype="application/json", headers=None):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def _json(self, code, obj):
        self._send(code, json.dumps(obj).encode())

    def _authorized(self):
        if not TOKEN:
            return True
        if self.headers.get("Authorization", "") == f"Bearer {TOKEN}":
            return True
        self._json(401, {"error": "Wrong or missing token"})
        return False

    def do_GET(self):
        if urlparse(self.path).path == "/health":
            if self._authorized():
                self._json(200, {"ok": True, "version": VERSION})
        else:
            self._json(404, {"error": "Not found"})

    def do_POST(self):
        url = urlparse(self.path)
        if url.path != "/slice":
            return self._json(404, {"error": "Not found"})
        if not self._authorized():
            return
        q = {k: v[0] for k, v in parse_qs(url.query).items()}
        length = int(self.headers.get("Content-Length", "0"))
        if not 0 < length <= MAX_BYTES:
            return self._json(400, {"error": "Send the .3mf as the request body (up to 200 MB)"})
        data = self.rfile.read(length)
        if not data.startswith(b"PK"):
            return self._json(400, {"error": "That isn't a 3MF file"})
        try:
            plate = int(q.get("plate", "1"))
        except ValueError:
            return self._json(400, {"error": "plate must be a number"})
        if not LOCK.acquire(timeout=SLICE_TIMEOUT):
            return self._json(503, {"error": "Busy slicing something else"})
        try:
            sliced, info = slice_3mf(data, plate)
        except subprocess.TimeoutExpired:
            return self._json(504, {"error": "Slicing took too long"})
        except RuntimeError as e:
            return self._json(422, {"error": str(e)})
        finally:
            LOCK.release()
        name = safe_name(q.get("name", "model"))
        if q.get("upload") == "1":
            ip, serial, code = (self.headers.get(h, "").strip() for h in ("X-Printer-Ip", "X-Printer-Serial", "X-Printer-Code"))
            if not (ip and serial and code):
                return self._json(400, {"error": "Uploading needs X-Printer-Ip, X-Printer-Serial and X-Printer-Code"})
            try:
                path = upload(ip, serial, code, name, sliced)
            except Exception as e:  # noqa: BLE001 - reported to the app
                return self._json(502, {"error": f"Couldn't put the file on the printer: {e}"})
            return self._json(200, {"path": path, "name": name, "size": len(sliced), **info})
        self._send(200, sliced, "application/octet-stream",
                   {"X-Pandy-Result": json.dumps(info), "Content-Disposition": f'attachment; filename="{name}"'})

    def log_message(self, fmt, *args):
        print(f"{self.address_string()} {fmt % args}")


if __name__ == "__main__":
    VERSION = version()
    print(f"Pandy Slicer on port {PORT}, Bambu Studio {VERSION}, token {'on' if TOKEN else 'off'}")
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
