#!/usr/bin/env python3
"""Cross-checks what the documents claim against what the code does.

Protocol documents rot quietly: a message id renamed in the code keeps being
quoted in a design note for months. This reads both sides and reports the claims
that the code cannot back up - a message id, a service name or a port that
appears in docs/ but nowhere in the sources.

The paths a document points at are checked as well: a script that moves leaves
every note that names it pointing at nothing. Paths that describe a phone, an
external download or the output of a run are exempt on purpose, see
EXTERNAL_INPUTS and OUTPUT_DIRS.

The other direction (a constant the docs never mention) is not reported: plenty
of constants do not deserve a paragraph.

Usage:
    audit-docs.py
    audit-docs.py --selfcheck
"""

import argparse
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DOCS = os.path.join(ROOT, 'docs')
SOURCES = os.path.join(ROOT, 'android', 'iccoa-sink', 'java')
MESSAGE_ID = re.compile(r'0[xX][0-9A-Fa-f]{4}\b')
SERVICE_NAME = re.compile(r'_[a-z0-9-]+\._(?:tcp|udp)(?:\.local)?')
PORT = re.compile(r'\b(?:7000|710[0-9]|5353|5000)\b')
# only extensions that name a file; a bare dotted name like com.oplus.ocar is a
# package, not a path, and is not matched
PATH = re.compile(r'`([A-Za-z0-9_./-]+\.(?:apk|java|json|kt|md|ps1|py|rs|sh|toml|txt|xml))`')


def read_tree(folder, suffixes):
    text = ''
    for base, _, files in os.walk(folder):
        for name in files:
            if name.endswith(suffixes):
                with open(os.path.join(base, name), 'r', encoding='utf-8',
                          errors='replace') as handle:
                    text += handle.read()
    return text


# a value on a line like this is something that was observed on the wire, not
# something this code claims to implement
OBSERVATION = ['vid=', 'pid=', 'windex', 'wvalue', 'brequest', 'bmrequesttype',
               'rva', 'pcap', 'dump', '抓包', '捕获']
# placeholders in examples, never meant to be real names
PLACEHOLDER = ['xxx', 'abcd', 'example']
# files the work needs but that never live in this repository: the samples the
# user supplied, or a download described by hash in a benchmark note
EXTERNAL_INPUTS = ['base.apk', 'diplay-0.2.16.apk']
# the output of a run, written by a command the document itself tells you to run
OUTPUT_DIRS = ['reports']


def worth_checking(line):
    lowered = line.lower()
    return not any(marker in lowered for marker in OBSERVATION)


def claims(text):
    """The protocol-shaped things a text mentions, normalised.

    Only the three shapes that are cheap to be wrong about are compared: message
    ids, mDNS service names and the ports this bridge uses. Prose numbers (how
    many seconds a timer waits) are not checked: matching "every two seconds"
    against a constant needs a reader, not a regex.
    """
    found = {'message ids': set(), 'service names': set(), 'ports': set()}
    for line in text.splitlines():
        if not worth_checking(line):
            continue
        for match in MESSAGE_ID.finditer(line):
            found['message ids'].add(match.group(0).lower())
        for match in SERVICE_NAME.finditer(line):
            name = match.group(0).lower()
            if not any(placeholder in name for placeholder in PLACEHOLDER):
                found['service names'].add(name)
        for match in PORT.finditer(line):
            found['ports'].add(match.group(0))
    return found


def index_repo():
    """Every file in the tree, as a lower-cased path relative to the root.

    A path counts as present when some file in the tree ends with it, or - for a
    bare name, or a path written with an ellipsis ('java/.../Iap2.java') - when
    some file has the same name. That still catches the rot this check exists
    for: a renamed, moved or deleted file stops matching.
    """
    paths = set()
    for base, folders, files in os.walk(ROOT):
        folders[:] = [f for f in folders if f != '.git']
        for name in files:
            full = os.path.join(base, name)
            paths.add(os.path.relpath(full, ROOT).replace(os.sep, '/').lower())
    return paths


def missing_paths(doc_text, label, repo):
    """Files the document points at that are not there.

    Easy to get wrong and unambiguous to check. A phone path, a download that is
    not in the tree, or the output of a run the document tells you to run are
    all skipped - none of those are supposed to be checked in.
    """
    findings = 0
    for match in PATH.finditer(doc_text):
        candidate = match.group(1)
        lowered = candidate.lower()
        if lowered.startswith('http') or '*' in candidate:
            continue
        if candidate.startswith('/'):  # a path on the phone, not in here
            continue
        if lowered.split('/')[0] in OUTPUT_DIRS:
            continue
        if os.path.basename(lowered) in EXTERNAL_INPUTS:
            continue
        if lowered in repo or any(entry.endswith('/' + lowered) for entry in repo):
            continue
        # a document may elide the middle ('java/.../Iap2.java') or name a file
        # by its bare name; both fall back to matching the file name alone
        if '...' in candidate or '/' not in candidate:
            if any(os.path.basename(entry) == os.path.basename(lowered) for entry in repo):
                continue
        print('%s: points at %s, which does not exist' % (label, candidate))
        findings += 1
    return findings


def compare(doc_text, code_text, label):
    findings = 0
    doc = claims(doc_text)
    code = claims(code_text)
    for kind in sorted(doc):
        unbacked = sorted(doc[kind] - code[kind])
        if unbacked:
            print('%s: the code does not define these %s: %s'
                  % (label, kind, ', '.join(unbacked)))
            findings += len(unbacked)
    return findings


def selfcheck():
    """Plants a claim and a path the code cannot back, insists both are caught."""
    probe = os.path.join(DOCS, '_audit-probe.md')
    body = ('the car sends 0xDEAD and browses _nonsense._tcp on port 7109, and '
            '`scripts/definitely-not-here.py` drives all of it')
    with open(probe, 'w', encoding='utf-8') as handle:
        handle.write(body)
    try:
        doc_text = open(probe, 'r', encoding='utf-8').read()
        code_text = read_tree(SOURCES, '.java')
        findings = compare(doc_text, code_text, 'probe')
        findings += missing_paths(doc_text, 'probe', index_repo())
    finally:
        os.remove(probe)
    if findings != 4:
        print('the audit missed its own planted claims (%d of 4)' % findings)
        return 1
    print('the audit caught the three planted claims and the planted path')
    return 0


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--selfcheck', action='store_true')
    args = parser.parse_args()
    if args.selfcheck:
        return selfcheck()

    code_text = read_tree(SOURCES, '.java')
    repo = index_repo()
    total = 0
    for name in sorted(os.listdir(DOCS)):
        if not name.endswith('.md') or name.startswith('_'):
            continue
        with open(os.path.join(DOCS, name), 'r', encoding='utf-8', errors='replace') as handle:
            doc_text = handle.read()
        found = compare(doc_text, code_text, name)
        found += missing_paths(doc_text, name, repo)
        total += found
    print('---')
    print('%d claim(s) the code does not back' % total)
    return 1 if total else 0


if __name__ == '__main__':
    sys.exit(main())
