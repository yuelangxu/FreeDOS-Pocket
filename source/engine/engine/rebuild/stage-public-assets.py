"""Recover only known public packaging assets from a release APK into a fresh staging directory."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path, PurePosixPath
import shutil
import tempfile
import zipfile

def main(apk, dest):
    spec=json.loads((Path(__file__).resolve().parents[1]/'public-asset-hashes.json').read_bytes())
    if dest.exists():raise FileExistsError(f'Use a new directory: {dest}')
    with zipfile.ZipFile(apk) as z, tempfile.TemporaryDirectory(dir=dest.parent) as tmp:
        actual=[n for n in z.namelist() if n.startswith('assets/') and not n.endswith('/')]
        if sorted(actual)!=sorted(spec):raise ValueError('Public APK asset set mismatch')
        stage=Path(tmp)/'assets';stage.mkdir()
        for n,r in spec.items():
            rel=PurePosixPath(n)
            if not rel.parts[0]=='assets' or '..' in rel.parts or '\\' in n:raise ValueError('Invalid public asset name')
            out=stage.joinpath(*rel.parts[1:]);out.parent.mkdir(exist_ok=True,parents=True)
            if z.getinfo(n).file_size!=r['bytes']:raise ValueError('Wrong entry size: '+n)
            with z.open(n) as s, out.open('xb') as f:shutil.copyfileobj(s,f,length=1024*1024)
            h=hashlib.sha256();size=0
            with (gzip.open(out,'rb') if r.get('expandedBytes') else out.open('rb')) as s:
                while block:=s.read(1024*1024):
                    size+=len(block)
                    if size>r.get('expandedBytes',r['bytes']):raise ValueError('Unexpected expanded length: '+n)
                    h.update(block)
            if size!=r.get('expandedBytes',r['bytes']) or h.hexdigest()!=r['sha256']:raise ValueError('Unexpected public asset: '+n)
        stage.rename(dest)
    print('Recovered and verified public assets. No guest disk from a device was used.')

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('apk',type=Path);p.add_argument('new_directory',type=Path)
    a=p.parse_args();main(a.apk,a.new_directory.resolve())
