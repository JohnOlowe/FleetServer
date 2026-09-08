#!/usr/bin/env python3
"""Pull the useful part out of a Gradle console log.

Used by .github/workflows/android.yml so that a failed build reports a compact,
readable error block (as a check run) instead of pages of stack traces.

Usage: python3 build-tools/extract_errors.py gradle-build.log
"""
import re
import sys

MAX_CHARS = 40000

# Lines that mark the beginning of something worth reading.
PATTERNS = (
    re.compile(r"^e: "),                                   # kotlin
    re.compile(r"(?m)^e: "),
    re.compile(r"\.java:\d+: error:"),
    re.compile(r"\.kts:\d+:\d+: "),
    re.compile(r"^w: "),
    re.compile(r"error:"),
    re.compile(r"^FAILED$"),
    re.compile(r"^FAILURE: "),
    re.compile(r"^What went wrong:"),
    re.compile(r"^> "),
    re.compile(r"^Execution failed"),
    re.compile(r"^Could not "),
    re.compile(r"^Caused by: "),
    re.compile(r"^AAPT: "),
    re.compile(r"^Android resource linking failed"),
    re.compile(r"^\s+at org\.gradle"),
    re.compile(r"Unresolved reference"),
    re.compile(r"cannot find symbol|incompatible types|is not abstract|"
               r"does not override|method does not override|no suitable method|"
               r"cannot be applied|non-static|not a statement|illegal start of"),
)


def interesting(line):
    return any(pattern.search(line) for pattern in PATTERNS)


def main():
    if len(sys.argv) < 2:
        print("usage: extract_errors.py <gradle-log>")
        return 1
    try:
        with open(sys.argv[1], errors="replace") as handle:
            lines = handle.read().splitlines()
    except OSError as error:
        print("could not read log: %s" % error)
        return 1

    kept = []
    stack_lines = 0
    for index, line in enumerate(lines):
        stripped = line.rstrip()
        if not stripped:
            continue
        if re.match(r"^\s+at org\.gradle", stripped):
            stack_lines += 1
            if stack_lines > 12:
                continue
        if interesting(stripped):
            kept.append(stripped)
            # include the source line and caret javac prints under an error
            for offset in (1, 2, 3):
                if index + offset < len(lines):
                    following = lines[index + offset].rstrip()
                    if following.strip().startswith("^") or following.strip().startswith("symbol:")
                        kept.append(following)

    if not kept:
        kept = lines[-120:]

    out = []
    total = 0
    for line in kept:
        if total + len(line) + 1 > MAX_CHARS:
            out.append("... (truncated)")
            break
        out.append(line)
        total += len(line) + 1
    print("\n".join(out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
