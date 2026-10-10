#!/usr/bin/env python3
"""Reads a run's log and says how far the bridge got.

The app writes one line per step, so a run can be diagnosed without a debugger:
this prints the steps it saw, the first one it did not, and every line that means
something is degraded.

Two guards against the checklist drifting away from the code:

* every pattern it matches is verified to exist in the Java sources
  (`--verify-patterns`), so a renamed log line is caught here rather than in a
  car;
* the steps are listed in pipeline order, so "the first missing step" is the
  answer to "where did it stop".

Usage:
    check-run-log.py --log run.txt
    check-run-log.py --logcat              # pulls from adb
    check-run-log.py --verify-patterns     # checks the patterns against the tree
"""

import argparse
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SOURCES = os.path.join(ROOT, 'android', 'iccoa-sink', 'java')

# (step, pattern, what it means)
STEPS = [
    ('service', 'service created', 'the foreground service is up'),
    ('bluetooth', 'SDP registered: CarPlay Probe', 'the CarPlay identity is published'),
    ('invited', 'inviting the car to tcp/', 'the port the advertisement names is open'),
    ('advertising', 'advertising ', 'this phone is advertising itself as a CarPlay controller'),
    ('car-found-us', 'the car connected to the invited port from',
     'the car found us and connected'),
    ('link', 'RFCOMM', 'a Bluetooth link to the car exists'),
    ('link-open', 'link open; waiting up to', 'the iAP2 link is opening'),
    ('iap2-session', 'control session', 'the iAP2 control session is negotiated'),
    ('identify', 'bootstrap -> 0x1D00 request identification', 'the phone identifies itself'),
    ('identified', 'bootstrap <- 0x1D01 the car sends', 'the car answered with its capabilities'),
    ('authenticate', 'bootstrap -> 0xAA00 request authentication', 'authentication is asked for'),
    ('certificate', 'bootstrap <- 0xAA01 certificate bytes=', 'the car presented a certificate'),
    ('challenge', 'bootstrap -> 0xAA02 challenge ', 'the challenge went out'),
    ('signed', 'bootstrap <- 0xAA03 signature valid=', 'the car proved its key'),
    ('announced', 'bootstrap announced as ', 'the phone announced itself'),
    ('wifi-offer', 'bootstrap Wi-Fi from 0x', 'the car handed over its Wi-Fi'),
    ('wifi-join', 'Wi-Fi: joined ', 'the phone joined the car network'),
    ('airplay-connect', 'AirPlay connected to ', 'the AirPlay session is connected'),
    ('auth-path', 'auth-setup unavailable (', 'the car has no MFi endpoint'),
    ('mfisap', 'MFiSAP certificate-bytes=', 'the mfi key exchange ran'),
    ('session', 'session established: ', 'SETUP /setup created the session'),
    ('info', 'GET /info fields=', 'the car reported its display'),
    ('display', "the car's display ", 'the screen the car says it has, and its frame-rate ceiling'),
    ('event-channel', 'event channel open', 'the event channel is up'),
    ('modes', 'modesChanged accepted; reply fields=', 'the modes were accepted'),
    ('screen-stream', 'streams agreed: screen type 110', 'the screen stream is agreed'),
    ('audio-formats', 'the car announced PCM formats mask=',
     'the car said which PCM formats it can decode'),
    ('audio-stream', 'audio agreed: ', 'the audio stream is agreed'),
    ('record', 'RECORD accepted; the media path can start', 'the session is running'),
    ('feedback', 'feedback keep-alive running (every', 'the session keep-alive is running'),
    ('tunnel', 'iAP tunnel armed; waiting for the car', 'the iAP tunnel is armed'),
    ('encode', 'encoding at ', 'the video size, frame rate and density were decided'),
    ('video-producer', 'video path running', 'the screen producer started'),
    ('video-config', 'video configuration sent from ', 'the decoder configuration went out'),
    ('video-frames', 'video tunnel sent ', 'frames are leaving for the car'),
    ('audio-frames', 'audio path running: ', 'audio is leaving for the car'),
    ('hid-device', 'HID device registered uuid=', 'the car described its input device'),
    ('touch', 'HID touch finger ', 'the car sent a touch report we could decode'),
    ('touch-injected', 'car touch', 'the touch was injected into the phone'),
]

# (pattern, what it means) - present means something is degraded or has stopped
WARNINGS = [
    ('auth-setup unavailable', 'no MFi endpoint: the screen falls back to the pairing key, '
                               'which the car cannot unwrap'),
    ('no MFiSAP key', 'the screen is being sent with a key the car never received'),
    ('names no display size', 'the car did not describe its screen: the encode size comes '
                             'from the phone screen and its scale'),
    ('the car announced no audio formats', 'the car did not say which PCM formats it takes:'
                                        ' the offers from here are guesses'),
    ('no audio offer was accepted', 'no sound this session'),
    ('no audio key from the handshake', 'no sound this session'),
    ('HID descriptor unsupported for', 'the car input device cannot be decoded: no touch'),
    ('is not a touch screen', 'the car sent reports for a device this side cannot '
                             'decode (key/button support is missing)'),
    ('cannot decode the HID report as finger', 'the report did not match its own descriptor: '
                                               'read the byte dump above'),
    ('no descriptor registered for', 'touch reports are arriving but cannot be decoded'),
    ('pairing did not complete', 'the car will probably refuse the session'),
    ('screen capture refused', 'the car is being sent the test pattern, not the screen'),
    ('car rejected', 'the car refused a request: read the status code on that line'),
    ('no answer to SETUP', 'the car did not answer a SETUP'),
    ('out of range port', 'the car named a port we refused'),
    ('requires mutual authentication', 'the car wants mutual auth, which this side cannot do'),
    ('the car closed the session', 'the car hung up'),
    ('session read ended', 'the session connection dropped'),
    ('cannot open the event channel', 'no car commands will arrive'),
    ('signature valid=false', 'the car failed to prove its key: the bootstrap stops here'),
    ('is taken (', 'another responder holds the mDNS port: the announcement is '
                        'sent from elsewhere and the car may ignore it'),
    ('no multicast lock', 'without the lock the announcements may be filtered'),
    ('control bytes from the car', 'the car said something on the invited port: send these '
                                   'bytes back'),
    ('no encoder is running yet; answering 501', 'the car asked for a key frame before the '
                                                 'screen producer was up'),
    ('which this side does not implement; answering 501', 'the car sent a command this side does '
                                                        'not implement: the type is on that line'),
    ('voice input, which is not implemented', 'the voice button was pressed and this side has no '
                                             'microphone path'),
    ('feedback keep-alive stopped', 'the keep-alive failed: the session is probably gone'),
    ('video send stalled for', 'the car stopped reading: the tunnel was closed so the sender '
                               'could unwind'),
    ('the outbound link did not come up; trying again in', 'the Bluetooth link to the car failed '
                                                          'and is being retried'),
    ('retrying the AirPlay session in', 'the session failed and is being rebuilt'),
    ('; not retrying any more', 'the session kept failing: this side gave up'),
    ('0x1D03', 'the car rejected identification outright'),
    ('mutual authentication', 'see the line'),
]

# lines that are the end of a session rather than a failure
STOPPED = ['service destroyed', 'the car closed the session', 'session read ended']

# these are only assembled at run time, so --verify-patterns cannot look for them
RUNTIME_ONLY = {'signature valid=false'}

# a line that looks like trouble but is part of a normal run: the audio
# negotiation turns offers down on purpose until the car takes one
EXCUSED = ['the car refused PCM/']


def fires(pattern, text):
    """True when a warning pattern appears on a line that is not an expected one."""
    lines = [line for line in text.splitlines() if pattern in line]
    if not lines:
        return False
    return any(not any(excuse in line for excuse in EXCUSED) for line in lines)


def read_log(args):
    if args.log:
        with open(args.log, 'r', encoding='utf-8', errors='replace') as handle:
            return handle.read()
    if args.logcat:
        adb = os.path.join(ROOT, 'tools', 'platform-tools', 'adb.exe')
        output = subprocess.run([adb, 'logcat', '-d', '-s', 'iccoa-sink:V'],
                                capture_output=True, text=True)
        return output.stdout
    return sys.stdin.read()


def verify_patterns():
    text = []
    for folder, _, files in os.walk(SOURCES):
        for name in files:
            if name.endswith('.java'):
                with open(os.path.join(folder, name), 'r', encoding='utf-8',
                          errors='replace') as handle:
                    text.append(handle.read())
    joined = '\n'.join(text)
    missing = [pattern for _, pattern, _ in STEPS if pattern not in joined]
    missing += [pattern for pattern, _ in WARNINGS
                if pattern not in joined and pattern not in RUNTIME_ONLY]
    if missing:
        print('these patterns are not in the sources any more:')
        for pattern in missing:
            print('  ' + pattern)
        return 1
    print('every pattern still exists in the sources')
    return 0


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--log')
    parser.add_argument('--logcat', action='store_true')
    parser.add_argument('--verify-patterns', action='store_true')
    parser.add_argument('--expect', help='comma separated steps that must be reached')
    args = parser.parse_args()
    if args.verify_patterns:
        return verify_patterns()

    text = read_log(args)
    reached = []
    for name, pattern, meaning in STEPS:
        if pattern in text:
            reached.append((name, meaning))
    missing = [(name, pattern, meaning) for name, pattern, meaning in STEPS
               if pattern not in text]
    fired = [(pattern, meaning) for pattern, meaning in WARNINGS if fires(pattern, text)]
    stopped = [line for line in STOPPED if line in text]

    print('steps reached: %d of %d' % (len(reached), len(STEPS)))
    for name, meaning in reached:
        print('  ok   %-14s %s' % (name, meaning))
    if reached:
        furthest = max(index for index, entry in enumerate(STEPS)
                       if entry[1] in text)
        after = [entry for entry in STEPS[furthest + 1:]]
        before = [entry for entry in STEPS[:furthest + 1] if entry[1] not in text]
        print('furthest step seen: %s' % STEPS[furthest][0])
        if before:
            print('not in this log: ' + ', '.join(entry[0] for entry in before))
        if after:
            print('next step not seen: %s (%s)' % (after[0][0], after[0][2]))
            for name, pattern, meaning in after[1:]:
                print('  --   %-14s %s' % (name, meaning))
    else:
        print('none of the pipeline steps are in this log')
    if stopped:
        print('the run ended: ' + '; '.join(stopped))
    if fired:
        print('things to look at:')
        for pattern, meaning in fired:
            print('  !!   %-36s %s' % (pattern, meaning))
    else:
        print('no degraded-path lines found')

    if args.expect:
        wanted = [name for name in args.expect.split(',')]
        not_reached = [name for name in wanted
                       if name not in [entry[0] for entry in reached]]
        if not_reached:
            print('VERDICT: these expected steps were not reached: %s'
                  % ', '.join(not_reached))
            return 3
        print('VERDICT: the expected steps were reached')
        return 0

    if len(reached) == len(STEPS):
        print('VERDICT: the whole pipeline reported success')
        return 0
    if not reached:
        return 2
    furthest = max(index for index, entry in enumerate(STEPS) if entry[1] in text)
    after = STEPS[furthest + 1:]
    if not after:
        print('VERDICT: every step after the ones this log starts at was reached')
        return 0
    print('VERDICT: stopped before %s' % after[0][0])
    return 2


if __name__ == '__main__':
    sys.exit(main())
