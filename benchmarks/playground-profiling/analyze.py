# SPDX-License-Identifier: Apache-2.0
"""Analyze temporary browser probe JSON and a sanitized DevTools HAR export.

No requests are made and no production settings are modified.
Queue/compile boundaries are conservative intervals inferred from polling.
Download envelopes can overlap WebAssembly initialization; do not sum them.
"""
import argparse
import datetime as dt
import json
from pathlib import Path
from urllib.parse import urlparse


def epoch_ms(value):
    return dt.datetime.fromisoformat(value.replace('Z', '+00:00')).timestamp() * 1000


def analyze(data, har):
    entries = har.get('log', {}).get('entries', [])
    results = []
    for run in data['runs']:
        start = run['click']
        messages = {m['type']: m['t'] for m in run['messages']}
        api = run['api']
        post = next((a for a in api if a['path'] == '/api/compile'), None)
        ready = next((a for a in api if a.get('status') == 'ready'), None)
        compiling = [a for a in api if a.get('status') == 'compiling']
        queued = [a for a in api if a.get('status') == 'queued']
        result = {'label': run['label'], 'mode': run['mode'],
                  'initial_status': post.get('status') if post else None,
                  'server_cache_hit': bool(post and post.get('status') == 'ready'),
                  'click_to_output_ms': run.get('firstVisibleOutput', start) - start
                      if 'firstVisibleOutput' in run else None,
                  'click_to_ready_response_ms': ready['bodyObserved'] - start if ready else None,
                  'iframe_bootstrap_ms': messages['ready'] - ready['bodyObserved']
                      if ready and 'ready' in messages else None,
                  'worker_load_and_runtime_startup_ms': messages['running'] - messages['ready']
                      if 'running' in messages and 'ready' in messages else None,
                  'running_to_output_ms': run['firstVisibleOutput'] - messages['running']
                      if 'firstVisibleOutput' in run and 'running' in messages else None,
                  'running_to_result_message_ms': messages['result'] - messages['running']
                      if 'result' in messages and 'running' in messages else None,
                  'result_observed': 'result' in messages, 'exit_code': run.get('exitCode'),
                  'api_observations': len(api)}
        if compiling and ready and post:
            first = compiling[0]
            previous_queue = [a for a in queued if a['start'] < first['start']]
            start_lo = previous_queue[-1]['start'] if previous_queue else post['start']
            start_hi = first['bodyObserved']
            end_lo = compiling[-1]['start']
            end_hi = ready['bodyObserved']
            result['compile_ms_interval'] = [max(0, end_lo - start_hi), end_hi - start_lo]
            # Includes unknown request-arrival time; this is a conservative queue bound.
            result['queue_ms_interval'] = [max(0, start_lo - post['bodyObserved']),
                                           max(0, start_hi - post['start'])]
            result['poll_and_ready_detection_ms_interval'] = [0, max(0, end_hi - end_lo)]
        elif post and post.get('status') == 'ready':
            result['queue_ms_interval'] = [0, 0]
            result['compile_ms_interval'] = [0, 0]
        runner = run.get('runnerUrl')
        if runner:
            prefix = runner.rsplit('/', 1)[0] + '/'
            lo = data['timeOrigin'] + start
            hi = data['timeOrigin'] + run.get('firstVisibleOutput', run.get('result', start + 240000))
            assets = [e for e in entries if e['request']['url'].startswith(prefix)
                      and lo <= epoch_ms(e['startedDateTime']) <= hi]
            if assets:
                first_request = min(epoch_ms(e['startedDateTime']) for e in assets)
                last_response = max(epoch_ms(e['startedDateTime']) + e['time'] for e in assets)
                result['asset_request_count'] = len(assets)
                result['asset_network_envelope_ms'] = last_response - first_request
                known_bodies = [e['response'].get('bodySize', -1) for e in assets]
                result['asset_encoded_body_bytes'] = sum(known_bodies) if all(n >= 0 for n in known_bodies) else None
                result['asset_transfer_bytes'] = sum(max(0, e['response'].get('_transferSize', 0)) for e in assets)
                result['asset_decoded_content_bytes'] = sum(max(0, e['response'].get('content', {}).get('size', 0)) for e in assets)
                result['asset_cache_entries'] = sum(bool(e.get('_fromCache')) or
                    e['response'].get('_transferSize') == 0 for e in assets)
                wire = [e for e in assets if e['response'].get('_transferSize', 0) > 0]
                result['wire_request_envelope_ms'] = (max(epoch_ms(e['startedDateTime']) + e['time'] for e in wire)
                    - min(epoch_ms(e['startedDateTime']) for e in wire)) if wire else 0
                result['ready_response_to_first_asset_request_ms'] = first_request - (data['timeOrigin'] + ready['bodyObserved']) if ready else None
                result['asset_receive_time_sum_ms_nonadditive'] = sum(max(0, e.get('timings', {}).get('receive', 0)) for e in assets)
                if 'running' in messages:
                    result['runtime_after_last_asset_response_ms'] = data['timeOrigin'] + messages['running'] - last_response
                result['asset_content_encodings'] = sorted({h['value'] for e in assets for h in e['response'].get('headers', [])
                    if h['name'].lower() == 'content-encoding'})
        results.append(result)
    return results


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('probe', type=Path)
    parser.add_argument('--har', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    probe = json.loads(args.probe.read_text(encoding='utf-8-sig'))
    har = json.loads(args.har.read_text(encoding='utf-8-sig')) if args.har else {}
    output = {'browser': probe['browser'], 'timeOrigin': probe['timeOrigin'],
              'runs': analyze(probe, har)}
    args.output.write_text(json.dumps(output, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(output, ensure_ascii=False, indent=2))
