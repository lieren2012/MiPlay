#!/usr/bin/env python3
"""Lists log lines in the app that no test run has ever printed.

A branch that never runs is a branch nobody has verified, and the log lines are
the cheapest proxy for it: every step of the pipeline says something. Lines that
only exist on paths a desktop test cannot reach (Wi-Fi, bluetooth, MediaProjection,
accessibility) are expected here; the ones that are not are the interesting ones -
a condition that can never be true reads exactly like this.

Usage:
    audit-log-coverage.py --corpus reports/selftest-all.log
    audit-log-coverage.py --corpus a.txt --corpus b.txt --app android/iccoa-sink/java

The corpus has to be a real capture with the suites' output in it, because the
whole check is a substring search: an empty or truncated capture reports every
line as untested. Such a capture is refused (exit 2) rather than reported.
"""

import argparse
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_APP = os.path.join(ROOT, 'android', 'iccoa-sink', 'java')
# every suite prints this before the app log it captured
SUITE_MARKER = '---- '

# Logs.add("..."), log("..."), listener.onLog("..."), and the same with a prefix
CALL = re.compile(r'(?:Logs\.add|\blog|onLog)\(\s*"([^"\\]{8,})"')

# paths a desktop test cannot reach: they need a radio, a display or a service
ANDROID_ONLY = [
    'SinkService.java', 'MainActivity.java', 'ScreenSource.java', 'ProjectedTouchService.java',
    'CarplayBluetooth.java', 'CarplayBluetoothReceiver.java', 'CarWifi.java', 'H264Encoder.java',
    'H264Decoder.java', 'UibcChannel.java', 'RtspServer.java', 'RtpReceiver.java',
    'WfdSession.java', 'AirplayBootstrap.java', 'CarplayAdvertiser.java',
    'CarplayControlListener.java', 'TouchInjector.java', 'SurfaceEncoder.java',
]


def literals(app_dir):
    """Every log literal in the app, with the file it lives in."""
    found = {}
    for folder, _, files in os.walk(app_dir):
        for name in files:
            if not name.endswith('.java') or name.startswith('SelfTest'):
                continue
            path = os.path.join(folder, name)
            with open(path, 'r', encoding='utf-8', errors='replace') as handle:
                text = handle.read()
            for match in CALL.finditer(text):
                literal = match.group(1)
                found.setdefault(literal, set()).add(name)
    return found


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--corpus', action='append', required=True)
    parser.add_argument('--app', default=DEFAULT_APP)
    parser.add_argument('--all', action='store_true',
                        help='also list the lines on paths only a phone can reach')
    args = parser.parse_args()

    corpus = ''
    for path in args.corpus:
        with open(path, 'r', encoding='utf-8', errors='replace') as handle:
            corpus += handle.read()

    # A capture that never reached the suites - an empty file, or a run-selftests
    # log that only kept the summary - makes every single line look untested.
    # That reads like the worst possible result instead of a broken input, so it
    # is refused instead of reported.
    if not any(line.startswith(SUITE_MARKER) for line in corpus.splitlines()):
        print('this corpus carries no suite output: expected lines like')
        print('"---- app log ----". Run scripts\\run-selftests.ps1 first; it writes')
        print('reports\\selftest-all.log with every suite and its app log.')
        return 2

    found = literals(args.app)
    missing = {literal: files for literal, files in found.items() if literal not in corpus}

    reachable = {}
    for literal, files in missing.items():
        if not any(file in ANDROID_ONLY for file in files):
            reachable[literal] = files

    print('%d log lines in the app, %d never printed by these runs' % (len(found), len(missing)))
    print('%d of those are on paths a desktop test cannot reach' % (len(missing) - len(reachable)))
    if args.all:
        for literal in sorted(missing):
            print('  (phone) %s' % literal)
    if not reachable:
        print('nothing left that a test could have reached')
        return 0
    print('reached from testable code but never printed:')
    for literal in sorted(reachable):
        print('  %s\n     in %s' % (literal, ', '.join(sorted(reachable[literal]))))
    return 1


if __name__ == '__main__':
    sys.exit(main())
