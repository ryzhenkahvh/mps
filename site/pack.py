#!/usr/bin/env python3
"""Builds site/mps-site.zip: the archive that is unpacked into the folder of the site on the hosting.

    python3 site/pack.py

What goes in: everything under site/public, the APK in site/public/download included.
What never goes in: app/config.php and whatever the site keeps for itself in app/data
(the settings of the database, its key, answers that wait) — so the archive holds no
passwords and may lie in a public repository.
"""
import os
import sys
import time
import zipfile

here = os.path.dirname(os.path.abspath(__file__))
root = os.path.join(here, 'public')
out = os.path.join(here, 'mps-site.zip')


def left_out(rel):
    if rel == 'app/config.php':
        return True
    return rel.startswith('app/data/') and rel != 'app/data/.htaccess'


names = []
for folder, dirs, files in os.walk(root):
    dirs.sort()
    for name in sorted(files):
        rel = os.path.relpath(os.path.join(folder, name), root).replace(os.sep, '/')
        if not left_out(rel):
            names.append(rel)

apks = [n for n in names if n.startswith('download/') and n.endswith('.apk')]
if len(apks) > 1:
    sys.exit('more than one APK in site/public/download: ' + ', '.join(apks))

tmp = out + '.tmp'
with zipfile.ZipFile(tmp, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    for rel in names:
        path = os.path.join(root, rel)
        info = zipfile.ZipInfo(rel, time.gmtime(os.path.getmtime(path))[:6])
        # an APK, a picture and a video are compressed already
        info.compress_type = zipfile.ZIP_STORED if rel.endswith(('.apk', '.png', '.jpg', '.mp4')) else zipfile.ZIP_DEFLATED
        info.external_attr = 0o100644 << 16
        with open(path, 'rb') as f:
            z.writestr(info, f.read(), compresslevel=9)
os.replace(tmp, out)

print('%s: %d files, %.1f MB%s' % (os.path.relpath(out), len(names), os.path.getsize(out) / 1048576,
                                   ', with ' + apks[0] if apks else ', WITHOUT an APK (the download page will say the app is coming)'))
