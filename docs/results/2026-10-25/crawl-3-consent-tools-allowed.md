# Serene engine crawl, 2026-10-25T14:05Z

Viewport: Pixel 7 emulation, locale en-GB, wait 10000 ms. Modes: baseline, rules. Model: none. Autoconsent: on. Consent tools' scripts: allowed.

After each run: "annoyances" counts overlays still on screen that the detector's heuristics read as annoyances (score ≥ 0.5); "blocking" counts any overlay still covering ≥ 30% of the screen; "locked" is page scrolling. Check the screenshots: heuristics are not ground truth.

| Site | Baseline overlays | Rules: annoyances / blocking / locked | Serene: annoyances / blocking / locked | Consent rejected (autoconsent) | Model calls (ms) | 2nd visit residual / model calls |
|---|---|---|---|---|---|---|
| theguardian | cookie_consent | 0 / 0 / no | – | Sourcepoint-frame | – | 0 / 0 |
| bbc | none | 0 / 0 / no | – | – | – | 0 / 0 |
| spiegel | none | 0 / 0 / no | – | sourcepoint-top | – | 0 / 0 |
| lemonde | cookie_consent | 0 / 0 / no | – | – | – | 0 / 0 |
| elpais | none | 0 / 0 / no | – | – | – | 0 / 0 |
| corriere | cookie_consent | 0 / 0 / no | – | – | – | 0 / 0 |
| nu.nl | none | 0 / 0 / no | – | dpgmedia-nl | – | 0 / 0 |
| onet | cookie_consent | 0 / 0 / no | – | – | – | 0 / 0 |
| idnes | cookie_consent | 0 / 1 / no | – | – | – | 0 / 0 |
| yahoo | none | 0 / 0 / no | – | – | – | 0 / 0 |
| cnn | cookie_consent | 0 / 0 / no | – | – | – | 0 / 0 |
| nytimes | none | 0 / 0 / no | – | – | – | 0 / 0 |
| washingtonpost | error | error | – | – | – | – |
| forbes | none | 0 / 0 / no | – | – | – | 0 / 0 |
| businessinsider | none | 0 / 0 / no | – | – | – | 0 / 0 |
| medium | none | 0 / 0 / no | – | – | – | 0 / 0 |
| reddit | none | 0 / 0 / no | – | – | – | 0 / 0 |
| quora | none | 0 / 0 / no | – | – | – | 0 / 0 |
| pinterest | paywall_teaser | 1 / 0 / no | – | – | – | 0 / 0 |
| stackoverflow | cookie_consent | 0 / 0 / no | – | Onetrust | – | 0 / 0 |
| imdb | none | 0 / 0 / no | – | – | – | 0 / 0 |
| tripadvisor | none | 0 / 0 / no | – | – | – | 0 / 0 |
| healthline | none | 0 / 0 / no | – | – | – | 0 / 0 |
| asos | none | 0 / 0 / no | – | – | – | 0 / 0 |
| gymshark | cookie_consent, cookie_consent | 1 / 0 / no | – | Onetrust | – | 1 / 0 |
| allbirds | none | 0 / 0 / no | – | – | – | 0 / 0 |
| weather.com | none | 0 / 0 / no | – | – | – | 0 / 0 |
| wikipedia (control) | none | 0 / 0 / no | – | – | – | 0 / 0 |
| github (control) | none | 0 / 0 / no | – | – | – | 0 / 0 |
| duckduckgo (control) | none | 0 / 0 / no | – | – | – | 0 / 0 |

## Exposure

How long each overlay the engine acted on was visible on the first visit before it went, from the in-page probe (sampled every 100 ms). "Appeared" is ms after navigation start. List-hidden and cached overlays never show and are not listed. Baseline shows when the annoyances first appeared in a plain browser.

| Site | Baseline: annoyance appeared (ms) | rules: appeared → visible for (ms) |
|---|---|---|
| theguardian | cookie_consent 4313 | – |
| lemonde | cookie_consent 1627 | – |
| corriere | cookie_consent 3666 | 2770 → 0 (heuristic) |
| onet | cookie_consent 2318 | – |
| idnes | cookie_consent 3100 | – |
| cnn | cookie_consent 1732 | – |
| pinterest | login_wall 3073 | 2509 → 0 (heuristic) |
| stackoverflow | cookie_consent 2206 | – |
| gymshark | newsletter 503, cookie_consent 4403, cookie_consent 4303 | – |
| weather.com | app_install 3480 | 3468 → 250 (heuristic) |

- rules: 3 overlays acted on, visible for median 0 ms, p90 250 ms, max 250 ms.

## Decisions per site

### theguardian

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 1 candidates, 17 detector scans (max 72.4 ms, total 521 ms), first action at – ms, screenshot `shots/theguardian-baseline-first.png`
  - `body > div[role="dialog"]` bottom 44%, h=cookie_consent:1: observed · ""
  - still visible: `body > div[role="dialog"]` cookie_consent:1 ""
- **rules**: 0 requests blocked, 194 site hide rules + 1 generic, 0 candidates, 14 detector scans (max 47.9 ms, total 381 ms), first action at – ms, screenshot `shots/theguardian-rules-first.png`
  - autoconsent frame https://sourcepoint.theguardian.com: optOutResult Sourcepoint-frame → true
  - autoconsent frame https://sourcepoint.theguardian.com: autoconsentDone Sourcepoint-frame

### bbc

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 1 candidates, 16 detector scans (max 3.8 ms, total 41 ms), first action at – ms, screenshot `shots/bbc-baseline-first.png`
  - `header#bbc-header` top 6%, h=content:0: observed · ""
- **rules**: 1 requests blocked, 189 site hide rules + 0 generic, 1 candidates, 23 detector scans (max 14.8 ms, total 76 ms), first action at – ms, screenshot `shots/bbc-rules-first.png`
  - `header#bbc-header` top 6%, h=content:0: content → ignore (chrome, 1 ms) · ""

### spiegel

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 2 candidates, 13 detector scans (max 33.4 ms, total 288 ms), first action at – ms, screenshot `shots/spiegel-baseline-first.png`
  - `header.-mt-8.pointer-events-none.print\:absolute` top 6%, h=newsletter:0.25: observed · "Anmelden Menü öffnen"
  - `body > div[role="dialog"]` full 100%, h=content:0.3: observed · ""
- **rules**: 0 requests blocked, 185 site hide rules + 11 generic, 1 candidates, 13 detector scans (max 82.2 ms, total 280 ms), first action at – ms, screenshot `shots/spiegel-rules-first.png`
  - `header.-mt-8.pointer-events-none.print\:absolute` top 6%, h=newsletter:0.25: content → ignore (chrome, 0 ms) · "Anmelden Menü öffnen"
  - autoconsent top: optOutResult sourcepoint-top → true
  - autoconsent top: autoconsentDone sourcepoint-top
  - still visible: `header.-mt-8.pointer-events-none.print\:absolute` newsletter:0.25 "Anmelden Menü öffnen"

### lemonde

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 1 candidates, 11 detector scans (max 53.2 ms, total 91 ms), first action at – ms, screenshot `shots/lemonde-baseline-first.png`
  - `div.gdpr-lmd-standard.gdpr-lmd-wall` full 100%, h=cookie_consent:1: observed · "Reject all cookies Le Monde and its partners may, independently or jointly, use cookies an"
  - still visible: `div.gdpr-lmd-standard.gdpr-lmd-wall` cookie_consent:1 "Reject all cookies Le Monde and its partners may, independently or jointly, use cookies an"
- **rules**: 1 requests blocked, 185 site hide rules + 5 generic, 0 candidates, 13 detector scans (max 10.4 ms, total 59 ms), first action at – ms, screenshot `shots/lemonde-rules-first.png`

### elpais

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 0 candidates, 10 detector scans (max 0.6 ms, total 4 ms), first action at – ms, screenshot `shots/elpais-baseline-first.png`
- **rules**: 0 requests blocked, 188 site hide rules + 0 generic, 0 candidates, 11 detector scans (max 6.4 ms, total 11 ms), first action at – ms, screenshot `shots/elpais-rules-first.png`

### corriere

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 3 candidates, 30 detector scans (max 50.9 ms, total 561 ms), first action at – ms, screenshot `shots/corriere-baseline-first.png`
  - `div#rcsad_TopLeft` top 16%, h=content:0: observed · ""
  - `header.header` center 13%, h=content:0: observed · "In evidenza App Edizioni Locali Sezioni Servizi Più popolari"
  - `div.privacy-cp-wall` full 100%, h=cookie_consent:1: observed · "Rifiuta e abbonati Il Corriere della Sera si impegna quotidianamente a fornirti informazio"
  - still visible: `div.privacy-cp-wall` cookie_consent:1 "Rifiuta e abbonati Il Corriere della Sera si impegna quotidianamente a fornirti informazio"
- **rules**: 2 requests blocked, 186 site hide rules + 4 generic, 3 candidates, 28 detector scans (max 47.5 ms, total 474 ms), first action at 2839 ms, screenshot `shots/corriere-rules-first.png`
  - `div#rcsad_TopLeft` top 16%, h=content:0: content → ignore (heuristic, 0 ms) · ""
  - `header.header` center 13%, h=content:0: content → ignore (heuristic, 0 ms) · "In evidenza App Edizioni Locali Sezioni Servizi Più popolari"
  - `div.privacy-cp-wall` full 100%, h=cookie_consent:1: cookie_consent → hide (heuristic, 2 ms) · "Rifiuta e abbonati Il Corriere della Sera si impegna quotidianamente a fornirti informazio"

### nu.nl

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 0 candidates, 10 detector scans (max 7.2 ms, total 15 ms), first action at – ms, screenshot `shots/nu.nl-baseline-first.png`
- **rules**: 0 requests blocked, 184 site hide rules + 19 generic, 3 candidates, 11 detector scans (max 12 ms, total 66 ms), first action at – ms, screenshot `shots/nu.nl-rules-first.png`
  - `div#message` full 100%, h=content:0: content → ignore (backdrop, 0 ms) · ""
  - `header.app-header` top 10%, h=content:0: content → ignore (chrome, 0 ms) · "Voorpagina Net binnen Voor jou Meer Voorpagina Net binnen Binnenland Buitenland Politiek V"
  - `header.app-header` top 10%, h=content:0: cached site rule, applied in the page · "Voorpagina Net binnen Voor jou Meer Voorpagina Net binnen Binnenland Buitenland Politiek V"
  - autoconsent top: optOutResult dpgmedia-nl → true
  - autoconsent top: autoconsentDone dpgmedia-nl

### onet

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 4 candidates, 30 detector scans (max 34.1 ms, total 112 ms), first action at – ms, screenshot `shots/onet-baseline-first.png`
  - `header.Header_headerWrapper__UJp_v.Header_headerWrapperlist__xIuox` top 7%, h=content:0: observed · "SUBSKRYBUJ"
  - `div > nav` bottom 7%, h=content:0: observed · "Premium Trening Umysłu Sport Poczta"
  - `div.cmp-popup_popup.hide_amp` full 100%, h=cookie_consent:1: observed · "Szanowna Użytkowniczko, Szanowny Użytkowniku, Zanim klikniesz którykolwiek przycisk prosim"
  - `div#rasp_cmp` bottom 60%, h=cookie_consent:1: observed · "Szanowna Użytkowniczko, Szanowny Użytkowniku, Zanim klikniesz którykolwiek przycisk prosim"
  - still visible: `div.cmp-popup_popup.show_amp` cookie_consent:1 "Szanowna Użytkowniczko, Szanowny Użytkowniku, Zanim klikniesz którykolwiek przycisk prosim"
- **rules**: 2 requests blocked, 185 site hide rules + 3 generic, 2 candidates, 25 detector scans (max 3.2 ms, total 46 ms), first action at – ms, screenshot `shots/onet-rules-first.png`
  - `header.Header_headerWrapper__UJp_v.Header_headerWrapperlist__xIuox` top 7%, h=content:0: content → ignore (chrome, 0 ms) · "SUBSKRYBUJ"
  - `div > nav` bottom 7%, h=content:0: content → ignore (heuristic, 0 ms) · "Premium Trening Umysłu Sport Poczta"

### idnes

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 2 candidates, 36 detector scans (max 11.8 ms, total 188 ms), first action at – ms, screenshot `shots/idnes-baseline-first.png`
  - `a.cookie-info` bottom 3%, h=cookie_consent:0.75: observed · "Pro pokračování vyberte, jakou formou vám máme zobrazovat obsah. Než se rozhodnete, využív"
  - `div#AdTrackGenericFixedMobile` bottom 36%, h=content:0: observed · "Asfaltová směs studená na celoroční opravy cest (beton, asfaltobe… Reklama Reklama"
  - still visible: `a.cookie-info` cookie_consent:0.75 "Pro pokračování vyberte, jakou formou vám máme zobrazovat obsah. Než se rozhodnete, využív"
- **rules**: 0 requests blocked, 185 site hide rules + 3 generic, 1 candidates, 35 detector scans (max 17.2 ms, total 192 ms), first action at – ms, screenshot `shots/idnes-rules-first.png`
  - `div#AdTrackGenericFixedMobile` bottom 18%, h=content:0: content → ignore (heuristic, 0 ms) · "Asfaltová směs studená na celoroční opravy cest (beton, asfaltobe… Reklama Reklama"

### yahoo

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 2 candidates, 38 detector scans (max 37 ms, total 259 ms), first action at – ms, screenshot `shots/yahoo-baseline-first.png`
  - `header#module-uh` top 11%, h=content:0: observed · "Skip to features bar Skip to left column Skip to main Skip to right column Search the web"
  - `div.bottom-0.flex.md\:hidden` bottom 7%, h=content:0: observed · ""
- **rules**: 10 requests blocked, 190 site hide rules + 10 generic, 1 candidates, 36 detector scans (max 6.7 ms, total 119 ms), first action at – ms, screenshot `shots/yahoo-rules-first.png`
  - `header#module-uh` top 11%, h=content:0: content → ignore (chrome, 0 ms) · "Skip to features bar Skip to left column Skip to main Skip to right column Search the web"

### cnn

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 2 candidates, 16 detector scans (max 28.9 ms, total 147 ms), first action at – ms, screenshot `shots/cnn-baseline-first.png`
  - `body > div` full 100%, h=cookie_consent:1: observed · "Legal Terms and Privacy By clicking "Agree", you agree to the Terms of Use and to the coll"
  - `div > div[role="dialog"]` center 57%, h=cookie_consent:1: observed · "Legal Terms and Privacy By clicking "Agree", you agree to the Terms of Use and to the coll"
  - still visible: `body > div` cookie_consent:1 "Legal Terms and Privacy By clicking "Agree", you agree to the Terms of Use and to the coll"
- **rules**: 0 requests blocked, 190 site hide rules + 15 generic, 1 candidates, 16 detector scans (max 62.5 ms, total 168 ms), first action at 1775 ms, screenshot `shots/cnn-rules-first.png`
  - `body > div` full 100%, h=cookie_consent:1: cookie_consent → hide (heuristic, 0 ms) · "Legal Terms and Privacy By clicking "Agree", you agree to the Terms of Use and to the coll"

### nytimes

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 0 candidates, 7 detector scans (max 0.7 ms, total 3 ms), first action at – ms, screenshot `shots/nytimes-baseline-first.png`
- **rules**: 0 requests blocked, 190 site hide rules + 0 generic, 0 candidates, 91 detector scans (max 1 ms, total 45 ms), first action at – ms, screenshot `shots/nytimes-rules-first.png`

### washingtonpost

- **baseline**: error: page.goto: Timeout 30000ms exceeded.
- **rules**: error: page.goto: Timeout 30000ms exceeded.

### forbes

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 0 candidates, 7 detector scans (max 0.7 ms, total 4 ms), first action at – ms, screenshot `shots/forbes-baseline-first.png`
- **rules**: 0 requests blocked, 190 site hide rules + 0 generic, 0 candidates, 8 detector scans (max 10.9 ms, total 14 ms), first action at – ms, screenshot `shots/forbes-rules-first.png`

### businessinsider

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 1 candidates, 31 detector scans (max 11.5 ms, total 177 ms), first action at – ms, screenshot `shots/businessinsider-baseline-first.png`
  - `sticky-footer-ad.active.component.has-ad` bottom 8%, h=content:0: observed · ""
- **rules**: 1 requests blocked, 194 site hide rules + 10 generic, 1 candidates, 29 detector scans (max 27.2 ms, total 206 ms), first action at – ms, screenshot `shots/businessinsider-rules-first.png`
  - `sticky-footer-ad.active.component.has-ad` bottom 8%, h=content:0: content → ignore (heuristic, 1506 ms) · ""

### medium

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 1 candidates, 9 detector scans (max 1.6 ms, total 9 ms), first action at – ms, screenshot `shots/medium-baseline-first.png`
  - `div.ac.aj.ak` top 9%, h=content:0: observed · "Get started"
- **rules**: 0 requests blocked, 186 site hide rules + 1 generic, 0 candidates, 12 detector scans (max 1.8 ms, total 9 ms), first action at – ms, screenshot `shots/medium-rules-first.png`

### reddit

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 1 candidates, 19 detector scans (max 23.8 ms, total 172 ms), first action at – ms, screenshot `shots/reddit-baseline-first.png`
  - `reddit-header-small.end-0.fixed.nd\:visible` top 7%, h=content:0: observed · "Open menu Open App Expand search Expand user menu"
- **rules**: 0 requests blocked, 212 site hide rules + 13 generic, 1 candidates, 51 detector scans (max 26.5 ms, total 653 ms), first action at – ms, screenshot `shots/reddit-rules-first.png`
  - `reddit-header-small.end-0.fixed.nd\:visible` top 7%, h=content:0: content → ignore (chrome, 0 ms) · "Open menu Open App Expand search Expand user menu"

### quora

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 0 candidates, 9 detector scans (max 3 ms, total 11 ms), first action at – ms, screenshot `shots/quora-baseline-first.png`
- **rules**: 0 requests blocked, 189 site hide rules + 1 generic, 0 candidates, 100 detector scans (max 1.8 ms, total 62 ms), first action at – ms, screenshot `shots/quora-rules-first.png`

### pinterest

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 4 candidates, 17 detector scans (max 18.8 ms, total 57 ms), first action at – ms, screenshot `shots/pinterest-baseline-first.png`
  - `div.sticky_K` top 7%, h=content:0: observed · ""
  - `div.box_k.fixed_K` bottom 6%, h=content:0: observed · ""
  - `div.box_k.fixed_K` full 100%, h=content:0.15: observed · ""
  - `div.box_k.elevation_k.fixed_K` center 31%, h=login_wall:1: observed · "Spooky season starts here Get Pinterest app Continue with email Continue with Google Conti"
  - still visible: `div.box_k.elevation_k.fixed_K` paywall_teaser:1 "Spooky season starts here Get Pinterest app Continue with email Continue with Google Alrea"
- **rules**: 0 requests blocked, 193 site hide rules + 2 generic, 4 candidates, 20 detector scans (max 12.1 ms, total 80 ms), first action at 2646 ms, screenshot `shots/pinterest-rules-first.png`
  - `div.sticky_K` top 7%, h=content:0: content → ignore (chrome, 1 ms) · ""
  - `div.box_k.fixed_K` bottom 6%, h=content:0: content → ignore (heuristic, 0 ms) · ""
  - `div.box_k.fixed_K` full 100%, h=content:0.15: content → ignore (backdrop, 0 ms) · ""
  - `div.box_k.elevation_k.fixed_K` center 31%, h=login_wall:1: login_wall → hide (heuristic, 0 ms) · "Spooky season starts here Get Pinterest app Continue with email Continue with Google Conti"
  - still visible: `div.sticky_K` newsletter:0.65 "Log in Sign up"

### stackoverflow

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 2 candidates, 11 detector scans (max 3.9 ms, total 29 ms), first action at – ms, screenshot `shots/stackoverflow-baseline-first.png`
  - `header.js-top-bar.l0.ps-fixed` top 7%, h=newsletter:0.3: observed · "Skip to main content Log in Sign up"
  - `div#onetrust-banner-sdk` center 25%, h=cookie_consent:1: observed · "By continuing to use this website, you agree Stack Exchange can store cookies on your devi"
  - still visible: `header.js-top-bar.l0.ps-fixed` newsletter:0.3 "Skip to main content Log in Sign up"
  - still visible: `div#onetrust-banner-sdk` cookie_consent:1 "By continuing to use this website, you agree Stack Exchange can store cookies on your devi"
- **rules**: 0 requests blocked, 190 site hide rules + 6 generic, 1 candidates, 16 detector scans (max 9.8 ms, total 53 ms), first action at – ms, screenshot `shots/stackoverflow-rules-first.png`
  - `header.js-top-bar.l0.ps-fixed` top 7%, h=newsletter:0.3: content → ignore (chrome, 0 ms) · "Skip to main content Log in Sign up"
  - autoconsent top: optOutResult Onetrust → true
  - autoconsent top: autoconsentDone Onetrust
  - still visible: `header.js-top-bar.l0.ps-fixed` newsletter:0.3 "Skip to main content Log in Sign up"

### imdb

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 0 candidates, 7 detector scans (max 0.6 ms, total 3 ms), first action at – ms, screenshot `shots/imdb-baseline-first.png`
- **rules**: 0 requests blocked, 184 site hide rules + 1 generic, 0 candidates, 8 detector scans (max 9.3 ms, total 14 ms), first action at – ms, screenshot `shots/imdb-rules-first.png`

### tripadvisor

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 0 candidates, 7 detector scans (max 1.2 ms, total 4 ms), first action at – ms, screenshot `shots/tripadvisor-baseline-first.png`
- **rules**: 0 requests blocked, 186 site hide rules + 0 generic, 0 candidates, 8 detector scans (max 0.7 ms, total 3 ms), first action at – ms, screenshot `shots/tripadvisor-rules-first.png`

### healthline

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 1 candidates, 31 detector scans (max 11 ms, total 96 ms), first action at – ms, screenshot `shots/healthline-baseline-first.png`
  - `div#sticky-nav` top 6%, h=newsletter:0.25: observed · "Subscribe"
  - still visible: `div#sticky-nav` newsletter:0.25 "Subscribe"
- **rules**: 0 requests blocked, 186 site hide rules + 3 generic, 1 candidates, 32 detector scans (max 9 ms, total 99 ms), first action at – ms, screenshot `shots/healthline-rules-first.png`
  - `div#sticky-nav` top 6%, h=newsletter:0.25: content → ignore (chrome, 0 ms) · "Subscribe"
  - still visible: `div#sticky-nav` newsletter:0.25 "Subscribe"

### asos

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 0 candidates, 6 detector scans (max 0.6 ms, total 3 ms), first action at – ms, screenshot `shots/asos-baseline-first.png`
- **rules**: 0 requests blocked, 185 site hide rules + 0 generic, 0 candidates, 7 detector scans (max 0.9 ms, total 4 ms), first action at – ms, screenshot `shots/asos-rules-first.png`

### gymshark

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 4 candidates, 18 detector scans (max 14.7 ms, total 128 ms), first action at – ms, screenshot `shots/gymshark-baseline-first.png`
  - `div > div` top 15%, h=newsletter:0.5: observed · "Pause slide rotation Get 15% off when you sign up for emails Free standard shipping on ord"
  - `div.header_container__hpkiK` center 0%, h=newsletter:0: observed · "What are you looking for? Women Men Accessories Explore Accessibility Statement Help Email"
  - `div.onetrust-pc-dark-filter.ot-fade-in` full 100%, h=cookie_consent:1: observed · ""
  - `div#onetrust-banner-sdk` bottom 39%, h=cookie_consent:1: observed · "Cookies and Tracking Pixels Gymshark uses cookies and pixels to enhance user experience an"
  - still visible: `div.onetrust-pc-dark-filter.ot-fade-in` cookie_consent:1 ""
  - still visible: `div#onetrust-banner-sdk` cookie_consent:1 "Cookies and Tracking Pixels Gymshark uses cookies and pixels to enhance user experience an"
- **rules**: 0 requests blocked, 184 site hide rules + 4 generic, 2 candidates, 20 detector scans (max 16.3 ms, total 135 ms), first action at – ms, screenshot `shots/gymshark-rules-first.png`
  - `div > div` top 15%, h=newsletter:0.5: content → ignore (heuristic, 0 ms) · "Pause slide rotation Get 15% off when you sign up for emails Free standard shipping on ord"
  - `div.header_container__hpkiK` center 0%, h=newsletter:0: content → ignore (heuristic, 0 ms) · "What are you looking for? Women Men Accessories Explore Accessibility Statement Help Email"
  - autoconsent top: optOutResult Onetrust → true
  - autoconsent top: autoconsentDone Onetrust
  - still visible: `div > div` newsletter:0.55 "Pause slide rotation Get 15% off when you sign up for emails Free standard shipping on ord"

### allbirds

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 1 candidates, 72 detector scans (max 6.2 ms, total 148 ms), first action at – ms, screenshot `shots/allbirds-baseline-first.png`
  - `section.shopify-section.shopify-section-group-header-group` top 4%, h=content:0: observed · "Free ground shipping on orders over $100 Due to increased demand, orders may take up to 30"
- **rules**: 2 requests blocked, 184 site hide rules + 17 generic, 1 candidates, 69 detector scans (max 8.5 ms, total 129 ms), first action at – ms, screenshot `shots/allbirds-rules-first.png`
  - `section.shopify-section.shopify-section-group-header-group` top 4%, h=content:0: content → ignore (chrome, 0 ms) · "Free ground shipping on orders over $100 Due to increased demand, orders may take up to 30"

### weather.com

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 3 candidates, 27 detector scans (max 29 ms, total 225 ms), first action at – ms, screenshot `shots/weather.com-baseline-first.png`
  - `div.-translate-x-1\/2.fixed.left-1\/2` center 2%, h=app_install:0.8: observed · "Get the App"
  - `nav.bg-white.bottom-0.fixed` bottom 7%, h=content:0: observed · "Forecast News My Plans Radar Upgrade"
  - `section.\@container\/card.adContainer.block` center 14%, h=content:0: observed · ""
- **rules**: 2 requests blocked, 186 site hide rules + 12 generic, 2 candidates, 23 detector scans (max 48.3 ms, total 268 ms), first action at 6074 ms, screenshot `shots/weather.com-rules-first.png`
  - `div.-translate-x-1\/2.fixed.left-1\/2` center 2%, h=app_install:0.8: app_install → click "Dismiss" (heuristic, 1 ms) · "Get the App"
  - `nav.bg-white.bottom-0.fixed` bottom 7%, h=content:0: content → ignore (heuristic, 0 ms) · "Forecast News My Plans Radar Upgrade"

### wikipedia (control)

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 0 candidates, 12 detector scans (max 2.9 ms, total 23 ms), first action at – ms, screenshot `shots/wikipedia_control_-baseline-first.png`
- **rules**: 0 requests blocked, 188 site hide rules + 1 generic, 0 candidates, 13 detector scans (max 3.3 ms, total 25 ms), first action at – ms, screenshot `shots/wikipedia_control_-rules-first.png`

### github (control)

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 1 candidates, 18 detector scans (max 14.1 ms, total 102 ms), first action at – ms, screenshot `shots/github_control_-baseline-first.png`
  - `div.header-wrapper.js-header-wrapper.position-relative` top 17%, h=content:0: observed · "Skip to content GitHub Universe 2026 Join Universe virtually, from anywhere. Save your sea"
- **rules**: 0 requests blocked, 4 site hide rules + 0 generic, 1 candidates, 19 detector scans (max 20.4 ms, total 119 ms), first action at – ms, screenshot `shots/github_control_-rules-first.png`
  - `div.header-wrapper.js-header-wrapper.position-relative` top 17%, h=content:0: content → ignore (heuristic, 0 ms) · "Skip to content GitHub Universe 2026 Join Universe virtually, from anywhere. Save your sea"

### duckduckgo (control)

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 0 candidates, 6 detector scans (max 0.7 ms, total 2 ms), first action at – ms, screenshot `shots/duckduckgo_control_-baseline-first.png`
- **rules**: 0 requests blocked, 194 site hide rules + 0 generic, 0 candidates, 7 detector scans (max 1.3 ms, total 3 ms), first action at – ms, screenshot `shots/duckduckgo_control_-rules-first.png`
