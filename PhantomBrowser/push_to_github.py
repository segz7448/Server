#!/usr/bin/env python3
"""
push_to_github.py
Pushes all PhantomBrowser project files to GitHub via the API.
Run: python3 push_to_github.py
"""

import urllib.request
import json
import base64
import os
import time
import sys

TOKEN = input("Enter your GitHub token: ").strip()
REPO  = "segz7448/Server"
BASE  = os.path.dirname(os.path.abspath(__file__))

HEADERS = {
    "Authorization": f"token {TOKEN}",
    "Accept": "application/vnd.github.v3+json",
    "Content-Type": "application/json",
}

def api(path, method="GET", data=None):
    url = f"https://api.github.com/repos/{REPO}/contents/{path}"
    body = json.dumps(data).encode() if data else None
    req = urllib.request.Request(url, data=body, headers=HEADERS, method=method)
    try:
        with urllib.request.urlopen(req) as r:
            return json.load(r), None
    except urllib.error.HTTPError as e:
        return None, e.read().decode()

def get_sha(path):
    d, _ = api(path)
    return d["sha"] if d and "sha" in d else None

def push_file(rel_path, content_bytes):
    encoded = base64.b64encode(content_bytes).decode()
    sha = get_sha(rel_path)
    payload = {
        "message": f"Add/update {rel_path}",
        "content": encoded,
    }
    if sha:
        payload["sha"] = sha
    _, err = api(rel_path, method="PUT", data=payload)
    if err:
        print(f"  ✗ {rel_path}: {err[:120]}")
    else:
        print(f"  ✓ {rel_path}")
    time.sleep(0.3)  # avoid secondary rate limit

SKIP_DIRS  = {".git", "node_modules", "__pycache__", ".gradle", "build"}
SKIP_EXTS  = {".pyc", ".class"}

def walk_and_push(local_dir, remote_prefix=""):
    for root, dirs, files in os.walk(local_dir):
        # Prune unwanted dirs
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        for fname in files:
            if any(fname.endswith(e) for e in SKIP_EXTS):
                continue
            local_path = os.path.join(root, fname)
            rel = os.path.relpath(local_path, local_dir).replace("\\", "/")
            remote_path = f"{remote_prefix}/{rel}" if remote_prefix else rel
            with open(local_path, "rb") as f:
                push_file(remote_path, f.read())

if __name__ == "__main__":
    print(f"\nPushing PhantomBrowser to {REPO}...\n")
    walk_and_push(BASE, "PhantomBrowser")
    print("\nDone. Now trigger the build:")
    print(f"  python3 trigger_build.py  (or go to Actions tab on GitHub)")
