#!/usr/bin/env python3
"""Looks for checks that cannot fail, or cannot say anything new.

A suite that is green proves only that its checks ran. This reads the checks
themselves and reports the shapes that make a green run mean less:

* a condition that is true by construction;
* a condition that only says a count is not negative, which no count can be;
* the same check name twice in one suite;
* a suite whose checks are all positive: for a protocol whose job is mostly to
  refuse things, a suite with no negation is worth a second look.

Usage:
    audit-assertions.py
    audit-assertions.py --verbose
"""

import argparse
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TESTS = os.path.join(ROOT, 'android', 'iccoa-sink', 'java')

BACKSLASH = chr(92)
SINGLE = chr(39)
DOUBLE = chr(34)

CHECK = re.compile(r'\bcheck\(\s*("(?:[^"\\]|\\.)*")\s*,', re.S)

TAUTOLOGIES = [
    (re.compile(r'\|\|\s*true\b'), 'ends in a tautology'),
    (re.compile(r'\btrue\s*\|\|'), 'starts with a tautology'),
    (re.compile(r'!\s*false\b'), 'negates false'),
    (re.compile(r'\b(\d+)\s*==\s*\1\b'), 'compares a literal with itself'),
]

VACUOUS_COUNT = re.compile(
    r'\b(\w*(?:count|size|length|frames|replies|queries|announced|sent|answered)\w*)'
    r'\s*>=\s*0\b')


def condition_of(text, start):
    """The condition of the check starting at start: its second argument.

    The first argument is the name, so the condition is what runs from after the
    first comma at this depth to the next one (or to the closing parenthesis).
    Reading the first argument here is what made this audit report a clean tree
    while its own planted defects went unnoticed.
    """
    index = text.index('(', start)
    depth = 0
    commas = 0
    begin = index + 1
    quote = ''
    while index < len(text):
        char = text[index]
        if quote:
            if char == quote and text[index - 1] != BACKSLASH:
                quote = ''
        elif char == DOUBLE or char == SINGLE:
            quote = char
        elif char in '([{':
            depth += 1
        elif char in ')]}':
            depth -= 1
            if depth == 0:
                return text[begin:index] if commas >= 1 else ''
        elif char == ',' and depth == 1:
            commas += 1
            if commas == 1:
                begin = index + 1
            else:
                return text[begin:index]
        index += 1
    return ''


PROBE = os.path.join(TESTS, 'SelfTestAuditProbe.java')

PROBE_SOURCE = '''class SelfTestAuditProbe {
    void run() {
        check("a tautology", 1 == 1 || true);
        check("a count", size >= 0);
        check("the same thing twice", x > 0);
        check("the same thing twice", y > 0);
    }
}
'''


def selfcheck():
    """Plants a suite with known defects and insists the audit reports them.

    A clean report from a tool nobody has tested is worth nothing - this one
    read the wrong argument for a while and reported a spotless tree - so the
    scan is run over a file whose problems are known.
    """
    import subprocess
    with open(PROBE, 'w', encoding='utf-8') as handle:
        handle.write(PROBE_SOURCE)
    try:
        output = subprocess.run([sys.executable, os.path.abspath(__file__)],
                                capture_output=True, text=True).stdout
    finally:
        os.remove(PROBE)
    wanted = ['ends in a tautology', 'compares a literal with itself',
              'not negative', 'more than once', 'none of them negative']
    missing = [text for text in wanted if text not in output]
    if missing:
        print('the audit missed its own planted defects: %s' % ', '.join(missing))
        return 1
    print('the audit caught every planted defect')
    return 0


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--verbose', action='store_true')
    parser.add_argument('--selfcheck', action='store_true')
    args = parser.parse_args()
    if args.selfcheck:
        return selfcheck()

    findings = 0
    suites = 0
    checks = 0
    for folder, _, files in os.walk(TESTS):
        for name in sorted(files):
            if not name.startswith('SelfTest') or not name.endswith('.java'):
                continue
            suites += 1
            path = os.path.join(folder, name)
            with open(path, 'r', encoding='utf-8', errors='replace') as handle:
                text = handle.read()
            names = []
            negatives = 0
            for match in CHECK.finditer(text):
                checks += 1
                check_name = match.group(1)
                condition = condition_of(text, match.start())
                names.append(check_name)
                # a check counts as negative when it expects something to be
                # refused, ignored or absent, however it is worded
                lowered = check_name.lower()
                if ('!' in condition or 'null' in condition
                        or any(word in lowered for word in (
                            'reject', 'refus', 'fail', 'ignore', 'never', 'without',
                            'no ', 'not ', 'unhandled', 'unusable', 'unsupported',
                            'missing', 'wrong', 'absent', 'only '))):
                    negatives += 1
                    negatives += 1
                for pattern, why in TAUTOLOGIES:
                    if pattern.search(condition):
                        print('%s: %s %s' % (name, check_name, why))
                        findings += 1
                if VACUOUS_COUNT.search(condition):
                    print('%s: %s only says a count is not negative' % (name, check_name))
                    findings += 1
                if args.verbose and condition.strip() == 'true':
                    print('%s: %s asserts true' % (name, check_name))
                    findings += 1
            seen = set()
            for check_name in names:
                if check_name in seen:
                    print('%s: the check %s appears more than once' % (name, check_name))
                    findings += 1
                seen.add(check_name)
            if names and negatives == 0:
                print('%s: %d checks, none of them negative' % (name, len(names)))
                findings += 1

    print('---')
    print('%d checks over %d suites, %d finding(s)' % (checks, suites, findings))
    return 1 if findings else 0


if __name__ == '__main__':
    sys.exit(main())
