"""Verify a dual- or four-ABI release source ZIP, optionally binding its native binaries to an APK."""
import argparse
import hashlib
import json
import zipfile
from pathlib import Path, PurePosixPath

ABIS = {'arm64-v8a', 'x86_64'}
LIBS = {'lib' + n + '.so' for n in ('SDL2','compat-SDL2-addons','compat-SDL2-ext','compat-limbo','compat-musl','glib-2.0','limbo','pixman-1','qemu-system-x86_64')}

def check(test, message):
    if not test:
        raise ValueError(message)

def sha(stream):
    return hashlib.file_digest(stream, 'sha256').hexdigest()

def verify(filename, apk=None, expected_release=None):
    with zipfile.ZipFile(filename) as z:
        names = z.namelist()
        check(len(names) == len(set(names)), 'Duplicate ZIP member')
        for n in names:
            path = PurePosixPath(n)
            check(path.parts[0]=='engine' and '..' not in path.parts and '\\' not in n and not path.is_absolute(), f'Invalid member: {n}')
            seed_manifest = n == ('engine/project/android-app/app/src/personalCommunityUniversalRelease/'
                                  'assets-nocdrom/seed/manifest.json')
            check(not any(seg.lower() in {'private','.git','.gradle','personal','build','obj','jnilibs'} for seg in path.parts)
                  and ('seed' not in (p.lower() for p in path.parts) or seed_manifest)
                  and path.suffix.lower() not in {'.so','.o','.jks','.p12','.lineage','.dpapi','.vdi','.apk','.img'}, f'Unexpected private/binary entry: {n}')
        m = json.loads(z.read('engine/manifest.json'))
        required = {'BUILDING.md','verify-archive.py','rebuild/prepare-native-tree.py','rebuild/build-abi.sh','native-inputs.json','rebuild/deps.sh','rebuild/core.sh','project/android-app/app/build.gradle','project/android-app/app/AndroidManifest.xml'}
        rec = {r['path']: r for r in m['files']}
        check(required <= rec.keys() and set(names) == {'engine/'+n for n in rec}|{'engine/manifest.json'}, 'Incomplete source ZIP')
        four_abis = ABIS|{'armeabi-v7a','x86'}
        releases={'0.3.16-public':ABIS,'0.3.17-public':four_abis,'0.3.20':four_abis}
        check(m['release'] in releases and (expected_release is None or expected_release==m['release'])
              and len(m['upstream_archives'])==6 and len(m['patches'])==5, 'Wrong source baseline')
        abis=releases[m['release']]
        for n,r in rec.items():
            check(z.getinfo('engine/'+n).file_size == r['bytes'], 'Wrong length: '+n)
            with z.open('engine/'+n) as f:
                check(sha(f)==r['sha256'], 'Wrong source hash: '+n)
        for group, folder in [('upstream_archives','upstream'),('patches','patches')]:
            for r in m[group]:
                check(r['sha256']==rec[folder+'/'+r['name']]['sha256'], 'Unpinned source archive/patch')
        bindings = json.loads(z.read('engine/native-inputs.json'))['libraries']
        check(set(bindings)==abis and all(set(s)==LIBS for s in bindings.values()), 'ABI or library set mismatch')
        if apk:
            with zipfile.ZipFile(apk) as a:
                actual=[n for n in a.namelist() if n.startswith('lib/') and not n.endswith('/')]
                check(sorted(actual)==sorted(f'lib/{abi}/{n}' for abi in abis for n in LIBS),'Wrong APK ABI/library set')
                for abi in abis:
                    for n,h in bindings[abi].items():
                        with a.open(f'lib/{abi}/{n}') as f:
                            check(sha(f)==h,f'APK native input mismatch: {abi}/{n}')
    return {'release':m['release'], 'verifiedSourceFiles':len(rec), 'abiLibraryHashes':len(abis)*len(LIBS), 'matchesSelectedApk':bool(apk)}

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('archive',type=Path);p.add_argument('--apk',type=Path)
    args=p.parse_args();print(json.dumps(verify(args.archive,args.apk),indent=2))
