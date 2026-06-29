#!/usr/bin/env python3
"""
trigger_build.py
Triggers the GitHub Actions build workflow for PhantomBrowser.
Run AFTER push_to_github.py has completed.
"""

import urllib.request
import json

TOKEN = input("Enter your GitHub token: ").strip()
REPO  = "segz7448/Server"

# Trigger the workflow
req = urllib.request.Request(
    f"https://api.github.com/repos/{REPO}/actions/workflows/build.yml/dispatches",
    data=json.dumps({"ref": "main"}).encode(),
    headers={
        "Authorization": f"token {TOKEN}",
        "Accept": "application/vnd.github.v3+json",
        "Content-Type": "application/json",
    },
    method="POST",
)

try:
    with urllib.request.urlopen(req) as r:
        print(f"✓ Build triggered! Status: {r.status}")
        print(f"  View at: https://github.com/{REPO}/actions")
except urllib.error.HTTPError as e:
    print(f"✗ Error {e.code}: {e.read().decode()}")
