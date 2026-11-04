"""Read-only launcher/reminder navigation rehearsal on an explicitly selected, unlocked device.

No records are created, completed, deleted or cleared. The script opens Weight and
returns to Today through real Activity intents, with Home between launches.
"""
import argparse
import subprocess
import time
import xml.etree.ElementTree as ET

p = argparse.ArgumentParser()
p.add_argument('--adb', default='adb')
p.add_argument('--serial', required=True)
p.add_argument('--cycles', type=int, default=5)
a = p.parse_args()
assert 1 <= a.cycles <= 20


def adb(*args):
    return subprocess.check_output([a.adb, '-s', a.serial, *args], text=True, timeout=20)


def nodes():
    adb('shell', 'uiautomator', 'dump', '/sdcard/euros-lifecycle.xml')
    xml = adb('exec-out', 'cat', '/sdcard/euros-lifecycle.xml')
    return [n.attrib for n in ET.fromstring(xml).iter('node') if n.get('package') == 'com.gwensmuda.euros']


def verify(expected):
    for _ in range(3):
        current = nodes()
        if expected(current):
            return
        time.sleep(.3)
    raise AssertionError('Expected Euros destination missing; unlock device or inspect navigation')


try:
    for cycle in range(a.cycles):
        adb('shell', 'am', 'start', '-W', '-n', 'com.gwensmuda.euros/.MainActivity', '--es', 'activity_id', '@weight')
        verify(lambda ns: any(n.get('text') == 'Weight · lb' for n in ns))
        adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
        adb('shell', 'am', 'start', '-W', '-n', 'com.gwensmuda.euros/.MainActivity', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.LAUNCHER')
        verify(lambda ns: any(n.get('text') == 'days to Euros' for n in ns) and not any(n.get('text') == 'Weight · lb' for n in ns))
        print(f'Cycle {cycle + 1}: reminder → Weight; Home → launcher → Today', flush=True)
finally:
    adb('shell', 'rm', '-f', '/sdcard/euros-lifecycle.xml')
