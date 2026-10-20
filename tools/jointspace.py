#!/usr/bin/env python3
"""Philips JointSpace API v6 client (PC side) for verifying Ambilight endpoints.

Pairing protocol copied from eslavnov/pylips. Needs only `requests`
(install into tools/venv: pip install "requests>=2.31").

    export TV_HOST=192.168.1.27          (PowerShell: $env:TV_HOST="192.168.1.27")
    python tools/jointspace.py --pair
    python tools/jointspace.py --get ambilight/power
    python tools/jointspace.py --post ambilight/power '{"power":"On"}'
    python tools/jointspace.py --ambilight-check

Credentials: ~/.cleanyourtv/jointspace-<host>.json (password never printed in full).
Optional env TV_SECRET overrides the HMAC secret (base64).
"""
import argparse
import base64
import hashlib
import hmac
import json
import os
import random
import string
import sys

import requests
import urllib3
from requests.auth import HTTPDigestAuth

urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)

# Secret exactly as hard-coded in pylips.py (base64).
SECRET = ("JCqdN5AcnAHgJYseUn7ER5k3qgtemfUvMRghQpTfTZq7Cvv8EPQPqfz6dDxPQPSu4gKFPWkJGw32zyASgJkHwCjU")
TIMEOUT = 5
CHECK_PATHS = ["system", "ambilight/power", "ambilight/mode",
               "ambilight/currentconfiguration", "ambilight/topology",
               "ambilight/supportedstyles", "ambilight/cached"]


class TvError(Exception):
    pass


def host():
    h = os.environ.get("TV_HOST")
    if not h:
        sys.exit("TV_HOST env var is required (e.g. TV_HOST=192.168.1.27)")
    return h


def base_url(h):
    return "https://{0}:1926/6/".format(h)


def cred_path(h):
    return os.path.join(os.path.expanduser("~"), ".cleanyourtv", "jointspace-{0}.json".format(h))


def load_creds(h):
    try:
        with open(cred_path(h)) as f:
            d = json.load(f)
        return d["user"], d["password"]
    except (OSError, ValueError, KeyError):
        return None


def save_creds(h, user, password):
    p = cred_path(h)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, "w") as f:
        json.dump({"user": user, "password": password}, f)
    try:
        os.chmod(p, 0o600)
    except OSError:
        pass


def mask(s):
    return s[:3] + "***" if s else "(none)"


def request(method, h, path, auth=None, body=None):
    url = base_url(h) + path.lstrip("/")
    try:
        r = requests.request(method, url, json=body, auth=auth, verify=False, timeout=TIMEOUT)
    except requests.exceptions.ConnectTimeout:
        raise TvError("timeout connecting to " + url)
    except requests.exceptions.ReadTimeout:
        raise TvError("timeout waiting for " + url)
    except requests.exceptions.ConnectionError as e:
        raise TvError("connection failed (refused/unreachable): {0}".format(e))
    if r.status_code == 401:
        raise TvError("401: not paired or credentials rejected; run --pair")
    return r


def make_auth(h):
    c = load_creds(h)
    return HTTPDigestAuth(c[0], c[1]) if c else None


def pretty(r, limit=None):
    try:
        return json.dumps(r.json(), indent=2)
    except ValueError:
        return r.text[:limit] if limit else r.text


def device_spec(device_id):
    return {"device_name": "heliotrope", "device_os": "Android", "app_name": "Pylips",
            "type": "native", "app_id": "app.id", "id": device_id}


def signature(timestamp, pin):
    key = base64.b64decode(os.environ.get("TV_SECRET", SECRET))
    msg = (str(timestamp) + str(pin)).encode()
    hexdigest = hmac.new(key, msg, hashlib.sha1).hexdigest()
    return base64.b64encode(hexdigest.encode()).decode()


def pair(h):
    alphabet = string.ascii_letters + string.digits
    user = "".join(random.SystemRandom().choice(alphabet) for _ in range(16))
    req = {"scope": ["read", "write", "control"], "device": device_spec(user)}
    r = request("POST", h, "pair/request", body=req)
    resp = r.json()
    if resp.get("error_id") != "SUCCESS":
        raise TvError("pair/request failed: {0}".format(resp))
    password, ts = resp["auth_key"], resp["timestamp"]
    pin = input("Enter PIN shown on TV: ").strip()
    grant = {"auth": {"auth_AppId": "1", "pin": pin, "auth_timestamp": ts,
                      "auth_signature": signature(ts, pin)},
             "device": device_spec(user)}
    r = request("POST", h, "pair/grant", auth=HTTPDigestAuth(user, password), body=grant)
    print("grant status", r.status_code, pretty(r, 200))
    if r.status_code != 200:
        raise TvError("pair/grant failed")
    save_creds(h, user, password)
    print("paired (user={0}, password={1}) saved to {2}".format(user, mask(password), cred_path(h)))


def ambilight_check(h):
    auth = make_auth(h)
    for p in CHECK_PATHS:
        try:
            r = request("GET", h, p, auth=None if p == "system" else auth)
            print("== {0}  HTTP {1}\n{2}\n".format(p, r.status_code, pretty(r, 200)))
        except TvError as e:
            print("== {0}  ERROR {1}\n".format(p, e))


def main():
    ap = argparse.ArgumentParser(description="JointSpace v6 client")
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--pair", action="store_true")
    g.add_argument("--get", metavar="PATH")
    g.add_argument("--post", nargs=2, metavar=("PATH", "JSON"))
    g.add_argument("--ambilight-check", action="store_true")
    a = ap.parse_args()
    h = host()
    try:
        if a.pair:
            pair(h)
        elif a.get:
            r = request("GET", h, a.get, auth=make_auth(h))
            print("HTTP", r.status_code)
            print(pretty(r))
        elif a.post:
            r = request("POST", h, a.post[0], auth=make_auth(h), body=json.loads(a.post[1]))
            print("HTTP", r.status_code)
            print(pretty(r))
        else:
            ambilight_check(h)
    except TvError as e:
        sys.exit("error: {0}".format(e))
    except ValueError as e:
        sys.exit("error: bad JSON: {0}".format(e))


if __name__ == "__main__":
    main()
