#!/usr/bin/env python3
"""Build the Lynk project's static Pages site and validate its shared update metadata."""
from html import escape as e
import json
from pathlib import Path
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
SITE = ROOT / 'site'
BASE = 'https://soBigRice.github.io/LynkCo-DiPlay/'
REPO = 'https://github.com/soBigRice/LynkCo-DiPlay'
PACKAGE = 'com.shihab.diplay.lynk'
SOURCE = REPO + '/archive/refs/heads/main.zip'


def validate_manifest(data):
    if type(data.get('schemaVersion')) is not int or data.get('schemaVersion') != 1 or data.get('packageName') != PACKAGE or 'release' not in data:
        raise ValueError('Invalid Lynk update manifest identity/schema')
    release = data['release']
    if release is None:
        return None
    for key, minimum in [('versionCode', 1), ('minSdk', 28)]:
        if type(release.get(key)) is not int or release[key] < minimum:
            raise ValueError('Invalid ' + key)
    if not isinstance(release.get('versionName'), str) or not release['versionName'].strip() or len(release['versionName']) > 100:
        raise ValueError('Invalid versionName')
    if not isinstance(release.get('notes'), str) or len(release['notes']) > 8000:
        raise ValueError('Invalid notes')
    for key, prefix in [('downloadUrl', '/soBigRice/LynkCo-DiPlay/releases/download/'),
                        ('releaseUrl', '/soBigRice/LynkCo-DiPlay/releases/tag/')]:
        url = urlsplit(release[key])
        if url.scheme != 'https' or url.netloc != 'github.com' or url.query or url.fragment or not url.path.startswith(prefix):
            raise ValueError('Updates must use this repository on HTTPS GitHub')
        if '%' in url.path or any(part in ('.', '..') for part in url.path.split('/')) or len(url.path) <= len(prefix):
            raise ValueError('Invalid release path')
    if not release['downloadUrl'].endswith('.apk'):
        raise ValueError('Expected APK asset')
    return release


def build():
    content = json.loads((SITE / 'content.json').read_text())
    release = validate_manifest(json.loads((SITE / 'updates/latest.json').read_text()))
    for lang, d in content.items():
        folder = SITE if lang == 'zh-Hans' else SITE / 'en'
        folder.mkdir(exist_ok=True)
        prefix = './' if lang == 'zh-Hans' else '../'
        url = BASE + ('' if lang == 'zh-Hans' else 'en/')
        primary = release['downloadUrl'] if release else SOURCE
        primary_text = d['download'] if release else d['sourceDownload']
        release_text = release['versionName'] if release else d['unpublished']
        notes = release['notes'] if release else d['releaseHint']
        (folder / 'index.html').write_text(f'''<!doctype html>
<html lang="{lang}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>LynkCo CarPlay · {e(d['tag'])}</title><meta name="description" content="{e(d['intro'])}"><meta name="theme-color" content="#121519">
<link rel="icon" href="{prefix}assets/icon.png"><link rel="stylesheet" href="{prefix}assets/site.css"><link rel="canonical" href="{url}">
<link rel="alternate" hreflang="zh-Hans" href="{BASE}"><link rel="alternate" hreflang="en" href="{BASE}en/">
<meta property="og:title" content="LynkCo CarPlay"><meta property="og:description" content="{e(d['intro'])}"><meta property="og:image" content="{BASE}assets/home.png"><meta property="og:url" content="{url}"><meta property="og:type" content="website">
</head><body><main>
<header><a class="brand" href="{prefix}"><strong>LynkCo <span>CarPlay</span></strong><small>{e(d['tag'])}</small></a>
<nav aria-label="{e(d['navigation'])}"><a href="#download">{e(d['downloads'])}</a><a href="#install">{e(d['install'])}</a><a href="#disclaimer">{e(d['disclaimerTitle'])}</a><a href="{REPO}">GitHub</a><a href="{prefix}{'en/' if lang == 'zh-Hans' else ''}" lang="{'en' if lang == 'zh-Hans' else 'zh-Hans'}">{'English' if lang == 'zh-Hans' else '中文'}</a></nav></header>
<section class="hero"><h1>{e(d['title']).replace(chr(10), '<br>')}</h1><p class="intro">{e(d['intro'])}</p>
<aside class="risk-notice" aria-labelledby="risk-title"><strong id="risk-title">{e(d['riskTitle'])}</strong><p>{e(d['riskSummary'])}</p><a href="#disclaimer">{e(d['disclaimerLink'])} ↓</a></aside>
<aside class="connection-notice" aria-labelledby="connection-status"><strong id="connection-status">{e(d['connectionTitle'])}</strong><p>{e(d['wiredNotice'])}</p><p>{e(d['wirelessNotice'])}</p></aside>
<div class="actions"><a class="button" href="{primary}">{e(primary_text)} <span aria-hidden="true">↓</span></a><a class="button secondary" href="#install">{e(d['install'])}</a></div><p class="note">{e(d['scope'])}</p></section>
<figure class="product"><a href="{prefix}assets/home.png"><img src="{prefix}assets/home.png" width="2560" height="1600" alt="{e(d['screenshotAlt'])}" fetchpriority="high"></a><figcaption>{e(d['caption'])}</figcaption></figure>
<section class="download-section" id="download"><div><h2>{e(d['downloads'])}</h2><p class="version">{e(release_text)}</p><p>{e(notes).replace(chr(10), '<br>')}</p></div><div class="download-actions"><a class="button" href="{primary}">{e(primary_text)} <span aria-hidden="true">↓</span></a><a href="{REPO}/releases">{e(d['releasePage'])} ↗</a></div></section>
<section class="disclaimer card" id="disclaimer" aria-labelledby="disclaimer-title"><h2 id="disclaimer-title">{e(d['disclaimerTitle'])}</h2><p>{e(d['disclaimerIntro'])}</p><dl>{''.join('<div><dt>' + e(risk['title']) + '</dt><dd>' + e(risk['text']) + '</dd></div>' for risk in d['risks'])}</dl><p class="disclaimer-terms">{e(d['disclaimerTerms'])}</p><nav><a href="{REPO}/blob/main/docs/licenses/DiAuto-AGPL-3.0.txt">AGPL-3.0 §15–17</a><a href="{REPO}/blob/main/docs/PRIVACY.md">{e(d['privacy'])}</a><a href="{REPO}/blob/main/docs/THIRD_PARTY_NOTICES.md">{e(d['notices'])}</a></nav></section>
<div class="grid"><section class="card" id="install"><h2>{e(d['setup'])}</h2><ol>{''.join('<li>' + e(step) + '</li>' for step in d['steps'])}</ol><p class="note">{e(d['updateHint'])}</p><a href="{REPO}/blob/main/docs/LYNK_OS_N.md">{e(d['guide'])} ↗</a></section>
<section class="card"><h2>{e(d['featuresTitle'])}</h2><ul>{''.join('<li>' + e(item) + '</li>' for item in d['features'])}</ul><h3>{e(d['compatibility'])}</h3><p>{e(d['compatibilityText'])}</p></section></div>
<section class="support"><div><h2>{e(d['feedbackTitle'])}</h2><p>{e(d['feedbackText'])}</p></div><a class="button secondary" href="{REPO}/issues/new/choose">{e(d['feedback'])} ↗</a></section>
<section class="credits"><h2>{e(d['creditsTitle'])}</h2><p>{e(d['credits'])} <a href="https://github.com/shihabal3amri/DiPlay">DiPlay</a> · <a href="{REPO}/blob/main/docs/THIRD_PARTY_NOTICES.md">{e(d['notices'])}</a></p></section>
<footer><nav><a href="{REPO}">{e(d['source'])}</a><a href="{REPO}/blob/main/LICENSE">AGPL-3.0</a><a href="#disclaimer">{e(d['disclaimerTitle'])}</a><a href="{REPO}/blob/main/docs/PRIVACY.md">{e(d['privacy'])}</a><a href="{REPO}/releases">{e(d['releasePage'])}</a></nav><p>{e(d['footer'])}</p></footer>
</main></body></html>''')
    # Preserve old inbound language URLs without advertising upstream APKs as Lynk builds.
    for lang in ['ar', 'es', 'ru', 'uk', 'zh-Hans']:
        folder = SITE / lang
        folder.mkdir(exist_ok=True)
        destination = '../' if lang == 'zh-Hans' else '../en/'
        (folder / 'index.html').write_text(f'<!doctype html><html lang="en"><meta charset="utf-8"><meta http-equiv="refresh" content="0;url={destination}"><title>LynkCo CarPlay</title><a href="{destination}">LynkCo CarPlay</a></html>')
    (SITE / '.nojekyll').touch()
    print('Built Chinese and English Lynk pages; APK:', release['versionName'] if release else 'not published')


if __name__ == '__main__':
    build()
