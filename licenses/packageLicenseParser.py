import json
import sys
from pathlib import Path

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

license_dir = Path(__file__).resolve().parent
with open(sys.argv[1], encoding="utf-8") as metadata:
    dependencies = json.load(metadata)
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
