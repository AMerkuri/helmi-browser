# Serene engine crawl, 2026-10-25T13:21Z

Viewport: Pixel 7 emulation, locale en-GB, wait 10000 ms. Modes: baseline, rules, serene. Model: Qwen3-0.6B-Q8_0.gguf. Autoconsent: on.

After each run: "annoyances" counts overlays still on screen that the detector's heuristics read as annoyances (score ≥ 0.5); "blocking" counts any overlay still covering ≥ 30% of the screen; "locked" is page scrolling. Check the screenshots: heuristics are not ground truth.

| Site | Baseline overlays | Rules: annoyances / blocking / locked | Serene: annoyances / blocking / locked | Consent rejected (autoconsent) | Model calls (ms) | 2nd visit residual / model calls |
|---|---|---|---|---|---|---|
| theguardian | cookie_consent | 0 / 0 / no | 0 / 0 / no | sourcepoint-top, Sourcepoint-frame | 0 | 0 / 0 |
| bbc | none | 0 / 0 / no | 0 / 0 / no | – | 0 | 0 / 0 |
| spiegel | none | 0 / 0 / no | 0 / 0 / no | sourcepoint-top | 0 | 0 / 0 |
| lemonde | cookie_consent | 0 / 0 / no | 0 / 0 / no | – | 0 | 0 / 0 |
| elpais | none | 0 / 0 / no | 0 / 0 / no | – | 0 | 0 / 0 |
| corriere | cookie_consent | 0 / 0 / no | 0 / 0 / no | – | 0 | 0 / 0 |
| nu.nl | none | 0 / 0 / no | 0 / 0 / no | dpgmedia-nl | 0 | 0 / 0 |
| onet | cookie_consent | 0 / 0 / no | 0 / 0 / no | – | 1 (2293) | 0 / 0 |
| idnes | cookie_consent | 0 / 0 / no | 0 / 0 / no | – | 0 | 0 / 0 |
| yahoo | none | 0 / 0 / no | 0 / 0 / no | – | 0 | 0 / 0 |
| cnn | cookie_consent | 0 / 0 / no | 0 / 0 / no | – | 0 | 0 / 0 |
| nytimes | none | 0 / 0 / no | 0 / 0 / no | – | 0 | 0 / 0 |
| washingtonpost | error | error | error | – | – | – |
| forbes | cookie_consent, cookie_consent | 0 / 0 / no | 0 / 0 / no | – | 0 | 0 / 0 |
| businessinsider | none | 0 / 0 / no | 0 / 0 / no | – | 0 | 0 / 0 |
| medium | none | 0 / 0 / no | 0 / 0 / no | – | 0 | 0 / 0 |
| reddit | none | 0 / 0 / no | 0 / 0 / no | – | 0 | 0 / 0 |
| quora | none | 0 / 0 / no | 0 / 0 / no | – | 0 | 0 / 0 |
| pinterest | paywall_teaser | 1 / 0 / no | 1 / 0 / no | – | 0 | 0 / 0 |
| stackoverflow | cookie_consent | 0 / 0 / no | 0 / 0 / no | – | 0 | 0 / 0 |
| imdb | none | 0 / 0 / no | 0 / 0 / no | – | 0 | 0 / 0 |
| tripadvisor | none | 0 / 0 / no | 0 / 0 / no | – | 0 | 0 / 0 |
| healthline | none | 0 / 0 / no | 0 / 0 / no | – | 0 | 0 / 0 |
| asos | none | 0 / 0 / no | 0 / 0 / no | – | 0 | 0 / 0 |
| gymshark | cookie_consent, cookie_consent | 1 / 0 / no | 1 / 0 / no | – | 1 (2765) | 1 / 0 |
| allbirds | none | 0 / 0 / no | 0 / 0 / no | – | 0 | 0 / 0 |
| weather.com | none | 0 / 0 / no | 0 / 0 / no | – | 1 (7185) | 0 / 0 |
| wikipedia (control) | none | 0 / 0 / no | 0 / 0 / no | – | 0 | 0 / 0 |
| github (control) | none | 0 / 0 / no | 0 / 0 / no | – | 0 | 0 / 0 |
| duckduckgo (control) | none | 0 / 0 / no | 0 / 1 / no | – | 0 | 0 / 0 |

## Exposure

How long each overlay the engine acted on was visible on the first visit before it went, from the in-page probe (sampled every 100 ms). "Appeared" is ms after navigation start. List-hidden and cached overlays never show and are not listed. Baseline shows when the annoyances first appeared in a plain browser.

| Site | Baseline: annoyance appeared (ms) | rules: appeared → visible for (ms) | serene: appeared → visible for (ms) |
|---|---|---|---|
| theguardian | cookie_consent 4308 | – | – |
| lemonde | cookie_consent 1845 | – | – |
| elpais | – | – | 4622 → 96 (heuristic) |
| corriere | cookie_consent 4713 | 3982 → 1440 (heuristic) | 3303 → 0 (heuristic) |
| onet | cookie_consent 2775 | – | – |
| idnes | cookie_consent 4263 | – | – |
| cnn | cookie_consent 1762 | 1457 → 0 (heuristic) | 1562 → 0 (heuristic) |
| forbes | cookie_consent 3408, cookie_consent 3789 | – | – |
| pinterest | login_wall 3007 | 2636 → 0 (heuristic) | 2456 → 173 (heuristic) |
| stackoverflow | cookie_consent 2383 | – | – |
| gymshark | newsletter 699, cookie_consent 4604, cookie_consent 4499 | – | – |
| allbirds | cookie_consent 3402 | – | – |
| weather.com | app_install 3587 | – | – |

- rules: 3 overlays acted on, visible for median 0 ms, p90 1440 ms, max 1440 ms.
- serene: 4 overlays acted on, visible for median 96 ms, p90 173 ms, max 173 ms.

## Decisions per site

### theguardian

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 1 candidates, 10 detector scans (max 33.2 ms), first action at – ms, screenshot `shots/theguardian-baseline-first.png`
  - `body > div[role="dialog"]` bottom 44%, h=cookie_consent:1: observed · ""
  - still visible: `body > div[role="dialog"]` cookie_consent:1 ""
- **rules**: 0 requests blocked, 194 site hide rules + 1 generic, 0 candidates, 6 detector scans (max 34.1 ms), first action at – ms, screenshot `shots/theguardian-rules-first.png`
  - autoconsent top: optOutResult sourcepoint-top → true
  - autoconsent top: autoconsentDone sourcepoint-top
  - autoconsent frame https://sourcepoint.theguardian.com: optOutResult Sourcepoint-frame → true
  - autoconsent frame https://sourcepoint.theguardian.com: autoconsentDone Sourcepoint-frame
- **serene**: 0 requests blocked, 194 site hide rules + 1 generic, 0 candidates, 7 detector scans (max 40 ms), first action at – ms, screenshot `shots/theguardian-serene-first.png`
  - autoconsent top: optOutResult sourcepoint-top → true
  - autoconsent top: autoconsentDone sourcepoint-top
  - autoconsent frame https://sourcepoint.theguardian.com: optOutResult Sourcepoint-frame → true
  - autoconsent frame https://sourcepoint.theguardian.com: autoconsentDone Sourcepoint-frame

### bbc

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 1 candidates, 12 detector scans (max 100.7 ms), first action at – ms, screenshot `shots/bbc-baseline-first.png`
  - `header#bbc-header` top 6%, h=content:0: observed · ""
- **rules**: 2 requests blocked, 189 site hide rules + 0 generic, 1 candidates, 11 detector scans (max 5.3 ms), first action at – ms, screenshot `shots/bbc-rules-first.png`
  - `header#bbc-header` top 6%, h=content:0: content → ignore (chrome, 1 ms) · ""
- **serene**: 2 requests blocked, 189 site hide rules + 0 generic, 1 candidates, 11 detector scans (max 8.7 ms), first action at – ms, screenshot `shots/bbc-serene-first.png`
  - `header#bbc-header` top 6%, h=content:0: content → ignore (chrome, 0 ms) · ""

### spiegel

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 2 candidates, 9 detector scans (max 36.7 ms), first action at – ms, screenshot `shots/spiegel-baseline-first.png`
  - `header.-mt-8.pointer-events-none.print\:absolute` top 6%, h=newsletter:0.25: observed · "Anmelden Menü öffnen"
  - `body > div[role="dialog"]` full 100%, h=content:0.3: observed · ""
- **rules**: 0 requests blocked, 185 site hide rules + 11 generic, 1 candidates, 12 detector scans (max 83.4 ms), first action at – ms, screenshot `shots/spiegel-rules-first.png`
  - `header.-mt-8.pointer-events-none.print\:absolute` top 6%, h=newsletter:0.25: content → ignore (chrome, 0 ms) · "Anmelden Menü öffnen"
  - autoconsent top: optOutResult sourcepoint-top → true
  - autoconsent top: autoconsentDone sourcepoint-top
  - still visible: `header.-mt-8.pointer-events-none.print\:absolute` newsletter:0.25 "Anmelden Menü öffnen"
- **serene**: 0 requests blocked, 185 site hide rules + 11 generic, 1 candidates, 11 detector scans (max 92.4 ms), first action at – ms, screenshot `shots/spiegel-serene-first.png`
  - `header.-mt-8.pointer-events-none.print\:absolute` top 6%, h=newsletter:0.25: content → ignore (chrome, 0 ms) · "Anmelden Menü öffnen"
  - autoconsent top: optOutResult sourcepoint-top → true
  - autoconsent top: autoconsentDone sourcepoint-top
  - still visible: `header.-mt-8.pointer-events-none.print\:absolute` newsletter:0.25 "Anmelden Menü öffnen"

### lemonde

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 1 candidates, 7 detector scans (max 7.8 ms), first action at – ms, screenshot `shots/lemonde-baseline-first.png`
  - `div.gdpr-lmd-standard.gdpr-lmd-wall` full 100%, h=cookie_consent:1: observed · "Reject all cookies Le Monde and its partners may, independently or jointly, use cookies an"
  - still visible: `div.gdpr-lmd-standard.gdpr-lmd-wall` cookie_consent:1 "Reject all cookies Le Monde and its partners may, independently or jointly, use cookies an"
- **rules**: 1 requests blocked, 185 site hide rules + 5 generic, 0 candidates, 8 detector scans (max 22.1 ms), first action at – ms, screenshot `shots/lemonde-rules-first.png`
- **serene**: 1 requests blocked, 185 site hide rules + 5 generic, 0 candidates, 8 detector scans (max 6 ms), first action at – ms, screenshot `shots/lemonde-serene-first.png`

### elpais

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 0 candidates, 7 detector scans (max 0.8 ms), first action at – ms, screenshot `shots/elpais-baseline-first.png`
- **rules**: 0 requests blocked, 188 site hide rules + 0 generic, 0 candidates, 8 detector scans (max 0.7 ms), first action at – ms, screenshot `shots/elpais-rules-first.png`
- **serene**: 5 requests blocked, 188 site hide rules + 4 generic, 2 candidates, 25 detector scans (max 33.7 ms), first action at 4815 ms, screenshot `shots/elpais-serene-first.png`
  - `div.ad.ad-giga.ad-giga-1` top 12%, h=content:0: content → ignore (chrome, 0 ms) · ""
  - `div#pmConsentWall` full 100%, h=cookie_consent:1: cookie_consent → hide (heuristic, 11 ms) · "It offers the following browsing modes Free browsing by accepting cookies This will involv"

### corriere

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 3 candidates, 15 detector scans (max 39.5 ms), first action at – ms, screenshot `shots/corriere-baseline-first.png`
  - `div#rcsad_TopLeft` top 16%, h=content:0: observed · ""
  - `header.header` center 13%, h=content:0: observed · "In evidenza App Edizioni Locali Sezioni Servizi Più popolari"
  - `div.privacy-cp-wall` full 100%, h=cookie_consent:1: observed · "Rifiuta e abbonati Il Corriere della Sera si impegna quotidianamente a fornirti informazio"
  - still visible: `div.privacy-cp-wall` cookie_consent:1 "Rifiuta e abbonati Il Corriere della Sera si impegna quotidianamente a fornirti informazio"
- **rules**: 3 requests blocked, 186 site hide rules + 4 generic, 3 candidates, 9 detector scans (max 42.7 ms), first action at 5508 ms, screenshot `shots/corriere-rules-first.png`
  - `div#rcsad_TopLeft` top 16%, h=content:0: content → ignore (heuristic, 0 ms) · ""
  - `header.header` center 13%, h=content:0: content → ignore (heuristic, 0 ms) · "In evidenza App Edizioni Locali Sezioni Servizi Più popolari"
  - `div.privacy-cp-wall` full 100%, h=cookie_consent:1: cookie_consent → hide (heuristic, 1 ms) · "Rifiuta e abbonati Il Corriere della Sera si impegna quotidianamente a fornirti informazio"
- **serene**: 3 requests blocked, 186 site hide rules + 4 generic, 3 candidates, 9 detector scans (max 20.5 ms), first action at 3405 ms, screenshot `shots/corriere-serene-first.png`
  - `div#rcsad_TopLeft` top 16%, h=content:0: content → ignore (heuristic, 0 ms) · ""
  - `header.header` center 13%, h=content:0: content → ignore (heuristic, 0 ms) · "In evidenza App Edizioni Locali Sezioni Servizi Più popolari"
  - `div.privacy-cp-wall` full 100%, h=cookie_consent:1: cookie_consent → hide (heuristic, 0 ms) · "Rifiuta e abbonati Il Corriere della Sera si impegna quotidianamente a fornirti informazio"

### nu.nl

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 0 candidates, 7 detector scans (max 1.2 ms), first action at – ms, screenshot `shots/nu.nl-baseline-first.png`
- **rules**: 0 requests blocked, 184 site hide rules + 19 generic, 3 candidates, 6 detector scans (max 13.1 ms), first action at – ms, screenshot `shots/nu.nl-rules-first.png`
  - `div#message` full 100%, h=content:0: content → ignore (backdrop, 0 ms) · ""
  - `header.app-header` top 10%, h=content:0: content → ignore (chrome, 0 ms) · "Voorpagina Net binnen Voor jou Meer Voorpagina Net binnen Binnenland Buitenland Politiek V"
  - `header.app-header` top 10%, h=content:0: cached site rule, applied in the page · "Voorpagina Net binnen Voor jou Meer Voorpagina Net binnen Binnenland Buitenland Politiek V"
  - autoconsent top: optOutResult dpgmedia-nl → true
  - autoconsent top: autoconsentDone dpgmedia-nl
- **serene**: 0 requests blocked, 184 site hide rules + 19 generic, 3 candidates, 6 detector scans (max 16.5 ms), first action at – ms, screenshot `shots/nu.nl-serene-first.png`
  - `div#message` full 100%, h=content:0: content → ignore (backdrop, 0 ms) · ""
  - `header.app-header` top 10%, h=content:0: content → ignore (chrome, 0 ms) · "Voorpagina Net binnen Voor jou Meer Voorpagina Net binnen Binnenland Buitenland Politiek V"
  - `header.app-header` top 10%, h=content:0: cached site rule, applied in the page · "Voorpagina Net binnen Voor jou Meer Voorpagina Net binnen Binnenland Buitenland Politiek V"
  - autoconsent top: optOutResult dpgmedia-nl → true
  - autoconsent top: autoconsentDone dpgmedia-nl

### onet

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 4 candidates, 13 detector scans (max 33.2 ms), first action at – ms, screenshot `shots/onet-baseline-first.png`
  - `header.Header_headerWrapper__UJp_v.Header_headerWrapperlist__xIuox` top 7%, h=content:0: observed · "SUBSKRYBUJ"
  - `div > nav` bottom 7%, h=content:0: observed · "Premium Trening Umysłu Sport Poczta"
  - `div.cmp-popup_popup.show_amp` full 100%, h=cookie_consent:1: observed · "Szanowna Użytkowniczko, Szanowny Użytkowniku, Zanim klikniesz którykolwiek przycisk prosim"
  - `div#rasp_cmp` bottom 60%, h=cookie_consent:1: observed · "Szanowna Użytkowniczko, Szanowny Użytkowniku, Zanim klikniesz którykolwiek przycisk prosim"
  - still visible: `div.cmp-popup_popup.show_amp` cookie_consent:1 "Szanowna Użytkowniczko, Szanowny Użytkowniku, Zanim klikniesz którykolwiek przycisk prosim"
- **rules**: 2 requests blocked, 185 site hide rules + 3 generic, 2 candidates, 15 detector scans (max 5.7 ms), first action at – ms, screenshot `shots/onet-rules-first.png`
  - `header.Header_headerWrapper__UJp_v.Header_headerWrapperlist__xIuox` top 7%, h=content:0: content → ignore (chrome, 0 ms) · "SUBSKRYBUJ"
  - `div > nav` bottom 7%, h=content:0: content → ignore (heuristic, 0 ms) · "Premium Trening Umysłu Sport Poczta"
- **serene**: 1 requests blocked, 185 site hide rules + 0 generic, 2 candidates, 9 detector scans (max 9.9 ms), first action at – ms, screenshot `shots/onet-serene-first.png`
  - `header.Header_headerWrapper__UJp_v.Header_headerWrapperlist__xIuox` top 7%, h=content:0: content → ignore (chrome, 0 ms) · "SUBSKRYBUJ"
  - `div > nav` bottom 7%, h=content:0: content → ignore (llm, 2294 ms) · "Premium Trening Umysłu Sport Poczta"

### idnes

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 1 candidates, 17 detector scans (max 12.1 ms), first action at – ms, screenshot `shots/idnes-baseline-first.png`
  - `a.cookie-info` bottom 12%, h=cookie_consent:0.75: observed · "Pro pokračování vyberte, jakou formou vám máme zobrazovat obsah. Než se rozhodnete, využív"
  - still visible: `a.cookie-info` cookie_consent:0.75 "Pro pokračování vyberte, jakou formou vám máme zobrazovat obsah. Než se rozhodnete, využív"
- **rules**: 1 requests blocked, 185 site hide rules + 3 generic, 0 candidates, 19 detector scans (max 8.7 ms), first action at – ms, screenshot `shots/idnes-rules-first.png`
- **serene**: 0 requests blocked, 185 site hide rules + 3 generic, 0 candidates, 17 detector scans (max 11.7 ms), first action at – ms, screenshot `shots/idnes-serene-first.png`

### yahoo

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 2 candidates, 13 detector scans (max 33 ms), first action at – ms, screenshot `shots/yahoo-baseline-first.png`
  - `header#module-uh` top 11%, h=content:0: observed · "Skip to features bar Skip to left column Skip to main Skip to right column Search the web"
  - `div.bottom-0.flex.md\:hidden` bottom 7%, h=content:0: observed · ""
- **rules**: 10 requests blocked, 190 site hide rules + 10 generic, 1 candidates, 12 detector scans (max 14.7 ms), first action at – ms, screenshot `shots/yahoo-rules-first.png`
  - `header#module-uh` top 11%, h=content:0: content → ignore (chrome, 0 ms) · "Skip to features bar Skip to left column Skip to main Skip to right column Search the web"
- **serene**: 10 requests blocked, 190 site hide rules + 10 generic, 1 candidates, 11 detector scans (max 10 ms), first action at – ms, screenshot `shots/yahoo-serene-first.png`
  - `header#module-uh` top 11%, h=content:0: content → ignore (chrome, 0 ms) · "Skip to features bar Skip to left column Skip to main Skip to right column Search the web"

### cnn

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 2 candidates, 8 detector scans (max 33.6 ms), first action at – ms, screenshot `shots/cnn-baseline-first.png`
  - `body > div` full 100%, h=cookie_consent:1: observed · "Legal Terms and Privacy By clicking "Agree", you agree to the Terms of Use and to the coll"
  - `div > div[role="dialog"]` center 57%, h=cookie_consent:1: observed · "Legal Terms and Privacy By clicking "Agree", you agree to the Terms of Use and to the coll"
  - still visible: `body > div` cookie_consent:1 "Legal Terms and Privacy By clicking "Agree", you agree to the Terms of Use and to the coll"
- **rules**: 0 requests blocked, 190 site hide rules + 15 generic, 1 candidates, 10 detector scans (max 22.7 ms), first action at 1499 ms, screenshot `shots/cnn-rules-first.png`
  - `body > div` full 100%, h=cookie_consent:1: cookie_consent → hide (heuristic, 0 ms) · "Legal Terms and Privacy By clicking "Agree", you agree to the Terms of Use and to the coll"
- **serene**: 0 requests blocked, 190 site hide rules + 15 generic, 1 candidates, 9 detector scans (max 15.6 ms), first action at 1618 ms, screenshot `shots/cnn-serene-first.png`
  - `body > div` full 100%, h=cookie_consent:1: cookie_consent → hide (heuristic, 0 ms) · "Legal Terms and Privacy By clicking "Agree", you agree to the Terms of Use and to the coll"

### nytimes

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 0 candidates, 5 detector scans (max 0.6 ms), first action at – ms, screenshot `shots/nytimes-baseline-first.png`
- **rules**: 0 requests blocked, 190 site hide rules + 0 generic, 0 candidates, 26 detector scans (max 1.5 ms), first action at – ms, screenshot `shots/nytimes-rules-first.png`
- **serene**: 0 requests blocked, 190 site hide rules + 0 generic, 0 candidates, 26 detector scans (max 1 ms), first action at – ms, screenshot `shots/nytimes-serene-first.png`

### washingtonpost

- **baseline**: error: page.goto: Timeout 30000ms exceeded.
- **rules**: error: page.goto: Timeout 30000ms exceeded.
- **serene**: error: page.goto: Timeout 30000ms exceeded.

### forbes

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 3 candidates, 10 detector scans (max 18.3 ms), first action at – ms, screenshot `shots/forbes-baseline-first.png`
  - `div.GuKkQ.header.universal-header` top 7%, h=content:0: observed · ""
  - `div#ketch-consent-overlay` full 100%, h=cookie_consent:0.9: observed · ""
  - `div#ketch-banner` bottom 47%, h=cookie_consent:1: observed · "Site Technologies We, and third parties in some cases, use cookies and similar technologie"
  - still visible: `div#ketch-consent-overlay` cookie_consent:0.9 ""
  - still visible: `div#ketch-banner` cookie_consent:1 "Site Technologies We, and third parties in some cases, use cookies and similar technologie"
- **rules**: 0 requests blocked, 190 site hide rules + 0 generic, 0 candidates, 6 detector scans (max 0.9 ms), first action at – ms, screenshot `shots/forbes-rules-first.png`
- **serene**: 0 requests blocked, 190 site hide rules + 0 generic, 0 candidates, 6 detector scans (max 0.8 ms), first action at – ms, screenshot `shots/forbes-serene-first.png`

### businessinsider

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 1 candidates, 10 detector scans (max 10.9 ms), first action at – ms, screenshot `shots/businessinsider-baseline-first.png`
  - `sticky-footer-ad.active.component.has-ad` bottom 8%, h=content:0: observed · ""
- **rules**: 1 requests blocked, 194 site hide rules + 10 generic, 0 candidates, 8 detector scans (max 7 ms), first action at – ms, screenshot `shots/businessinsider-rules-first.png`
- **serene**: 1 requests blocked, 194 site hide rules + 10 generic, 0 candidates, 8 detector scans (max 10.9 ms), first action at – ms, screenshot `shots/businessinsider-serene-first.png`

### medium

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 0 candidates, 7 detector scans (max 1 ms), first action at – ms, screenshot `shots/medium-baseline-first.png`
- **rules**: 0 requests blocked, 186 site hide rules + 2 generic, 1 candidates, 8 detector scans (max 1.8 ms), first action at – ms, screenshot `shots/medium-rules-first.png`
  - `div.ac.aj.ak` top 9%, h=content:0: content → ignore (chrome, 0 ms) · "Get started"
- **serene**: 0 requests blocked, 186 site hide rules + 1 generic, 0 candidates, 8 detector scans (max 1.5 ms), first action at – ms, screenshot `shots/medium-serene-first.png`

### reddit

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 1 candidates, 10 detector scans (max 26.4 ms), first action at – ms, screenshot `shots/reddit-baseline-first.png`
  - `reddit-header-small.end-0.fixed.nd\:visible` top 7%, h=content:0: observed · "Open menu Open App Expand search Expand user menu"
- **rules**: 0 requests blocked, 212 site hide rules + 10 generic, 1 candidates, 23 detector scans (max 28.2 ms), first action at – ms, screenshot `shots/reddit-rules-first.png`
  - `reddit-header-small.end-0.fixed.nd\:visible` top 7%, h=content:0: content → ignore (chrome, 0 ms) · "Open menu Open App Expand search Expand user menu"
- **serene**: 0 requests blocked, 212 site hide rules + 13 generic, 1 candidates, 23 detector scans (max 24.1 ms), first action at – ms, screenshot `shots/reddit-serene-first.png`
  - `reddit-header-small.end-0.fixed.nd\:visible` top 7%, h=content:0: content → ignore (chrome, 0 ms) · "Open menu Open App Expand search Expand user menu"

### quora

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 0 candidates, 7 detector scans (max 1.4 ms), first action at – ms, screenshot `shots/quora-baseline-first.png`
- **rules**: 0 requests blocked, 189 site hide rules + 1 generic, 0 candidates, 28 detector scans (max 3.7 ms), first action at – ms, screenshot `shots/quora-rules-first.png`
- **serene**: 0 requests blocked, 189 site hide rules + 1 generic, 0 candidates, 27 detector scans (max 2.2 ms), first action at – ms, screenshot `shots/quora-serene-first.png`

### pinterest

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 4 candidates, 10 detector scans (max 5 ms), first action at – ms, screenshot `shots/pinterest-baseline-first.png`
  - `div.sticky_K` top 7%, h=content:0: observed · ""
  - `div.box_k.fixed_K` bottom 6%, h=content:0: observed · ""
  - `div.box_k.fixed_K` full 100%, h=content:0.15: observed · ""
  - `div.box_k.elevation_k.fixed_K` center 71%, h=login_wall:1: observed · "Spooky season starts here Get Pinterest app Continue with email Continue with Google Conti"
  - still visible: `div.box_k.elevation_k.fixed_K` paywall_teaser:1 "Spooky season starts here Get Pinterest app Continue with email Continue with Google Alrea"
- **rules**: 0 requests blocked, 193 site hide rules + 2 generic, 4 candidates, 10 detector scans (max 9.5 ms), first action at 2684 ms, screenshot `shots/pinterest-rules-first.png`
  - `div.sticky_K` top 7%, h=content:0: content → ignore (chrome, 0 ms) · ""
  - `div.box_k.fixed_K` bottom 6%, h=content:0: content → ignore (heuristic, 0 ms) · ""
  - `div.box_k.fixed_K` full 100%, h=content:0.15: content → ignore (backdrop, 0 ms) · ""
  - `div.box_k.elevation_k.fixed_K` center 31%, h=login_wall:1: login_wall → hide (heuristic, 0 ms) · "Spooky season starts here Get Pinterest app Continue with email Continue with Google Conti"
  - still visible: `div.sticky_K` newsletter:0.65 "Log in Sign up"
- **serene**: 0 requests blocked, 193 site hide rules + 2 generic, 4 candidates, 10 detector scans (max 4.1 ms), first action at 2672 ms, screenshot `shots/pinterest-serene-first.png`
  - `div.sticky_K` top 7%, h=content:0: content → ignore (chrome, 0 ms) · ""
  - `div.box_k.fixed_K` bottom 6%, h=content:0: content → ignore (heuristic, 0 ms) · ""
  - `div.box_k.fixed_K` full 100%, h=content:0.15: content → ignore (backdrop, 0 ms) · ""
  - `div.box_k.elevation_k.fixed_K` center 31%, h=login_wall:1: login_wall → hide (heuristic, 0 ms) · "Spooky season starts here Get Pinterest app Continue with email Continue with Google Conti"
  - still visible: `div.sticky_K` newsletter:0.65 "Log in Sign up"

### stackoverflow

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 2 candidates, 6 detector scans (max 4.1 ms), first action at – ms, screenshot `shots/stackoverflow-baseline-first.png`
  - `header.js-top-bar.l0.ps-fixed` top 7%, h=newsletter:0.3: observed · "Skip to main content Log in Sign up"
  - `div#onetrust-banner-sdk` center 25%, h=cookie_consent:1: observed · "By continuing to use this website, you agree Stack Exchange can store cookies on your devi"
  - still visible: `header.js-top-bar.l0.ps-fixed` newsletter:0.3 "Skip to main content Log in Sign up"
  - still visible: `div#onetrust-banner-sdk` cookie_consent:1 "By continuing to use this website, you agree Stack Exchange can store cookies on your devi"
- **rules**: 2 requests blocked, 190 site hide rules + 3 generic, 1 candidates, 10 detector scans (max 11.7 ms), first action at – ms, screenshot `shots/stackoverflow-rules-first.png`
  - `header.js-top-bar.l0.ps-fixed` top 7%, h=newsletter:0.3: content → ignore (chrome, 0 ms) · "Skip to main content Log in Sign up"
  - still visible: `header.js-top-bar.l0.ps-fixed` newsletter:0.3 "Skip to main content Log in Sign up"
- **serene**: 2 requests blocked, 190 site hide rules + 3 generic, 1 candidates, 11 detector scans (max 15 ms), first action at – ms, screenshot `shots/stackoverflow-serene-first.png`
  - `header.js-top-bar.l0.ps-fixed` top 7%, h=newsletter:0.3: content → ignore (chrome, 0 ms) · "Skip to main content Log in Sign up"
  - still visible: `header.js-top-bar.l0.ps-fixed` newsletter:0.3 "Skip to main content Log in Sign up"

### imdb

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 0 candidates, 6 detector scans (max 0.7 ms), first action at – ms, screenshot `shots/imdb-baseline-first.png`
- **rules**: 0 requests blocked, 184 site hide rules + 1 generic, 0 candidates, 7 detector scans (max 0.7 ms), first action at – ms, screenshot `shots/imdb-rules-first.png`
- **serene**: 0 requests blocked, 184 site hide rules + 1 generic, 0 candidates, 7 detector scans (max 0.8 ms), first action at – ms, screenshot `shots/imdb-serene-first.png`

### tripadvisor

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 0 candidates, 5 detector scans (max 0.8 ms), first action at – ms, screenshot `shots/tripadvisor-baseline-first.png`
- **rules**: 0 requests blocked, 186 site hide rules + 0 generic, 0 candidates, 6 detector scans (max 0.8 ms), first action at – ms, screenshot `shots/tripadvisor-rules-first.png`
- **serene**: 0 requests blocked, 186 site hide rules + 0 generic, 0 candidates, 6 detector scans (max 0.8 ms), first action at – ms, screenshot `shots/tripadvisor-serene-first.png`

### healthline

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 1 candidates, 10 detector scans (max 14.9 ms), first action at – ms, screenshot `shots/healthline-baseline-first.png`
  - `div#sticky-nav` top 6%, h=newsletter:0.25: observed · "Subscribe"
  - still visible: `div#sticky-nav` newsletter:0.25 "Subscribe"
- **rules**: 3 requests blocked, 186 site hide rules + 0 generic, 1 candidates, 11 detector scans (max 8.5 ms), first action at – ms, screenshot `shots/healthline-rules-first.png`
  - `div#sticky-nav` top 6%, h=newsletter:0.25: content → ignore (chrome, 0 ms) · "Subscribe"
  - still visible: `div#sticky-nav` newsletter:0.25 "Subscribe"
- **serene**: 3 requests blocked, 186 site hide rules + 0 generic, 1 candidates, 11 detector scans (max 9.6 ms), first action at – ms, screenshot `shots/healthline-serene-first.png`
  - `div#sticky-nav` top 6%, h=newsletter:0.25: content → ignore (chrome, 0 ms) · "Subscribe"
  - still visible: `div#sticky-nav` newsletter:0.25 "Subscribe"

### asos

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 0 candidates, 5 detector scans (max 0.7 ms), first action at – ms, screenshot `shots/asos-baseline-first.png`
- **rules**: 0 requests blocked, 185 site hide rules + 0 generic, 0 candidates, 6 detector scans (max 1.3 ms), first action at – ms, screenshot `shots/asos-rules-first.png`
- **serene**: 0 requests blocked, 185 site hide rules + 0 generic, 0 candidates, 6 detector scans (max 1.2 ms), first action at – ms, screenshot `shots/asos-serene-first.png`

### gymshark

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 4 candidates, 10 detector scans (max 14.5 ms), first action at – ms, screenshot `shots/gymshark-baseline-first.png`
  - `div > div` top 15%, h=newsletter:0.5: observed · "Pause slide rotation Get 15% off when you sign up for emails Free standard shipping on ord"
  - `div.header_container__hpkiK` center 0%, h=newsletter:0: observed · "What are you looking for? Women Men Accessories Explore Accessibility Statement Help Email"
  - `div.onetrust-pc-dark-filter.ot-fade-in` full 100%, h=cookie_consent:1: observed · ""
  - `div#onetrust-banner-sdk` bottom 39%, h=cookie_consent:1: observed · "Cookies and Tracking Pixels Gymshark uses cookies and pixels to enhance user experience an"
  - still visible: `div.onetrust-pc-dark-filter.ot-fade-in` cookie_consent:1 ""
  - still visible: `div#onetrust-banner-sdk` cookie_consent:1 "Cookies and Tracking Pixels Gymshark uses cookies and pixels to enhance user experience an"
- **rules**: 2 requests blocked, 184 site hide rules + 0 generic, 2 candidates, 11 detector scans (max 16.1 ms), first action at – ms, screenshot `shots/gymshark-rules-first.png`
  - `div > div` top 15%, h=newsletter:0.5: content → ignore (heuristic, 0 ms) · "Pause slide rotation Get 15% off when you sign up for emails Free standard shipping on ord"
  - `div.header_container__hpkiK` center 0%, h=newsletter:0: content → ignore (heuristic, 0 ms) · "What are you looking for? Women Men Accessories Explore Accessibility Statement Help Email"
  - still visible: `div > div` newsletter:0.55 "Pause slide rotation Get 15% off when you sign up for emails Free standard shipping on ord"
- **serene**: 2 requests blocked, 184 site hide rules + 0 generic, 2 candidates, 11 detector scans (max 16.8 ms), first action at – ms, screenshot `shots/gymshark-serene-first.png`
  - `div.header_container__hpkiK` center 0%, h=newsletter:0: content → ignore (heuristic, 0 ms) · "What are you looking for? Women Men Accessories Explore Accessibility Statement Help Email"
  - `div > div` top 15%, h=newsletter:0.5: content → ignore (llm, 2766 ms) · "Pause slide rotation Get 15% off when you sign up for emails Free standard shipping on ord"
  - still visible: `div > div` newsletter:0.55 "Pause slide rotation Get 15% off when you sign up for emails Free standard shipping on ord"

### allbirds

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 2 candidates, 7 detector scans (max 3 ms), first action at – ms, screenshot `shots/allbirds-baseline-first.png`
  - `section.shopify-section.shopify-section-group-header-group` top 4%, h=content:0: observed · "Free ground shipping on orders over $100 Due to increased demand, orders may take up to 30"
  - `section#shopify-pc__banner` bottom 44%, h=cookie_consent:1: observed · "Cookie consent We and our partners, including Shopify, use cookies and other technologies "
- **rules**: 2 requests blocked, 184 site hide rules + 17 generic, 1 candidates, 8 detector scans (max 5.5 ms), first action at – ms, screenshot `shots/allbirds-rules-first.png`
  - `section.shopify-section.shopify-section-group-header-group` top 4%, h=content:0: content → ignore (chrome, 0 ms) · "Free ground shipping on orders over $100 Due to increased demand, orders may take up to 30"
- **serene**: 0 requests blocked, 184 site hide rules + 1 generic, 0 candidates, 6 detector scans (max 0.9 ms), first action at – ms, screenshot `shots/allbirds-serene-first.png`

### weather.com

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 2 candidates, 9 detector scans (max 33.9 ms), first action at – ms, screenshot `shots/weather.com-baseline-first.png`
  - `nav.bg-white.bottom-0.fixed` bottom 7%, h=content:0: observed · "Forecast News My Plans Radar Upgrade"
  - `div.-translate-x-1\/2.fixed.left-1\/2` center 2%, h=app_install:0.8: observed · "Get the App"
- **rules**: 6 requests blocked, 186 site hide rules + 11 generic, 2 candidates, 9 detector scans (max 20.9 ms), first action at 4448 ms, screenshot `shots/weather.com-rules-first.png`
  - `div.-translate-x-1\/2.fixed.left-1\/2` center 2%, h=app_install:0.8: app_install → click "Dismiss" (heuristic, 0 ms) · "Get the App"
  - `nav.bg-white.bottom-0.fixed` bottom 7%, h=content:0: content → ignore (heuristic, 0 ms) · "Forecast News My Plans Radar Upgrade"
- **serene**: 8 requests blocked, 186 site hide rules + 11 generic, 3 candidates, 8 detector scans (max 16.2 ms), first action at 12826 ms, screenshot `shots/weather.com-serene-first.png`
  - `nav.bg-white.bottom-0.fixed` bottom 7%, h=content:0: content → ignore (heuristic, 0 ms) · "Forecast News My Plans Radar Upgrade"
  - `div.-translate-x-1\/2.fixed.left-1\/2` center 2%, h=app_install:0.8: app_install → click "Dismiss" (llm, 7185 ms) · "Get the App"
  - `section.\@container\/card.adContainer.block` center 14%, h=content:0: content → ignore (heuristic, 1520 ms) · ""

### wikipedia (control)

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 0 candidates, 6 detector scans (max 2.1 ms), first action at – ms, screenshot `shots/wikipedia_control_-baseline-first.png`
- **rules**: 0 requests blocked, 188 site hide rules + 1 generic, 0 candidates, 8 detector scans (max 3.1 ms), first action at – ms, screenshot `shots/wikipedia_control_-rules-first.png`
- **serene**: 0 requests blocked, 188 site hide rules + 1 generic, 0 candidates, 8 detector scans (max 3.2 ms), first action at – ms, screenshot `shots/wikipedia_control_-serene-first.png`

### github (control)

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 1 candidates, 7 detector scans (max 9 ms), first action at – ms, screenshot `shots/github_control_-baseline-first.png`
  - `div.header-wrapper.js-header-wrapper.position-relative` top 17%, h=content:0: observed · "Skip to content GitHub Universe 2026 Join Universe virtually, from anywhere. Save your sea"
- **rules**: 0 requests blocked, 4 site hide rules + 0 generic, 1 candidates, 7 detector scans (max 10.7 ms), first action at – ms, screenshot `shots/github_control_-rules-first.png`
  - `div.header-wrapper.js-header-wrapper.position-relative` top 17%, h=content:0: content → ignore (heuristic, 0 ms) · "Skip to content GitHub Universe 2026 Join Universe virtually, from anywhere. Save your sea"
- **serene**: 0 requests blocked, 4 site hide rules + 0 generic, 1 candidates, 7 detector scans (max 11.4 ms), first action at – ms, screenshot `shots/github_control_-serene-first.png`
  - `div.header-wrapper.js-header-wrapper.position-relative` top 17%, h=content:0: content → ignore (heuristic, 0 ms) · "Skip to content GitHub Universe 2026 Join Universe virtually, from anywhere. Save your sea"

### duckduckgo (control)

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 1 candidates, 5 detector scans (max 2.1 ms), first action at – ms, screenshot `shots/duckduckgo_control_-baseline-first.png`
  - `div > div` center 33%, h=content:0: observed · "Take back your privacy! Private Search Tracker Blocking Fewer Ads & Pop-Ups Get DuckDuckGo"
- **rules**: 0 requests blocked, 194 site hide rules + 0 generic, 0 candidates, 6 detector scans (max 1.3 ms), first action at – ms, screenshot `shots/duckduckgo_control_-rules-first.png`
- **serene**: 0 requests blocked, 194 site hide rules + 0 generic, 1 candidates, 5 detector scans (max 1.9 ms), first action at – ms, screenshot `shots/duckduckgo_control_-serene-first.png`
  - `div > div` center 33%, h=content:0: content → ignore (heuristic, 0 ms) · "Take back your privacy! Private Search Tracker Blocking Fewer Ads & Pop-Ups Get DuckDuckGo"
