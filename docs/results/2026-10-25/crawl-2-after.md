# Serene engine crawl, 2026-10-25T13:48Z

Viewport: Pixel 7 emulation, locale en-GB, wait 10000 ms. Modes: baseline, rules, serene. Model: Qwen3-0.6B-Q8_0.gguf. Autoconsent: on. Consent tools' scripts: blocked by the lists.

After each run: "annoyances" counts overlays still on screen that the detector's heuristics read as annoyances (score ≥ 0.5); "blocking" counts any overlay still covering ≥ 30% of the screen; "locked" is page scrolling. Check the screenshots: heuristics are not ground truth.

| Site | Baseline overlays | Rules: annoyances / blocking / locked | Serene: annoyances / blocking / locked | Consent rejected (autoconsent) | Model calls (ms) | 2nd visit residual / model calls |
|---|---|---|---|---|---|---|
| theguardian | cookie_consent | 0 / 0 / no | 0 / 0 / no | Sourcepoint-frame | 0 | 0 / 0 |
| bbc | none | 0 / 0 / no | 0 / 0 / no | – | 0 | 0 / 0 |
| spiegel | none | 0 / 0 / no | 0 / 0 / no | – | 0 | 0 / 0 |
| lemonde | cookie_consent | 0 / 0 / no | 0 / 0 / no | – | 0 | 0 / 0 |
| elpais | none | 0 / 0 / no | 0 / 0 / no | – | 0 | 0 / 0 |
| corriere | cookie_consent | 0 / 0 / no | 0 / 0 / no | – | 0 | 0 / 0 |
| nu.nl | none | 0 / 0 / no | 0 / 0 / no | dpgmedia-nl | 0 | 0 / 0 |
| onet | cookie_consent | 0 / 0 / no | 0 / 0 / no | – | 1 (2079) | 0 / 0 |
| idnes | cookie_consent | 0 / 1 / no | 0 / 1 / no | – | 1 (689) | 0 / 0 |
| yahoo | none | 0 / 0 / no | 0 / 0 / no | – | 0 | 0 / 0 |
| cnn | cookie_consent | 0 / 0 / no | 0 / 0 / no | – | 0 | 0 / 0 |
| nytimes | none | 0 / 0 / no | 0 / 0 / no | – | 0 | 0 / 0 |
| washingtonpost | error | error | error | – | – | – |
| forbes | none | 0 / 0 / no | 0 / 0 / no | – | 0 | 0 / 0 |
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
| gymshark | cookie_consent, cookie_consent | 1 / 0 / no | 1 / 0 / no | – | 1 (2739) | 1 / 0 |
| allbirds | none | 0 / 0 / no | 0 / 0 / no | – | 0 | 0 / 0 |
| weather.com | none | 0 / 0 / no | 0 / 0 / no | – | 1 (11600) | 0 / 0 |
| wikipedia (control) | none | 0 / 0 / no | 0 / 0 / no | – | 0 | 0 / 0 |
| github (control) | none | 0 / 0 / no | 0 / 0 / no | – | 0 | 0 / 0 |
| duckduckgo (control) | none | 0 / 1 / no | 0 / 0 / no | – | 0 | 0 / 0 |

## Exposure

How long each overlay the engine acted on was visible on the first visit before it went, from the in-page probe (sampled every 100 ms). "Appeared" is ms after navigation start. List-hidden and cached overlays never show and are not listed. Baseline shows when the annoyances first appeared in a plain browser.

| Site | Baseline: annoyance appeared (ms) | rules: appeared → visible for (ms) | serene: appeared → visible for (ms) |
|---|---|---|---|
| theguardian | cookie_consent 4314 | – | – |
| spiegel | – | – | 3652 → 89 (heuristic) |
| lemonde | cookie_consent 1612 | – | – |
| corriere | cookie_consent 3199 | – | 2657 → 0 (heuristic) |
| onet | cookie_consent 2627 | – | – |
| idnes | cookie_consent 3578 | – | – |
| cnn | cookie_consent 1497 | – | – |
| pinterest | login_wall 2624 | 2287 → 91 (heuristic) | 2376 → 0 (heuristic) |
| stackoverflow | cookie_consent 2289 | – | – |
| gymshark | newsletter 1229, cookie_consent 4929, cookie_consent 4929 | – | – |
| allbirds | cookie_consent 2749 | – | – |
| weather.com | – | 3633 → 250 (heuristic) | – |

- rules: 2 overlays acted on, visible for median 250 ms, p90 250 ms, max 250 ms.
- serene: 3 overlays acted on, visible for median 0 ms, p90 89 ms, max 89 ms.

## Decisions per site

### theguardian

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 1 candidates, 21 detector scans (max 77.5 ms, total 499 ms), first action at – ms, screenshot `shots/theguardian-baseline-first.png`
  - `body > div[role="dialog"]` bottom 44%, h=cookie_consent:1: observed · ""
  - still visible: `body > div[role="dialog"]` cookie_consent:1 ""
- **rules**: 0 requests blocked, 194 site hide rules + 1 generic, 0 candidates, 18 detector scans (max 25.7 ms, total 369 ms), first action at – ms, screenshot `shots/theguardian-rules-first.png`
  - autoconsent frame https://sourcepoint.theguardian.com: optOutResult Sourcepoint-frame → true
  - autoconsent frame https://sourcepoint.theguardian.com: autoconsentDone Sourcepoint-frame
- **serene**: 0 requests blocked, 194 site hide rules + 1 generic, 1 candidates, 15 detector scans (max 29.6 ms, total 343 ms), first action at – ms, screenshot `shots/theguardian-serene-first.png`
  - `body > div[role="dialog"]` bottom 44%, h=cookie_consent:1: content → ignore (autoconsent, 101 ms) · ""
  - autoconsent frame https://sourcepoint.theguardian.com: optOutResult Sourcepoint-frame → true
  - autoconsent frame https://sourcepoint.theguardian.com: autoconsentDone Sourcepoint-frame

### bbc

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 1 candidates, 27 detector scans (max 98.3 ms, total 165 ms), first action at – ms, screenshot `shots/bbc-baseline-first.png`
  - `header#bbc-header` top 6%, h=content:0: observed · ""
- **rules**: 2 requests blocked, 189 site hide rules + 0 generic, 1 candidates, 19 detector scans (max 4.5 ms, total 51 ms), first action at – ms, screenshot `shots/bbc-rules-first.png`
  - `header#bbc-header` top 6%, h=content:0: content → ignore (chrome, 0 ms) · ""
- **serene**: 2 requests blocked, 189 site hide rules + 0 generic, 1 candidates, 12 detector scans (max 5.9 ms, total 33 ms), first action at – ms, screenshot `shots/bbc-serene-first.png`
  - `header#bbc-header` top 6%, h=content:0: content → ignore (chrome, 0 ms) · ""

### spiegel

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 2 candidates, 15 detector scans (max 26.6 ms, total 230 ms), first action at – ms, screenshot `shots/spiegel-baseline-first.png`
  - `header.-mt-8.pointer-events-none.print\:absolute` top 6%, h=newsletter:0.25: observed · "Anmelden Menü öffnen"
  - `body > div[role="dialog"]` full 100%, h=content:0.3: observed · ""
- **rules**: 0 requests blocked, 185 site hide rules + 11 generic, 1 candidates, 15 detector scans (max 29.7 ms, total 264 ms), first action at – ms, screenshot `shots/spiegel-rules-first.png`
  - `header.-mt-8.pointer-events-none.print\:absolute` top 6%, h=newsletter:0.25: content → ignore (chrome, 0 ms) · "Anmelden Menü öffnen"
  - autoconsent top: optOutResult sourcepoint-top → true
  - autoconsent top: autoconsentDone sourcepoint-top
  - still visible: `header.-mt-8.pointer-events-none.print\:absolute` newsletter:0.25 "Anmelden Menü öffnen"
- **serene**: 0 requests blocked, 185 site hide rules + 11 generic, 2 candidates, 16 detector scans (max 30 ms, total 308 ms), first action at 3816 ms, screenshot `shots/spiegel-serene-first.png`
  - `header.-mt-8.pointer-events-none.print\:absolute` top 6%, h=newsletter:0.25: content → ignore (chrome, 0 ms) · "Anmelden Menü öffnen"
  - `body > div[role="dialog"]` full 100%, h=content:0.3: cookie_consent → hide (heuristic, 0 ms) · ""
  - still visible: `header.-mt-8.pointer-events-none.print\:absolute` newsletter:0.25 "Anmelden Menü öffnen"

### lemonde

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 1 candidates, 12 detector scans (max 7.9 ms, total 48 ms), first action at – ms, screenshot `shots/lemonde-baseline-first.png`
  - `div.gdpr-lmd-standard.gdpr-lmd-wall` full 100%, h=cookie_consent:1: observed · "Reject all cookies Le Monde and its partners may, independently or jointly, use cookies an"
  - still visible: `div.gdpr-lmd-standard.gdpr-lmd-wall` cookie_consent:1 "Reject all cookies Le Monde and its partners may, independently or jointly, use cookies an"
- **rules**: 1 requests blocked, 185 site hide rules + 5 generic, 0 candidates, 13 detector scans (max 5.5 ms, total 47 ms), first action at – ms, screenshot `shots/lemonde-rules-first.png`
- **serene**: 1 requests blocked, 185 site hide rules + 5 generic, 0 candidates, 12 detector scans (max 9.6 ms, total 46 ms), first action at – ms, screenshot `shots/lemonde-serene-first.png`

### elpais

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 0 candidates, 10 detector scans (max 0.8 ms, total 5 ms), first action at – ms, screenshot `shots/elpais-baseline-first.png`
- **rules**: 0 requests blocked, 188 site hide rules + 0 generic, 0 candidates, 10 detector scans (max 0.8 ms, total 5 ms), first action at – ms, screenshot `shots/elpais-rules-first.png`
- **serene**: 0 requests blocked, 188 site hide rules + 0 generic, 0 candidates, 11 detector scans (max 0.7 ms, total 5 ms), first action at – ms, screenshot `shots/elpais-serene-first.png`

### corriere

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 3 candidates, 34 detector scans (max 27.7 ms, total 571 ms), first action at – ms, screenshot `shots/corriere-baseline-first.png`
  - `div#rcsad_TopLeft` top 16%, h=content:0: observed · ""
  - `header.header` center 13%, h=content:0: observed · "In evidenza App Edizioni Locali Sezioni Servizi Più popolari"
  - `div.privacy-cp-wall` full 100%, h=cookie_consent:1: observed · "Rifiuta e abbonati Il Corriere della Sera si impegna quotidianamente a fornirti informazio"
  - still visible: `div.privacy-cp-wall` cookie_consent:1 "Rifiuta e abbonati Il Corriere della Sera si impegna quotidianamente a fornirti informazio"
- **rules**: 3 requests blocked, 186 site hide rules + 4 generic, 3 candidates, 19 detector scans (max 114.9 ms, total 424 ms), first action at 2798 ms, screenshot `shots/corriere-rules-first.png`
  - `div#rcsad_TopLeft` top 16%, h=content:0: content → ignore (heuristic, 1 ms) · ""
  - `header.header` center 13%, h=content:0: content → ignore (heuristic, 0 ms) · "In evidenza App Edizioni Locali Sezioni Servizi Più popolari"
  - `div.privacy-cp-wall` full 100%, h=cookie_consent:1: cookie_consent → hide (heuristic, 1 ms) · "Rifiuta e abbonati Il Corriere della Sera si impegna quotidianamente a fornirti informazio"
- **serene**: 3 requests blocked, 186 site hide rules + 4 generic, 3 candidates, 26 detector scans (max 23.2 ms, total 367 ms), first action at 2705 ms, screenshot `shots/corriere-serene-first.png`
  - `div#rcsad_TopLeft` top 16%, h=content:0: content → ignore (heuristic, 0 ms) · ""
  - `header.header` center 13%, h=content:0: content → ignore (heuristic, 0 ms) · "In evidenza App Edizioni Locali Sezioni Servizi Più popolari"
  - `div.privacy-cp-wall` full 100%, h=cookie_consent:1: cookie_consent → hide (heuristic, 1 ms) · "Rifiuta e abbonati Il Corriere della Sera si impegna quotidianamente a fornirti informazio"

### nu.nl

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 0 candidates, 10 detector scans (max 9.1 ms, total 17 ms), first action at – ms, screenshot `shots/nu.nl-baseline-first.png`
- **rules**: 0 requests blocked, 184 site hide rules + 19 generic, 3 candidates, 11 detector scans (max 16.6 ms, total 63 ms), first action at – ms, screenshot `shots/nu.nl-rules-first.png`
  - `div#message` full 100%, h=content:0: content → ignore (backdrop, 0 ms) · ""
  - `header.app-header` top 10%, h=content:0: content → ignore (chrome, 0 ms) · "Voorpagina Net binnen Voor jou Meer Voorpagina Net binnen Binnenland Buitenland Politiek V"
  - `header.app-header` top 10%, h=content:0: cached site rule, applied in the page · "Voorpagina Net binnen Voor jou Meer Voorpagina Net binnen Binnenland Buitenland Politiek V"
  - autoconsent top: optOutResult dpgmedia-nl → true
  - autoconsent top: autoconsentDone dpgmedia-nl
- **serene**: 0 requests blocked, 184 site hide rules + 19 generic, 3 candidates, 11 detector scans (max 14.2 ms, total 68 ms), first action at – ms, screenshot `shots/nu.nl-serene-first.png`
  - `div#message` full 100%, h=content:0: content → ignore (backdrop, 0 ms) · ""
  - `header.app-header` top 10%, h=content:0: content → ignore (chrome, 0 ms) · "Voorpagina Net binnen Voor jou Meer Voorpagina Net binnen Binnenland Buitenland Politiek V"
  - `header.app-header` top 10%, h=content:0: cached site rule, applied in the page · "Voorpagina Net binnen Voor jou Meer Voorpagina Net binnen Binnenland Buitenland Politiek V"
  - autoconsent top: optOutResult dpgmedia-nl → true
  - autoconsent top: autoconsentDone dpgmedia-nl

### onet

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 4 candidates, 24 detector scans (max 12.6 ms, total 74 ms), first action at – ms, screenshot `shots/onet-baseline-first.png`
  - `header.Header_headerWrapper__UJp_v.Header_headerWrapperlist__xIuox` top 7%, h=content:0: observed · "SUBSKRYBUJ"
  - `div > nav` bottom 7%, h=content:0: observed · "Premium Trening Umysłu Sport Poczta"
  - `div.cmp-popup_popup.hide_amp` full 100%, h=cookie_consent:1: observed · "Szanowna Użytkowniczko, Szanowny Użytkowniku, Zanim klikniesz którykolwiek przycisk prosim"
  - `div#rasp_cmp` bottom 60%, h=cookie_consent:1: observed · "Szanowna Użytkowniczko, Szanowny Użytkowniku, Zanim klikniesz którykolwiek przycisk prosim"
  - still visible: `div.cmp-popup_popup.show_amp` cookie_consent:1 "Szanowna Użytkowniczko, Szanowny Użytkowniku, Zanim klikniesz którykolwiek przycisk prosim"
- **rules**: 2 requests blocked, 185 site hide rules + 3 generic, 2 candidates, 27 detector scans (max 9.8 ms, total 76 ms), first action at – ms, screenshot `shots/onet-rules-first.png`
  - `header.Header_headerWrapper__UJp_v.Header_headerWrapperlist__xIuox` top 7%, h=content:0: content → ignore (chrome, 0 ms) · "SUBSKRYBUJ"
  - `div > nav` bottom 7%, h=content:0: content → ignore (heuristic, 0 ms) · "Premium Trening Umysłu Sport Poczta"
- **serene**: 2 requests blocked, 185 site hide rules + 4 generic, 2 candidates, 28 detector scans (max 6.2 ms, total 82 ms), first action at – ms, screenshot `shots/onet-serene-first.png`
  - `header.Header_headerWrapper__UJp_v.Header_headerWrapperlist__xIuox` top 7%, h=content:0: content → ignore (chrome, 0 ms) · "SUBSKRYBUJ"
  - `div > nav` bottom 7%, h=content:0: content → ignore (llm, 2080 ms) · "Premium Trening Umysłu Sport Poczta"

### idnes

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 2 candidates, 37 detector scans (max 12.1 ms, total 182 ms), first action at – ms, screenshot `shots/idnes-baseline-first.png`
  - `a.cookie-info` bottom 3%, h=cookie_consent:0.75: observed · "Pro pokračování vyberte, jakou formou vám máme zobrazovat obsah. Než se rozhodnete, využív"
  - `div#AdTrackGenericFixedMobile` bottom 13%, h=content:0: observed · "Asfaltová směs studená na celoroční opravy cest (beton, asfaltobe… Reklama Reklama"
  - still visible: `a.cookie-info` cookie_consent:0.75 "Pro pokračování vyberte, jakou formou vám máme zobrazovat obsah. Než se rozhodnete, využív"
- **rules**: 0 requests blocked, 185 site hide rules + 3 generic, 1 candidates, 40 detector scans (max 22.2 ms, total 201 ms), first action at – ms, screenshot `shots/idnes-rules-first.png`
  - `div#AdTrackGenericFixedMobile` bottom 36%, h=content:0: content → ignore (heuristic, 0 ms) · "Asfaltová směs studená na celoroční opravy cest (beton, asfaltobe… Reklama Reklama"
- **serene**: 0 requests blocked, 185 site hide rules + 3 generic, 1 candidates, 39 detector scans (max 10.3 ms, total 174 ms), first action at – ms, screenshot `shots/idnes-serene-first.png`
  - `div#AdTrackGenericFixedMobile` bottom 13%, h=content:0: content → ignore (llm, 690 ms) · "Asfaltová směs studená na celoroční opravy cest (beton, asfaltobe… Reklama Reklama"

### yahoo

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 2 candidates, 40 detector scans (max 40.7 ms, total 354 ms), first action at – ms, screenshot `shots/yahoo-baseline-first.png`
  - `header#module-uh` top 11%, h=content:0: observed · "Skip to features bar Skip to left column Skip to main Skip to right column Search the web"
  - `div.bottom-0.flex.md\:hidden` bottom 7%, h=content:0: observed · ""
- **rules**: 10 requests blocked, 190 site hide rules + 10 generic, 1 candidates, 34 detector scans (max 22.7 ms, total 134 ms), first action at – ms, screenshot `shots/yahoo-rules-first.png`
  - `header#module-uh` top 11%, h=content:0: content → ignore (chrome, 0 ms) · "Skip to features bar Skip to left column Skip to main Skip to right column Search the web"
- **serene**: 10 requests blocked, 190 site hide rules + 10 generic, 1 candidates, 35 detector scans (max 6.3 ms, total 117 ms), first action at – ms, screenshot `shots/yahoo-serene-first.png`
  - `header#module-uh` top 11%, h=content:0: content → ignore (chrome, 0 ms) · "Skip to features bar Skip to left column Skip to main Skip to right column Search the web"

### cnn

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 2 candidates, 19 detector scans (max 37.7 ms, total 151 ms), first action at – ms, screenshot `shots/cnn-baseline-first.png`
  - `body > div` full 100%, h=cookie_consent:1: observed · "Legal Terms and Privacy By clicking "Agree", you agree to the Terms of Use and to the coll"
  - `div > div[role="dialog"]` center 57%, h=cookie_consent:1: observed · "Legal Terms and Privacy By clicking "Agree", you agree to the Terms of Use and to the coll"
  - still visible: `body > div` cookie_consent:1 "Legal Terms and Privacy By clicking "Agree", you agree to the Terms of Use and to the coll"
- **rules**: 0 requests blocked, 190 site hide rules + 15 generic, 1 candidates, 17 detector scans (max 21.9 ms, total 136 ms), first action at 1643 ms, screenshot `shots/cnn-rules-first.png`
  - `body > div` full 100%, h=cookie_consent:1: cookie_consent → hide (heuristic, 0 ms) · "Legal Terms and Privacy By clicking "Agree", you agree to the Terms of Use and to the coll"
- **serene**: 0 requests blocked, 190 site hide rules + 15 generic, 1 candidates, 16 detector scans (max 17.4 ms, total 106 ms), first action at 1701 ms, screenshot `shots/cnn-serene-first.png`
  - `body > div` full 100%, h=cookie_consent:1: cookie_consent → hide (heuristic, 0 ms) · "Legal Terms and Privacy By clicking "Agree", you agree to the Terms of Use and to the coll"

### nytimes

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 0 candidates, 7 detector scans (max 0.7 ms, total 3 ms), first action at – ms, screenshot `shots/nytimes-baseline-first.png`
- **rules**: 0 requests blocked, 190 site hide rules + 0 generic, 0 candidates, 100 detector scans (max 1.6 ms, total 43 ms), first action at – ms, screenshot `shots/nytimes-rules-first.png`
- **serene**: 0 requests blocked, 190 site hide rules + 0 generic, 0 candidates, 99 detector scans (max 3 ms, total 48 ms), first action at – ms, screenshot `shots/nytimes-serene-first.png`

### washingtonpost

- **baseline**: error: page.goto: Timeout 30000ms exceeded.
- **rules**: error: page.goto: Timeout 30000ms exceeded.
- **serene**: error: page.goto: Timeout 30000ms exceeded.

### forbes

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 0 candidates, 7 detector scans (max 0.8 ms, total 3 ms), first action at – ms, screenshot `shots/forbes-baseline-first.png`
- **rules**: 2 requests blocked, 190 site hide rules + 6 generic, 2 candidates, 24 detector scans (max 19.1 ms, total 130 ms), first action at – ms, screenshot `shots/forbes-rules-first.png`
  - `div.GuKkQ.header.universal-header` top 7%, h=content:0: content → ignore (chrome, 0 ms) · ""
  - `div.GuKkQ.header.universal-header` top 7%, h=content:0: cached site rule, applied in the page · ""
  - still visible: `div.GuKkQ.header.universal-header` app_install:0.3 "Open In App 5+"
- **serene**: 2 requests blocked, 190 site hide rules + 6 generic, 2 candidates, 22 detector scans (max 27 ms, total 147 ms), first action at – ms, screenshot `shots/forbes-serene-first.png`
  - `div.GuKkQ.header.universal-header` top 7%, h=content:0: content → ignore (chrome, 0 ms) · ""
  - `div.GuKkQ.header.universal-header` top 7%, h=content:0: cached site rule, applied in the page · ""
  - still visible: `div.GuKkQ.header.universal-header` app_install:0.3 "Open In App 5+"

### businessinsider

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 1 candidates, 29 detector scans (max 11.4 ms, total 141 ms), first action at – ms, screenshot `shots/businessinsider-baseline-first.png`
  - `sticky-footer-ad.active.component.has-ad` bottom 8%, h=content:0: observed · ""
- **rules**: 1 requests blocked, 194 site hide rules + 10 generic, 0 candidates, 14 detector scans (max 15.4 ms, total 92 ms), first action at – ms, screenshot `shots/businessinsider-rules-first.png`
- **serene**: 1 requests blocked, 194 site hide rules + 10 generic, 0 candidates, 14 detector scans (max 11.9 ms, total 70 ms), first action at – ms, screenshot `shots/businessinsider-serene-first.png`

### medium

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 0 candidates, 9 detector scans (max 1.5 ms, total 8 ms), first action at – ms, screenshot `shots/medium-baseline-first.png`
- **rules**: 0 requests blocked, 186 site hide rules + 1 generic, 0 candidates, 12 detector scans (max 1.6 ms, total 11 ms), first action at – ms, screenshot `shots/medium-rules-first.png`
- **serene**: 0 requests blocked, 186 site hide rules + 1 generic, 0 candidates, 12 detector scans (max 2.1 ms, total 10 ms), first action at – ms, screenshot `shots/medium-serene-first.png`

### reddit

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 1 candidates, 18 detector scans (max 32.1 ms, total 209 ms), first action at – ms, screenshot `shots/reddit-baseline-first.png`
  - `reddit-header-small.end-0.fixed.nd\:visible` top 7%, h=content:0: observed · "Open menu Open App Expand search Expand user menu"
- **rules**: 0 requests blocked, 212 site hide rules + 13 generic, 1 candidates, 49 detector scans (max 81.4 ms, total 721 ms), first action at – ms, screenshot `shots/reddit-rules-first.png`
  - `reddit-header-small.end-0.fixed.nd\:visible` top 7%, h=content:0: content → ignore (chrome, 0 ms) · "Open menu Open App Expand search Expand user menu"
- **serene**: 0 requests blocked, 212 site hide rules + 13 generic, 1 candidates, 50 detector scans (max 27.3 ms, total 676 ms), first action at – ms, screenshot `shots/reddit-serene-first.png`
  - `reddit-header-small.end-0.fixed.nd\:visible` top 7%, h=content:0: content → ignore (chrome, 0 ms) · "Open menu Open App Expand search Expand user menu"

### quora

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 0 candidates, 11 detector scans (max 1.1 ms, total 9 ms), first action at – ms, screenshot `shots/quora-baseline-first.png`
- **rules**: 0 requests blocked, 189 site hide rules + 1 generic, 0 candidates, 102 detector scans (max 2 ms, total 65 ms), first action at – ms, screenshot `shots/quora-rules-first.png`
- **serene**: 0 requests blocked, 189 site hide rules + 1 generic, 0 candidates, 101 detector scans (max 1.6 ms, total 64 ms), first action at – ms, screenshot `shots/quora-serene-first.png`

### pinterest

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 4 candidates, 17 detector scans (max 14.8 ms, total 58 ms), first action at – ms, screenshot `shots/pinterest-baseline-first.png`
  - `div.sticky_K` top 7%, h=content:0: observed · ""
  - `div.box_k.fixed_K` bottom 6%, h=content:0: observed · ""
  - `div.box_k.fixed_K` full 100%, h=content:0.15: observed · ""
  - `div.box_k.elevation_k.fixed_K` center 31%, h=login_wall:1: observed · "Spooky season starts here Get Pinterest app Continue with email Continue with Google Conti"
  - still visible: `div.box_k.elevation_k.fixed_K` paywall_teaser:1 "Spooky season starts here Get Pinterest app Continue with email Continue with Google Alrea"
- **rules**: 0 requests blocked, 193 site hide rules + 2 generic, 4 candidates, 17 detector scans (max 11.8 ms, total 52 ms), first action at 2402 ms, screenshot `shots/pinterest-rules-first.png`
  - `div.sticky_K` top 7%, h=content:0: content → ignore (chrome, 0 ms) · ""
  - `div.box_k.fixed_K` bottom 6%, h=content:0: content → ignore (heuristic, 0 ms) · ""
  - `div.box_k.fixed_K` full 100%, h=content:0.15: content → ignore (backdrop, 0 ms) · ""
  - `div.box_k.elevation_k.fixed_K` center 31%, h=login_wall:1: login_wall → hide (heuristic, 0 ms) · "Spooky season starts here Get Pinterest app Continue with email Continue with Google Conti"
  - still visible: `div.sticky_K` newsletter:0.65 "Log in Sign up"
- **serene**: 0 requests blocked, 193 site hide rules + 2 generic, 4 candidates, 18 detector scans (max 9.3 ms, total 70 ms), first action at 2489 ms, screenshot `shots/pinterest-serene-first.png`
  - `div.sticky_K` top 7%, h=content:0: content → ignore (chrome, 0 ms) · ""
  - `div.box_k.fixed_K` bottom 6%, h=content:0: content → ignore (heuristic, 0 ms) · ""
  - `div.box_k.fixed_K` full 100%, h=content:0.15: content → ignore (backdrop, 0 ms) · ""
  - `div.box_k.elevation_k.fixed_K` center 31%, h=login_wall:1: login_wall → hide (heuristic, 0 ms) · "Spooky season starts here Get Pinterest app Continue with email Continue with Google Conti"
  - still visible: `div.sticky_K` newsletter:0.65 "Log in Sign up"

### stackoverflow

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 2 candidates, 10 detector scans (max 5.9 ms, total 32 ms), first action at – ms, screenshot `shots/stackoverflow-baseline-first.png`
  - `header.js-top-bar.l0.ps-fixed` top 7%, h=newsletter:0.3: observed · "Skip to main content Log in Sign up"
  - `div#onetrust-banner-sdk` center 25%, h=cookie_consent:1: observed · "By continuing to use this website, you agree Stack Exchange can store cookies on your devi"
  - still visible: `header.js-top-bar.l0.ps-fixed` newsletter:0.3 "Skip to main content Log in Sign up"
  - still visible: `div#onetrust-banner-sdk` cookie_consent:1 "By continuing to use this website, you agree Stack Exchange can store cookies on your devi"
- **rules**: 2 requests blocked, 190 site hide rules + 3 generic, 1 candidates, 22 detector scans (max 13.7 ms, total 69 ms), first action at – ms, screenshot `shots/stackoverflow-rules-first.png`
  - `header.js-top-bar.l0.ps-fixed` top 7%, h=newsletter:0.3: content → ignore (chrome, 0 ms) · "Skip to main content Log in Sign up"
  - still visible: `header.js-top-bar.l0.ps-fixed` newsletter:0.3 "Skip to main content Log in Sign up"
- **serene**: 2 requests blocked, 190 site hide rules + 3 generic, 1 candidates, 22 detector scans (max 13.2 ms, total 74 ms), first action at – ms, screenshot `shots/stackoverflow-serene-first.png`
  - `header.js-top-bar.l0.ps-fixed` top 7%, h=newsletter:0.3: content → ignore (chrome, 0 ms) · "Skip to main content Log in Sign up"
  - still visible: `header.js-top-bar.l0.ps-fixed` newsletter:0.3 "Skip to main content Log in Sign up"

### imdb

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 0 candidates, 7 detector scans (max 0.7 ms, total 4 ms), first action at – ms, screenshot `shots/imdb-baseline-first.png`
- **rules**: 0 requests blocked, 184 site hide rules + 1 generic, 0 candidates, 8 detector scans (max 1 ms, total 5 ms), first action at – ms, screenshot `shots/imdb-rules-first.png`
- **serene**: 0 requests blocked, 184 site hide rules + 1 generic, 0 candidates, 8 detector scans (max 1 ms, total 5 ms), first action at – ms, screenshot `shots/imdb-serene-first.png`

### tripadvisor

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 0 candidates, 7 detector scans (max 0.7 ms, total 3 ms), first action at – ms, screenshot `shots/tripadvisor-baseline-first.png`
- **rules**: 0 requests blocked, 186 site hide rules + 0 generic, 0 candidates, 8 detector scans (max 0.9 ms, total 4 ms), first action at – ms, screenshot `shots/tripadvisor-rules-first.png`
- **serene**: 0 requests blocked, 186 site hide rules + 0 generic, 0 candidates, 8 detector scans (max 1.2 ms, total 5 ms), first action at – ms, screenshot `shots/tripadvisor-serene-first.png`

### healthline

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 1 candidates, 30 detector scans (max 7.6 ms, total 79 ms), first action at – ms, screenshot `shots/healthline-baseline-first.png`
  - `div#sticky-nav` top 6%, h=newsletter:0.25: observed · "Subscribe"
  - still visible: `div#sticky-nav` newsletter:0.25 "Subscribe"
- **rules**: 2 requests blocked, 186 site hide rules + 0 generic, 1 candidates, 24 detector scans (max 7.3 ms, total 69 ms), first action at – ms, screenshot `shots/healthline-rules-first.png`
  - `div#sticky-nav` top 6%, h=newsletter:0.25: content → ignore (chrome, 0 ms) · "Subscribe"
  - still visible: `div#sticky-nav` newsletter:0.25 "Subscribe"
- **serene**: 2 requests blocked, 186 site hide rules + 0 generic, 1 candidates, 22 detector scans (max 5.9 ms, total 61 ms), first action at – ms, screenshot `shots/healthline-serene-first.png`
  - `div#sticky-nav` top 6%, h=newsletter:0.25: content → ignore (chrome, 0 ms) · "Subscribe"
  - still visible: `div#sticky-nav` newsletter:0.25 "Subscribe"

### asos

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 0 candidates, 6 detector scans (max 1.1 ms, total 3 ms), first action at – ms, screenshot `shots/asos-baseline-first.png`
- **rules**: 0 requests blocked, 185 site hide rules + 0 generic, 0 candidates, 7 detector scans (max 0.7 ms, total 3 ms), first action at – ms, screenshot `shots/asos-rules-first.png`
- **serene**: 0 requests blocked, 185 site hide rules + 0 generic, 0 candidates, 7 detector scans (max 1.4 ms, total 4 ms), first action at – ms, screenshot `shots/asos-serene-first.png`

### gymshark

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 4 candidates, 18 detector scans (max 19.1 ms, total 141 ms), first action at – ms, screenshot `shots/gymshark-baseline-first.png`
  - `div > div` top 15%, h=newsletter:0.5: observed · "Pause slide rotation Get 15% off when you sign up for emails Free standard shipping on ord"
  - `div.header_container__hpkiK` center 0%, h=newsletter:0: observed · "What are you looking for? Women Men Accessories Explore Accessibility Statement Help Email"
  - `div#onetrust-banner-sdk` bottom 39%, h=cookie_consent:1: observed · "Cookies and Tracking Pixels Gymshark uses cookies and pixels to enhance user experience an"
  - `div.onetrust-pc-dark-filter.ot-fade-in` full 100%, h=cookie_consent:1: observed · ""
  - still visible: `div.onetrust-pc-dark-filter.ot-fade-in` cookie_consent:1 ""
  - still visible: `div#onetrust-banner-sdk` cookie_consent:1 "Cookies and Tracking Pixels Gymshark uses cookies and pixels to enhance user experience an"
- **rules**: 2 requests blocked, 184 site hide rules + 0 generic, 2 candidates, 15 detector scans (max 21.2 ms, total 117 ms), first action at – ms, screenshot `shots/gymshark-rules-first.png`
  - `div > div` top 15%, h=newsletter:0.5: content → ignore (heuristic, 0 ms) · "Pause slide rotation Get 15% off when you sign up for emails Free standard shipping on ord"
  - `div.header_container__hpkiK` center 0%, h=newsletter:0: content → ignore (heuristic, 0 ms) · "What are you looking for? Women Men Accessories Explore Accessibility Statement Help Email"
  - still visible: `div > div` newsletter:0.55 "Pause slide rotation Get 15% off when you sign up for emails Free standard shipping on ord"
- **serene**: 2 requests blocked, 184 site hide rules + 0 generic, 2 candidates, 15 detector scans (max 41.2 ms, total 195 ms), first action at – ms, screenshot `shots/gymshark-serene-first.png`
  - `div.header_container__hpkiK` center 0%, h=newsletter:0: content → ignore (heuristic, 0 ms) · "What are you looking for? Women Men Accessories Explore Accessibility Statement Help Email"
  - `div > div` top 15%, h=newsletter:0.5: content → ignore (llm, 2739 ms) · "Pause slide rotation Get 15% off when you sign up for emails Free standard shipping on ord"
  - still visible: `div > div` newsletter:0.55 "Pause slide rotation Get 15% off when you sign up for emails Free standard shipping on ord"

### allbirds

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 2 candidates, 76 detector scans (max 6 ms, total 161 ms), first action at – ms, screenshot `shots/allbirds-baseline-first.png`
  - `section.shopify-section.shopify-section-group-header-group` top 4%, h=content:0: observed · "Free ground shipping on orders over $100 Due to increased demand, orders may take up to 30"
  - `section#shopify-pc__banner` bottom 44%, h=cookie_consent:1: observed · "Cookie consent We and our partners, including Shopify, use cookies and other technologies "
- **rules**: 2 requests blocked, 184 site hide rules + 17 generic, 1 candidates, 68 detector scans (max 6.2 ms, total 150 ms), first action at – ms, screenshot `shots/allbirds-rules-first.png`
  - `section.shopify-section.shopify-section-group-header-group` top 4%, h=content:0: content → ignore (chrome, 0 ms) · "Free ground shipping on orders over $100 Due to increased demand, orders may take up to 30"
- **serene**: 2 requests blocked, 184 site hide rules + 17 generic, 1 candidates, 72 detector scans (max 6.3 ms, total 151 ms), first action at – ms, screenshot `shots/allbirds-serene-first.png`
  - `section.shopify-section.shopify-section-group-header-group` top 4%, h=content:0: content → ignore (chrome, 0 ms) · "Free ground shipping on orders over $100 Due to increased demand, orders may take up to 30"

### weather.com

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 1 candidates, 32 detector scans (max 31.6 ms, total 292 ms), first action at – ms, screenshot `shots/weather.com-baseline-first.png`
  - `nav.bg-white.bottom-0.fixed` bottom 7%, h=content:0: observed · "Forecast News My Plans Radar Upgrade"
- **rules**: 6 requests blocked, 186 site hide rules + 11 generic, 2 candidates, 24 detector scans (max 45.3 ms, total 252 ms), first action at 5945 ms, screenshot `shots/weather.com-rules-first.png`
  - `div.-translate-x-1\/2.fixed.left-1\/2` center 2%, h=app_install:0.8: app_install → click "Dismiss" (heuristic, 0 ms) · "Get the App"
  - `nav.bg-white.bottom-0.fixed` bottom 7%, h=content:0: content → ignore (heuristic, 0 ms) · "Forecast News My Plans Radar Upgrade"
- **serene**: 11 requests blocked, 186 site hide rules + 11 generic, 2 candidates, 22 detector scans (max 47.8 ms, total 235 ms), first action at 21022 ms, screenshot `shots/weather.com-serene-first.png`
  - `nav.bg-white.bottom-0.fixed` bottom 7%, h=content:0: content → ignore (heuristic, 0 ms) · "Forecast News My Plans Radar Upgrade"
  - `div.-translate-x-1\/2.fixed.left-1\/2` center 2%, h=app_install:0.8: app_install → click "Dismiss" (llm, 11601 ms) · "Get the App"

### wikipedia (control)

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 0 candidates, 11 detector scans (max 15.7 ms, total 39 ms), first action at – ms, screenshot `shots/wikipedia_control_-baseline-first.png`
- **rules**: 0 requests blocked, 188 site hide rules + 1 generic, 0 candidates, 13 detector scans (max 5.5 ms, total 28 ms), first action at – ms, screenshot `shots/wikipedia_control_-rules-first.png`
- **serene**: 0 requests blocked, 188 site hide rules + 1 generic, 0 candidates, 14 detector scans (max 2.8 ms, total 28 ms), first action at – ms, screenshot `shots/wikipedia_control_-serene-first.png`

### github (control)

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 1 candidates, 18 detector scans (max 20.8 ms, total 132 ms), first action at – ms, screenshot `shots/github_control_-baseline-first.png`
  - `div.header-wrapper.js-header-wrapper.position-relative` top 17%, h=content:0: observed · "Skip to content GitHub Universe 2026 Join Universe virtually, from anywhere. Save your sea"
- **rules**: 0 requests blocked, 4 site hide rules + 0 generic, 1 candidates, 18 detector scans (max 11.7 ms, total 95 ms), first action at – ms, screenshot `shots/github_control_-rules-first.png`
  - `div.header-wrapper.js-header-wrapper.position-relative` top 17%, h=content:0: content → ignore (heuristic, 0 ms) · "Skip to content GitHub Universe 2026 Join Universe virtually, from anywhere. Save your sea"
- **serene**: 0 requests blocked, 4 site hide rules + 0 generic, 1 candidates, 17 detector scans (max 9.3 ms, total 74 ms), first action at – ms, screenshot `shots/github_control_-serene-first.png`
  - `div.header-wrapper.js-header-wrapper.position-relative` top 17%, h=content:0: content → ignore (heuristic, 0 ms) · "Skip to content GitHub Universe 2026 Join Universe virtually, from anywhere. Save your sea"

### duckduckgo (control)

- **baseline**: 0 requests blocked, 0 site hide rules + 0 generic, 2 candidates, 11 detector scans (max 2.1 ms, total 16 ms), first action at – ms, screenshot `shots/duckduckgo_control_-baseline-first.png`
  - `div > div` center 33%, h=content:0: observed · "Take back your privacy! Private Search Tracker Blocking Fewer Ads & Pop-Ups Get DuckDuckGo"
  - `div > div` center 33%, h=content:0: observed · "Take back your privacy! Private Search Tracker Blocking Fewer Ads & Pop-Ups Get DuckDuckGo"
- **rules**: 0 requests blocked, 194 site hide rules + 0 generic, 1 candidates, 8 detector scans (max 1.9 ms, total 9 ms), first action at – ms, screenshot `shots/duckduckgo_control_-rules-first.png`
  - `div > div` center 33%, h=content:0: content → ignore (heuristic, 0 ms) · "Take back your privacy! Private Search Tracker Blocking Fewer Ads & Pop-Ups Get DuckDuckGo"
- **serene**: 0 requests blocked, 194 site hide rules + 0 generic, 0 candidates, 7 detector scans (max 0.5 ms, total 2 ms), first action at – ms, screenshot `shots/duckduckgo_control_-serene-first.png`
