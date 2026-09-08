#!/usr/bin/env python3
"""Wrap extracted build errors into a GitHub check-run payload.

Usage: make_check_payload.py <errors-file> <head_sha> <ref_name> > payload.json

The result is posted with:
    gh api repos/<owner>/<repo>/check-runs --input payload.json
"""
import json
import sys

MAX_CHARS = 60000


def main():
    if len(sys.argv) < 4:
        print("usage: make_check_payload.py <errors-file> <head_sha> <ref_name>", file=sys.stderr)
        return 1
    errors_file, head_sha, ref_name = sys.argv[1], sys.argv[2], sys.argv[3]
    try:
        with open(errors_file, errors="replace") as handle:
            text = handle.read()
    except OSError as error:
        text = "could not read build errors: %s" % error
    if len(text) > MAX_CHARS:
        text = text[:MAX_CHARS] + "\n... (truncated)\n"

    payload = {
        "name": "Build errors",
        "head_sha": head_sha,
        "status": "completed",
        "conclusion": "failure",
        "output": {
            "title": "Gradle build failed on %s" % ref_name,
            "summary": "Extracted compiler output from `./gradlew assembleDebug`.",
            "text": text,
        },
    }
    json.dump(payload, sys.stdout)
    return 0


if __name__ == "__main__":
    sys.exit(main())
