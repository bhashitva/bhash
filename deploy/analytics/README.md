# Bhashitva GA4

Shared web-stream Measurement ID: `G-J31MR361WE` (public identifier, not a secret).

`bhash-analytics.js` is the canonical tag for bhashitva.org, play.bhashitva.org,
and chess.bhashitva.org. It is disabled on development hosts and run.bhashitva.org,
and honors browser Do Not Track / Global Privacy Control. It uses a shared
bhashitva.org cookie domain, disables Google Signals and advertising personalization,
and sends a `site_area` value: website, playground or chess.

Only pathname and restricted utm_source/utm_medium/utm_campaign values are
included in page context. Shared-code fragments, other query fields, source,
input, runtime output, errors, and chess positions are not custom-event fields.
The event wrapper accepts only enumerated parameters; never add free-form text.
Browser privacy flags, blockers and cookie choices can reduce measured traffic.
Integrate any site's consent manager before tag initialization rather than adding
a second tag. Do not add Site Kit/GTM simultaneously for this ID.

## Installation

- WordPress: put `bhash-analytics.php` and `bhash-analytics.js` directly in
  `wp-content/mu-plugins/`. Public pages get the tag; signed-in users do not.
- Playground: `npm run build` in playground copies the script; index.html loads
  it. The Java server permits the named Google script and collection endpoints
  only on the editor origin; execution-origin isolation is unchanged.
- Chess: copy the JS beside the deployed index.html; apply `chess-analytics.patch`
  to the reviewed deployed application. The patch adds a script reference and
  game events, without sending positions or move history. Keep the chess source
  repository's own licensing/notices with its distribution.

`chess_game_start` fires after the first successful move in a fresh page/game,
including a resumed game. `chess_game_complete` fires at most once per such game
session, after a successful move reaches a finished position. Loading a terminal
FEN alone is not counted as game completion. `chess_restart` counts button actions.

## Existing VPS pilot

The pilot can retain its existing offline compiler image. Extract a pinned source
release with playground/, deploy/analytics/, src/main/antlr4/Bhash.g4, LICENSE,
NOTICE and LICENSING.md. In that release, run `npm ci` and `npm run build` inside
playground/. Then run:

```sh
bash RELEASE/deploy/analytics/activate-playground.sh /opt/bhash RELEASE
```

This backs up the stack configuration, mounts the new frontend read-only, and
uses a gateway CSP override for the older Java server. It validates Caddy before
recreating the containers without rebuilding the compiler. There is a brief
restart; existing temporary compilation results may need to be regenerated.
For rollback, rename compose.override.yaml out of the way and run
`docker compose up -d --no-build`. The original image and Caddyfile are retained.
A future full image rebuild includes the frontend and CSP directly; remove the
pilot override only as part of that reviewed deployment.

## Account configuration and verification

In the GA4 web stream, use this one ID on all three hosts. Verify that cookies
have Domain=.bhashitva.org and use Reports > Realtime while browsing normally.
For hostname breakdowns use the built-in Host name dimension. Register
`site_area`, `destination`, `mode`, `example`, `outcome`, and `stage` as event-scoped
custom dimensions if needed. Mark `donation_click` as a key event if useful;
it records interest, not a completed donation. Do not create duplicate page-view tags.

Enhanced Site search, Form interactions, Outbound clicks and browser-history
page views were disabled during setup: searches and form contents
are outside this instrumentation's intended scope. The custom `resource_click` and `donation_click` use fixed
categories rather than full target URLs. Keep advertising features disabled.
An anchored domain regex includes bhashitva.org, www.bhashitva.org,
play.bhashitva.org and chess.bhashitva.org for link attribution; validate that the same visitor cookie survives navigation.

Custom events: playground_run, playground_compile_success,
playground_run_complete (success/error), playground_run_failed (compile),
playground_stop, playground_example_select, playground_download, playground_share,
chess_game_start, chess_game_complete, chess_restart, donation_click, resource_click.

Run `node --test scripts/analytics_test.cjs` from the repository root. Tests verify
URL redaction, allowlisted event values, duplicate initialization, privacy opt-out,
and suppression on development and execution hosts. Browser and Realtime checks
are still required after each deployment.

## Deployment record (2026-10-26)

- WordPress: added the two must-use plugin files; verified public homepage script inclusion.
- Chess: updated index.html/app.js plus the shared tag; a legal e2-e4 move completed.
  Source is maintained in bhashitva/js-chess-engine branch bhash, commit
  fe559bda58b8346e72c598a95784c017c5681f7d. Its build copies the tag into distribution assets.
- Playground: frontend from commit 279425934e1915602e162441b1a0a4f915fe30d7,
  deployed at /opt/bhash/analytics-release-2794259 with a read-only analytics-web mount.
  The compiler remains at 8180c48. Caddy validated; service healthy; hello program exited 0.
- Rollback configuration: /opt/bhash/analytics-backup-20261026T135738Z.
- GA4 account 335743816, property 466329429, stream 9915340912; ID verified in the UI.
  Domain rule and enhanced-measurement settings saved. Tag UI reported sending data.
