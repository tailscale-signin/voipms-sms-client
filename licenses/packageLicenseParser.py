import json
import sys
from pathlib import Path
from zipfile import ZipFile

licenseMap = {
    # Android SDK
    'Android Software Development Kit License': 'Android-SDK.txt',

    # Apache License 2.0
    'Apache 2.0': 'Apache-2.0.txt',
    'Apache License 2.0': 'Apache-2.0.txt',
    'Apache License, Version 2.0': 'Apache-2.0.txt',
    'Apache-2.0': 'Apache-2.0.txt',
    'The Apache License, Version 2.0': 'Apache-2.0.txt',
    'The Apache Software License, Version 2.0': 'Apache-2.0.txt',
    'Apache License v2': 'Apache-2.0.txt',

    # BSD License
    'BSD-3-Clause': 'BSD-3-Clause.txt',
}

licenseGroupMap = {
    ('The MIT License', 'org.checkerframework', 'checker-qual', '3.12.0'): 'checker-qual.txt',
    ('MIT License', 'com.github.mukeshsolanki', 'MarkdownView-Android', '2.0.0'): 'MarkdownView-Android.txt'
}

mlkitTermsUrl = 'https://developers.google.com/ml-kit/terms'
reviewedMlkitPoms = {('common', '18.11.0'), ('translate', '17.0.3')}

license_dir = Path(__file__).resolve().parent
with open(sys.argv[1], encoding="utf-8") as metadata:
    dependencies = json.load(metadata)

# The OSS plugin reads embedded notices only for Google Play Services and Firebase.
# Other AARs can contain additional native libraries, independent of their Maven license.
embedded = {(e['name'], l['text']) for e in dependencies if e['artifactInfo'] is None
            for l in e['licenses'] if l['text']}
coordinates = {(a['group'], a['name'], a['version']) for e in dependencies
               if (a := e['artifactInfo']) is not None}

def add_notice(name, text):
    if (name, text) not in embedded:
        dependencies.append({'name': name, 'artifactInfo': None,
                             'licenses': [{'name': None, 'url': None, 'text': text}]})
        embedded.add((name, text))

for archive in map(Path, sys.argv[3:]):
    if archive.name.startswith('tesseract4android-'):
        # This Maven POM omits licenses and its AAR has no embedded notice index.
        version = archive.stem.removeprefix('tesseract4android-')
        native = (license_dir / f'Tesseract4Android-{version}-native.txt').read_text(encoding='utf-8')
        add_notice(f'Tesseract4Android {version} and Tesseract OCR',
                   (license_dir / 'Apache-2.0.txt').read_text(encoding='utf-8'))
        add_notice(f'Tesseract4Android {version} bundled native notices', native)
        continue
    module, version = archive.stem.split('-', 1)
    if ('com.google.mlkit', module, version) not in coordinates:
        # Debug variants have no POM license list. Supply the two reviewed declarations.
        if (module, version) not in reviewedMlkitPoms:
            raise ValueError(f'Review the license metadata for {archive.name}')
        dependencies.append({'name': module, 'artifactInfo': {
            'group': 'com.google.mlkit', 'name': module, 'version': version},
            'licenses': [{'name': 'ML Kit Terms of Service', 'url': mlkitTermsUrl, 'text': None}]})
    with ZipFile(archive) as aar:
        index = json.loads(aar.read('third_party_licenses.json'))
        pool = aar.read('third_party_licenses.txt')
        for name, span in index.items():
            start, length = span['start'], span['length']
            if start < 0 or length <= 0 or start + length > len(pool):
                raise ValueError(f'Invalid embedded notice for {name} in {archive.name}')
            add_notice(name, pool[start:start + length].decode('utf-8'))

dependencies.sort(key=lambda e: e['name'])
for entry in dependencies:
    for l in entry['licenses']:
        if l['text'] is None:
            fileName = None
            if l['name'] in licenseMap:
                fileName = licenseMap[l['name']]
            else:
                artifactInfo = entry['artifactInfo']
                if artifactInfo is not None:
                    # Google publishes these SDK terms by reference in its Maven metadata.
                    # Keep ordinary OSS licenses on the full-text path.
                    if (artifactInfo['group'] == 'com.google.mlkit' and
                            l['name'] == 'ML Kit Terms of Service' and l.get('url') == mlkitTermsUrl):
                        l['publisherTermsReference'] = True
                        continue
                    key = (l['name'], artifactInfo['group'], artifactInfo['name'], artifactInfo['version'])
                    if key in licenseGroupMap:
                        fileName = licenseGroupMap[key]
            if fileName is not None:
                l['text'] = (license_dir / fileName).read_text(encoding='utf-8')
            else:
                raise Exception(entry)

with open(sys.argv[2], 'w', encoding='utf-8') as f:
    for entry in dependencies:
        artifactInfo = entry['artifactInfo']
        if artifactInfo is not None:
            f.write(f'## {entry["name"]} ({artifactInfo["group"]}:{artifactInfo["name"]}:{artifactInfo["version"]})')
        else:
            f.write(f'## {entry["name"]}')
        for l in entry['licenses']:
            if l.get('publisherTermsReference'):
                f.write(f'\n\n[ML Kit Terms of Service]({mlkitTermsUrl})\n\n'
                        'Google publishes the terms for this SDK at the link above. '
                        'They incorporate the [Google APIs Terms of Service](https://developers.google.com/terms).\n\n')
                continue
            f.write('\n\n```\n')
            f.write(l['text'].strip('\n'))
            f.write('\n```\n\n')
