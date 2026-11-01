# SPDX-License-Identifier: Apache-2.0
"""Compare the live Edge baseline with the deployed prebuilt browser host.

All processing is local. Shared assets are associated by request start time,
not by a per-job URL. Network envelopes overlap initialization; never sum them.
"""
import argparse
import copy
import json
import statistics
from pathlib import Path
from urllib.parse import urlsplit

from analyze import analyze, epoch_ms

HOSTS = {'play.bhashitva.org', 'run.bhashitva.org'}
HEADERS = {'accept', 'accept-encoding', 'origin', 'referer', 'content-type',
           'content-length', 'content-encoding', 'cache-control', 'etag',
           'last-modified', 'vary', 'if-none-match', 'if-modified-since',
           'timing-allow-origin', 'cross-origin-resource-policy'}


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def write(path, data):
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def sanitized(har):
    result = {'log': {'version': '1.2', 'creator': har['log']['creator'], 'entries': []}}
    for entry in har['log']['entries']:
        url = urlsplit(entry['request']['url'])
        if url.scheme != 'https' or url.hostname not in HOSTS:
            continue
        if not (url.path.startswith(('/api/', '/artifacts/', '/browser-host/')) or
                url.path in {'/', '/index.html', '/app.js', '/style.css', '/sw.js',
                             '/icon.svg', '/manifest.webmanifest'}):
            continue
        assert not url.query, 'Review unexpected query parameters before publishing.'
        kept = {key: copy.deepcopy(entry[key]) for key in (
            'startedDateTime', 'time', 'cache', 'timings', 'connection',
            '_fromCache', '_resourceType', '_priority') if key in entry}
        for kind in ['request', 'response']:
            side = entry[kind]
            keys = (['method', 'url', 'httpVersion', 'headersSize', 'bodySize', 'postData']
                    if kind == 'request' else
                    ['status', 'statusText', 'httpVersion', 'headersSize', 'bodySize',
                     'redirectURL', '_transferSize', '_error', '_fetchedViaServiceWorker'])
            kept[kind] = {key: copy.deepcopy(side[key]) for key in keys if key in side}
            kept[kind]['headers'] = [copy.deepcopy(header) for header in side.get('headers', [])
                                     if header['name'].lower() in HEADERS]
            kept[kind]['cookies'] = []
        kept['request']['queryString'] = []
        kept['response']['content'] = {key: value for key, value in entry['response'].get('content', {}).items()
                                        if key in {'size', 'mimeType', 'compression', 'comment'}}
        result['log']['entries'].append(kept)
    return result


def extended_analysis(probe, har):
    rows = analyze(probe, {})
    for row, run in zip(rows, probe['runs']):
        ready = next(item for item in run['api'] if item.get('status') == 'ready')
        timing = ready.get('timings', {})
        row['reported_bundle_build_ms'] = timing.get('compileMs')
        hit = row['server_cache_hit']
        row['this_run_queue_ms'] = 0 if hit else timing.get('queueMs')
        row['this_run_build_ms'] = 0 if hit else timing.get('compileMs')
        if timing:
            row['request_poll_and_ready_overhead_ms'] = (
                row['click_to_ready_response_ms'] - row['this_run_queue_ms'] - row['this_run_build_ms'])
        lo = probe['timeOrigin'] + run['click']
        hi = probe['timeOrigin'] + run['firstVisibleOutput']
        prefix = run['runnerUrl'].rsplit('/', 1)[0] + '/'
        assets = [entry for entry in har['log']['entries']
                  if (entry['request']['url'].startswith(prefix) or
                      entry['request']['url'].startswith('https://run.bhashitva.org/browser-host/'))
                  and lo <= epoch_ms(entry['startedDateTime']) <= hi]
        assert assets, run['label']
        first = min(epoch_ms(entry['startedDateTime']) for entry in assets)
        last = max(epoch_ms(entry['startedDateTime']) + entry['time'] for entry in assets)
        transferred = lambda entries: sum(max(0, entry['response'].get('_transferSize', 0)) for entry in entries)
        runtime = [entry for entry in assets if '/browser-host/' in entry['request']['url']]
        wire = [entry for entry in assets if entry['response'].get('_transferSize', 0) > 0]
        messages = {item['type']: item['t'] for item in run['messages']}
        row.update({
            'asset_request_count': len(assets),
            'asset_network_envelope_ms': last - first,
            'asset_transfer_bytes': transferred(assets),
            'shared_runtime_request_count': len(runtime),
            'shared_runtime_transfer_bytes': transferred(runtime),
            'program_artifact_transfer_bytes': transferred(assets) - transferred(runtime),
            'asset_decoded_content_bytes': sum(max(0, entry['response'].get('content', {}).get('size', 0)) for entry in assets),
            'asset_cache_entries': sum(bool(entry.get('_fromCache')) or entry['response'].get('_transferSize') == 0 for entry in assets),
            'wire_request_envelope_ms': (max(epoch_ms(entry['startedDateTime']) + entry['time'] for entry in wire)
                - min(epoch_ms(entry['startedDateTime']) for entry in wire)) if wire else 0,
            'runtime_after_last_asset_response_ms': probe['timeOrigin'] + messages['running'] - last,
            'asset_content_encodings': sorted({header['value'] for entry in assets
                for header in entry['response'].get('headers', []) if header['name'].lower() == 'content-encoding'}),
        })
    return rows


def compare(before, after):
    groups = {
        'hello_source_miss': ['hello-cold-1', 'hello-cold-2', 'hello-cold-3'],
        'hello_cached': ['hello-warm-1', 'hello-warm-2', 'hello-warm-3'],
        'loop_source_miss': ['cpu-cold'],
        'loop_cached': ['cpu-warm-1', 'cpu-warm-2', 'cpu-warm-3'],
    }
    result = {}
    for name, labels in groups.items():
        old = statistics.median(row['click_to_output_ms'] for row in before if row['label'] in labels)
        new = statistics.median(row['click_to_output_ms'] for row in after if row['label'] in labels)
        result[name] = {'samples_per_path': len(labels), 'baseline_median_ms': old,
                        'prebuilt_median_ms': new, 'percent_change': (new / old - 1) * 100}
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--after-probe', type=Path, required=True)
    parser.add_argument('--after-har', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parent
    after_probe = read(args.after_probe)
    assert len(after_probe['runs']) == 10
    assert all(run.get('exitCode') == 0 and run.get('errorCharacters') == 0 for run in after_probe['runs'])
    args.output.mkdir(parents=True, exist_ok=True)
    clean_har = sanitized(read(args.after_har))
    write(args.output / 'network.har', clean_har)
    write(args.output / 'timings.json', after_probe)
    baseline_probe = read(root / 'timings.json')
    before = analyze(baseline_probe, read(root / 'network.har'))
    after = extended_analysis(after_probe, clean_har)
    assert len({row['label'] for row in after}) == 10
    assert sum(not row['server_cache_hit'] for row in after) == 4
    summary = {'baseline_browser': baseline_probe['browser'], 'after_browser': after_probe['browser'],
               'groups': compare(before, after), 'baseline': before, 'prebuilt': after}
    write(args.output / 'analysis.json', summary)
    print(json.dumps(summary['groups'], indent=2))
