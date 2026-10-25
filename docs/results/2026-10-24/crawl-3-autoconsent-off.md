# Serene engine crawl, 2026-10-24T22:55Z

Viewport: Pixel 7 emulation, locale en-GB, wait 10000 ms. Modes: rules, serene. Model: Qwen3-0.6B-Q8_0.gguf. Autoconsent: off.

After each run: "annoyances" counts overlays still on screen that the detector's heuristics read as annoyances (score ≥ 0.5); "blocking" counts any overlay still covering ≥ 30% of the screen; "locked" is page scrolling. Check the screenshots: heuristics are not ground truth.

| Site | Baseline overlays | Rules: annoyances / blocking / locked | Serene: annoyances / blocking / locked | Consent rejected (autoconsent) | Model calls (ms) | 2nd visit residual / model calls |
|---|---|---|---|---|---|---|
| theguardian | – | 0 / 0 / no | 0 / 0 / no | – | 0 | 0 / 0 |
| bbc | – | 0 / 0 / no | 0 / 0 / no | – | 0 | 0 / 0 |
| spiegel | – | 0 / 0 / no | 0 / 0 / no | – | 0 | 0 / 0 |
| lemonde | – | 0 / 0 / no | 0 / 0 / no | – | 0 | 0 / 0 |
| elpais | – | 0 / 0 / no | 0 / 0 / no | – | 0 | 0 / 0 |
| corriere | – | 0 / 0 / no | 0 / 0 / no | – | 0 | 0 / 0 |
| nu.nl | – | 0 / 0 / no | 0 / 0 / no | – | 0 | 0 / 0 |
| onet | – | 0 / 0 / no | 0 / 0 / no | – | 1 (1261) | 0 / 0 |
| idnes | – | 0 / 0 / no | 0 / 1 / no | – | 0 | 0 / 1 |
| yahoo | – | 0 / 0 / no | 0 / 0 / no | – | 0 | 0 / 0 |
| cnn | – | 0 / 0 / no | 0 / 0 / no | – | 0 | 0 / 0 |
| nytimes | – | 0 / 0 / no | 0 / 0 / no | – | 0 | 0 / 0 |
| washingtonpost | – | error | error | – | – | – |
| forbes | – | 0 / 0 / no | 0 / 0 / no | – | 0 | 0 / 0 |
| businessinsider | – | 0 / 0 / no | 0 / 0 / no | – | 0 | 0 / 0 |
| medium | – | 0 / 0 / no | 0 / 0 / no | – | 0 | 0 / 0 |
| reddit | – | 0 / 0 / no | 0 / 0 / no | – | 0 | 0 / 0 |
| quora | – | 0 / 0 / no | 0 / 0 / no | – | 0 | 0 / 0 |
| pinterest | – | 1 / 0 / no | 1 / 0 / no | – | 0 | 0 / 0 |
| stackoverflow | – | 0 / 0 / no | 0 / 0 / no | – | 0 | 0 / 0 |
| imdb | – | 0 / 0 / no | 0 / 0 / no | – | 0 | 0 / 0 |
| tripadvisor | – | 0 / 0 / no | 0 / 0 / no | – | 0 | 0 / 0 |
| healthline | – | 0 / 0 / no | 0 / 0 / no | – | 0 | 0 / 0 |
| asos | – | 0 / 0 / no | 0 / 0 / no | – | 0 | 0 / 0 |
| gymshark | – | 1 / 0 / no | 1 / 0 / no | – | 1 (2864) | 1 / 0 |
| allbirds | – | 0 / 0 / no | 0 / 0 / no | – | 0 | 0 / 0 |
| weather.com | – | 0 / 0 / no | 0 / 0 / no | – | 1 (2219) | 0 / 0 |
| wikipedia (control) | – | 0 / 0 / no | 0 / 0 / no | – | 0 | 0 / 0 |
| github (control) | – | 0 / 0 / no | 0 / 0 / no | – | 0 | 0 / 0 |
| duckduckgo (control) | – | 0 / 1 / no | 0 / 1 / no | – | 0 | 0 / 0 |

## Decisions per site

### theguardian

- **rules**: 0 requests blocked, 194 site hide rules + 1 generic, 1 candidates, 9 detector scans (max 30.5 ms), first action at 4210 ms, screenshot `shots/theguardian-rules-first.png`
  - `body > div[role="dialog"]` bottom 44%, h=cookie_consent:1: cookie_consent → click "Closer" (heuristic, 2 ms) · ""
- **serene**: 0 requests blocked, 194 site hide rules + 1 generic, 1 candidates, 10 detector scans (max 30.8 ms), first action at 3444 ms, screenshot `shots/theguardian-serene-first.png`
  - `body > div[role="dialog"]` bottom 44%, h=cookie_consent:1: cookie_consent → click "Closer" (heuristic, 1 ms) · ""

### bbc

- **rules**: 2 requests blocked, 189 site hide rules + 0 generic, 1 candidates, 9 detector scans (max 5.9 ms), first action at – ms, screenshot `shots/bbc-rules-first.png`
  - `header#bbc-header` top 6%, h=content:0: content → ignore (chrome, 0 ms) · ""
- **serene**: 2 requests blocked, 189 site hide rules + 0 generic, 1 candidates, 8 detector scans (max 5.4 ms), first action at – ms, screenshot `shots/bbc-serene-first.png`
  - `header#bbc-header` top 6%, h=content:0: content → ignore (chrome, 0 ms) · ""

### spiegel

- **rules**: 0 requests blocked, 185 site hide rules + 11 generic, 2 candidates, 10 detector scans (max 25.6 ms), first action at 4248 ms, screenshot `shots/spiegel-rules-first.png`
  - `header.-mt-8.pointer-events-none.print\:absolute` top 6%, h=newsletter:0.25: content → ignore (chrome, 0 ms) · "Anmelden Menü öffnen"
  - `body > div[role="dialog"]` full 100%, h=content:0.15: cookie_consent → hide (heuristic, 0 ms) · ""
  - still visible: `header.-mt-8.pointer-events-none.print\:absolute` newsletter:0.25 "Anmelden Menü öffnen"
- **serene**: 0 requests blocked, 185 site hide rules + 11 generic, 2 candidates, 11 detector scans (max 85 ms), first action at 3655 ms, screenshot `shots/spiegel-serene-first.png`
  - `header.-mt-8.pointer-events-none.print\:absolute` top 6%, h=newsletter:0.25: content → ignore (chrome, 0 ms) · "Anmelden Menü öffnen"
  - `body > div[role="dialog"]` full 100%, h=content:0.3: cookie_consent → hide (heuristic, 0 ms) · ""
  - still visible: `header.-mt-8.pointer-events-none.print\:absolute` newsletter:0.25 "Anmelden Menü öffnen"

### lemonde

- **rules**: 1 requests blocked, 185 site hide rules + 5 generic, 0 candidates, 7 detector scans (max 6.5 ms), first action at – ms, screenshot `shots/lemonde-rules-first.png`
- **serene**: 1 requests blocked, 185 site hide rules + 5 generic, 0 candidates, 7 detector scans (max 7.3 ms), first action at – ms, screenshot `shots/lemonde-serene-first.png`

### elpais

- **rules**: 5 requests blocked, 188 site hide rules + 4 generic, 2 candidates, 24 detector scans (max 15.7 ms), first action at 4621 ms, screenshot `shots/elpais-rules-first.png`
  - `div.ad.ad-giga.ad-giga-1` top 12%, h=content:0: content → ignore (chrome, 0 ms) · ""
  - `div#pmConsentWall` full 100%, h=cookie_consent:1: cookie_consent → hide (heuristic, 0 ms) · "It offers the following browsing modes Free browsing by accepting cookies This will involv"
- **serene**: 5 requests blocked, 188 site hide rules + 4 generic, 2 candidates, 25 detector scans (max 12.8 ms), first action at 3964 ms, screenshot `shots/elpais-serene-first.png`
  - `div.ad.ad-giga.ad-giga-1` top 12%, h=content:0: content → ignore (chrome, 0 ms) · ""
  - `div#pmConsentWall` full 100%, h=cookie_consent:1: cookie_consent → hide (heuristic, 0 ms) · "It offers the following browsing modes Free browsing by accepting cookies This will involv"

### corriere

- **rules**: 3 requests blocked, 186 site hide rules + 4 generic, 3 candidates, 9 detector scans (max 19.6 ms), first action at 3527 ms, screenshot `shots/corriere-rules-first.png`
  - `div#rcsad_TopLeft` top 16%, h=content:0: content → ignore (heuristic, 0 ms) · ""
  - `header.header` center 13%, h=content:0: content → ignore (heuristic, 0 ms) · "In evidenza App Edizioni Locali Sezioni Servizi Più popolari"
  - `div.privacy-cp-wall` full 100%, h=cookie_consent:1: cookie_consent → hide (heuristic, 0 ms) · "Rifiuta e abbonati Il Corriere della Sera si impegna quotidianamente a fornirti informazio"
- **serene**: 3 requests blocked, 186 site hide rules + 4 generic, 3 candidates, 10 detector scans (max 55 ms), first action at 3119 ms, screenshot `shots/corriere-serene-first.png`
  - `div#rcsad_TopLeft` top 16%, h=content:0: content → ignore (heuristic, 0 ms) · ""
  - `header.header` center 13%, h=content:0: content → ignore (heuristic, 0 ms) · "In evidenza App Edizioni Locali Sezioni Servizi Più popolari"
  - `div.privacy-cp-wall` full 100%, h=cookie_consent:1: cookie_consent → hide (heuristic, 0 ms) · "Rifiuta e abbonati Il Corriere della Sera si impegna quotidianamente a fornirti informazio"

### nu.nl

- **rules**: 0 requests blocked, 184 site hide rules + 3 generic, 0 candidates, 7 detector scans (max 2.6 ms), first action at – ms, screenshot `shots/nu.nl-rules-first.png`
- **serene**: 0 requests blocked, 184 site hide rules + 3 generic, 0 candidates, 7 detector scans (max 2.2 ms), first action at – ms, screenshot `shots/nu.nl-serene-first.png`

### onet

- **rules**: 2 requests blocked, 185 site hide rules + 3 generic, 2 candidates, 13 detector scans (max 9.7 ms), first action at – ms, screenshot `shots/onet-rules-first.png`
  - `header.Header_headerWrapper__UJp_v.Header_headerWrapperlist__xIuox` top 7%, h=content:0: content → ignore (chrome, 0 ms) · "SUBSKRYBUJ"
  - `div > nav` bottom 7%, h=content:0: content → ignore (heuristic, 0 ms) · "Premium Trening Umysłu Sport Poczta"
- **serene**: 2 requests blocked, 185 site hide rules + 3 generic, 2 candidates, 13 detector scans (max 16.8 ms), first action at – ms, screenshot `shots/onet-serene-first.png`
  - `header.Header_headerWrapper__UJp_v.Header_headerWrapperlist__xIuox` top 7%, h=content:0: content → ignore (chrome, 0 ms) · "SUBSKRYBUJ"
  - `div > nav` bottom 7%, h=content:0: content → ignore (llm, 1262 ms) · "Premium Trening Umysłu Sport Poczta"

### idnes

- **rules**: 1 requests blocked, 185 site hide rules + 6 generic, 0 candidates, 19 detector scans (max 13.6 ms), first action at – ms, screenshot `shots/idnes-rules-first.png`
- **serene**: 0 requests blocked, 185 site hide rules + 4 generic, 0 candidates, 18 detector scans (max 16.5 ms), first action at – ms, screenshot `shots/idnes-serene-first.png`

### yahoo

- **rules**: 10 requests blocked, 190 site hide rules + 10 generic, 1 candidates, 13 detector scans (max 9.4 ms), first action at – ms, screenshot `shots/yahoo-rules-first.png`
  - `header#module-uh` top 11%, h=content:0: content → ignore (chrome, 0 ms) · "Skip to features bar Skip to left column Skip to main Skip to right column Search the web"
- **serene**: 10 requests blocked, 190 site hide rules + 10 generic, 1 candidates, 9 detector scans (max 13.1 ms), first action at – ms, screenshot `shots/yahoo-serene-first.png`
  - `header#module-uh` top 11%, h=content:0: content → ignore (chrome, 0 ms) · "Skip to features bar Skip to left column Skip to main Skip to right column Search the web"

### cnn

- **rules**: 0 requests blocked, 190 site hide rules + 15 generic, 1 candidates, 9 detector scans (max 13.5 ms), first action at 1677 ms, screenshot `shots/cnn-rules-first.png`
  - `body > div` full 100%, h=cookie_consent:1: cookie_consent → hide (heuristic, 0 ms) · "Legal Terms and Privacy By clicking "Agree", you agree to the Terms of Use and to the coll"
- **serene**: 0 requests blocked, 190 site hide rules + 15 generic, 1 candidates, 9 detector scans (max 12.6 ms), first action at 1443 ms, screenshot `shots/cnn-serene-first.png`
  - `body > div` full 100%, h=cookie_consent:1: cookie_consent → hide (heuristic, 0 ms) · "Legal Terms and Privacy By clicking "Agree", you agree to the Terms of Use and to the coll"

### nytimes

- **rules**: 0 requests blocked, 190 site hide rules + 0 generic, 0 candidates, 5 detector scans (max 0.8 ms), first action at – ms, screenshot `shots/nytimes-rules-first.png`
- **serene**: 0 requests blocked, 190 site hide rules + 0 generic, 0 candidates, 5 detector scans (max 0.9 ms), first action at – ms, screenshot `shots/nytimes-serene-first.png`

### washingtonpost

- **rules**: error: page.goto: Timeout 30000ms exceeded.
- **serene**: error: page.goto: Timeout 30000ms exceeded.

### forbes

- **rules**: 0 requests blocked, 190 site hide rules + 0 generic, 0 candidates, 5 detector scans (max 1.1 ms), first action at – ms, screenshot `shots/forbes-rules-first.png`
- **serene**: 0 requests blocked, 190 site hide rules + 0 generic, 0 candidates, 5 detector scans (max 0.7 ms), first action at – ms, screenshot `shots/forbes-serene-first.png`

### businessinsider

- **rules**: 1 requests blocked, 194 site hide rules + 10 generic, 0 candidates, 7 detector scans (max 8.3 ms), first action at – ms, screenshot `shots/businessinsider-rules-first.png`
- **serene**: 1 requests blocked, 194 site hide rules + 10 generic, 0 candidates, 7 detector scans (max 6.5 ms), first action at – ms, screenshot `shots/businessinsider-serene-first.png`

### medium

- **rules**: 0 requests blocked, 186 site hide rules + 1 generic, 0 candidates, 7 detector scans (max 1.3 ms), first action at – ms, screenshot `shots/medium-rules-first.png`
- **serene**: 0 requests blocked, 186 site hide rules + 1 generic, 0 candidates, 7 detector scans (max 1.9 ms), first action at – ms, screenshot `shots/medium-serene-first.png`

### reddit

- **rules**: 0 requests blocked, 212 site hide rules + 13 generic, 1 candidates, 24 detector scans (max 22.8 ms), first action at – ms, screenshot `shots/reddit-rules-first.png`
  - `reddit-header-small.end-0.fixed.nd\:visible` top 7%, h=content:0: content → ignore (chrome, 0 ms) · "Open menu Open App Expand search Expand user menu"
- **serene**: 0 requests blocked, 212 site hide rules + 13 generic, 1 candidates, 24 detector scans (max 38.1 ms), first action at – ms, screenshot `shots/reddit-serene-first.png`
  - `reddit-header-small.end-0.fixed.nd\:visible` top 7%, h=content:0: content → ignore (chrome, 0 ms) · "Open menu Open App Expand search Expand user menu"

### quora

- **rules**: 0 requests blocked, 189 site hide rules + 1 generic, 0 candidates, 28 detector scans (max 1.6 ms), first action at – ms, screenshot `shots/quora-rules-first.png`
- **serene**: 0 requests blocked, 189 site hide rules + 1 generic, 0 candidates, 28 detector scans (max 1.9 ms), first action at – ms, screenshot `shots/quora-serene-first.png`

### pinterest

- **rules**: 0 requests blocked, 193 site hide rules + 2 generic, 4 candidates, 9 detector scans (max 10.3 ms), first action at 3095 ms, screenshot `shots/pinterest-rules-first.png`
  - `div.box_k.fixed_K` full 100%, h=content:0.15: content → ignore (backdrop, 0 ms) · ""
  - `div.box_k.elevation_k.fixed_K` center 77%, h=paywall_teaser:1: paywall_teaser → hide (heuristic, 0 ms) · "Spooky season starts here Get Pinterest app Continue with email Continue with Google Alrea"
  - `div.sticky_K` top 7%, h=newsletter:0.65: content → ignore (chrome, 0 ms) · "Log in Sign up"
  - `div.box_k.fixed_K` bottom 6%, h=content:0: content → ignore (heuristic, 0 ms) · ""
  - still visible: `div.sticky_K` newsletter:0.65 "Log in Sign up"
- **serene**: 0 requests blocked, 193 site hide rules + 2 generic, 4 candidates, 10 detector scans (max 17 ms), first action at 3497 ms, screenshot `shots/pinterest-serene-first.png`
  - `div.sticky_K` top 7%, h=content:0: content → ignore (chrome, 0 ms) · ""
  - `div.box_k.fixed_K` bottom 6%, h=content:0: content → ignore (heuristic, 0 ms) · ""
  - `div.box_k.fixed_K` full 100%, h=content:0.15: content → ignore (backdrop, 0 ms) · ""
  - `div.box_k.elevation_k.fixed_K` center 77%, h=paywall_teaser:1: paywall_teaser → hide (heuristic, 0 ms) · "Spooky season starts here Get Pinterest app Continue with email Continue with Google Alrea"
  - still visible: `div.sticky_K` newsletter:0.65 "Log in Sign up"

### stackoverflow

- **rules**: 2 requests blocked, 190 site hide rules + 3 generic, 1 candidates, 12 detector scans (max 13.5 ms), first action at – ms, screenshot `shots/stackoverflow-rules-first.png`
  - `header.js-top-bar.l0.ps-fixed` top 7%, h=newsletter:0.3: content → ignore (chrome, 0 ms) · "Skip to main content Log in Sign up"
  - still visible: `header.js-top-bar.l0.ps-fixed` newsletter:0.3 "Skip to main content Log in Sign up"
- **serene**: 2 requests blocked, 190 site hide rules + 3 generic, 1 candidates, 11 detector scans (max 19.6 ms), first action at – ms, screenshot `shots/stackoverflow-serene-first.png`
  - `header.js-top-bar.l0.ps-fixed` top 7%, h=newsletter:0.3: content → ignore (chrome, 0 ms) · "Skip to main content Log in Sign up"
  - still visible: `header.js-top-bar.l0.ps-fixed` newsletter:0.3 "Skip to main content Log in Sign up"

### imdb

- **rules**: 0 requests blocked, 184 site hide rules + 1 generic, 0 candidates, 6 detector scans (max 0.7 ms), first action at – ms, screenshot `shots/imdb-rules-first.png`
- **serene**: 0 requests blocked, 184 site hide rules + 1 generic, 0 candidates, 6 detector scans (max 0.6 ms), first action at – ms, screenshot `shots/imdb-serene-first.png`

### tripadvisor

- **rules**: 0 requests blocked, 186 site hide rules + 0 generic, 0 candidates, 5 detector scans (max 0.8 ms), first action at – ms, screenshot `shots/tripadvisor-rules-first.png`
- **serene**: 0 requests blocked, 186 site hide rules + 0 generic, 0 candidates, 5 detector scans (max 0.7 ms), first action at – ms, screenshot `shots/tripadvisor-serene-first.png`

### healthline

- **rules**: 2 requests blocked, 186 site hide rules + 0 generic, 1 candidates, 10 detector scans (max 12.5 ms), first action at – ms, screenshot `shots/healthline-rules-first.png`
  - `div#sticky-nav` top 6%, h=newsletter:0.25: content → ignore (chrome, 0 ms) · "Subscribe"
  - still visible: `div#sticky-nav` newsletter:0.25 "Subscribe"
- **serene**: 3 requests blocked, 186 site hide rules + 0 generic, 1 candidates, 10 detector scans (max 7.8 ms), first action at – ms, screenshot `shots/healthline-serene-first.png`
  - `div#sticky-nav` top 6%, h=newsletter:0.25: content → ignore (chrome, 0 ms) · "Subscribe"
  - still visible: `div#sticky-nav` newsletter:0.25 "Subscribe"

### asos

- **rules**: 0 requests blocked, 185 site hide rules + 0 generic, 0 candidates, 5 detector scans (max 1.9 ms), first action at – ms, screenshot `shots/asos-rules-first.png`
- **serene**: 0 requests blocked, 185 site hide rules + 0 generic, 0 candidates, 5 detector scans (max 1.6 ms), first action at – ms, screenshot `shots/asos-serene-first.png`

### gymshark

- **rules**: 2 requests blocked, 184 site hide rules + 0 generic, 2 candidates, 12 detector scans (max 27.7 ms), first action at – ms, screenshot `shots/gymshark-rules-first.png`
  - `div > div` top 15%, h=newsletter:0.5: content → ignore (heuristic, 0 ms) · "Pause slide rotation Get 15% off when you sign up for emails Free standard shipping on ord"
  - `div.header_container__hpkiK` center 0%, h=newsletter:0: content → ignore (heuristic, 0 ms) · "What are you looking for? Women Men Accessories Explore Accessibility Statement Help Email"
  - still visible: `div > div` newsletter:0.55 "Pause slide rotation Get 15% off when you sign up for emails Free standard shipping on ord"
- **serene**: 2 requests blocked, 184 site hide rules + 0 generic, 2 candidates, 10 detector scans (max 19.2 ms), first action at – ms, screenshot `shots/gymshark-serene-first.png`
  - `div.header_container__hpkiK` center 0%, h=newsletter:0: content → ignore (heuristic, 0 ms) · "What are you looking for? Women Men Accessories Explore Accessibility Statement Help Email"
  - `div > div` top 15%, h=newsletter:0.5: content → ignore (llm, 2864 ms) · "Pause slide rotation Get 15% off when you sign up for emails Free standard shipping on ord"
  - still visible: `div > div` newsletter:0.55 "Pause slide rotation Get 15% off when you sign up for emails Free standard shipping on ord"

### allbirds

- **rules**: 2 requests blocked, 184 site hide rules + 17 generic, 1 candidates, 9 detector scans (max 12.3 ms), first action at – ms, screenshot `shots/allbirds-rules-first.png`
  - `section.shopify-section.shopify-section-group-header-group` top 4%, h=content:0: content → ignore (chrome, 0 ms) · "Free ground shipping on orders over $100 Due to increased demand, orders may take up to 30"
- **serene**: 2 requests blocked, 184 site hide rules + 17 generic, 1 candidates, 9 detector scans (max 9.8 ms), first action at – ms, screenshot `shots/allbirds-serene-first.png`
  - `section.shopify-section.shopify-section-group-header-group` top 4%, h=content:0: content → ignore (chrome, 0 ms) · "Free ground shipping on orders over $100 Due to increased demand, orders may take up to 30"

### weather.com

- **rules**: 12 requests blocked, 186 site hide rules + 11 generic, 2 candidates, 9 detector scans (max 21.7 ms), first action at 4312 ms, screenshot `shots/weather.com-rules-first.png`
  - `div.-translate-x-1\/2.fixed.left-1\/2` center 2%, h=app_install:0.8: app_install → click "Dismiss" (heuristic, 0 ms) · "Get the App"
  - `nav.bg-white.bottom-0.fixed` bottom 7%, h=content:0: content → ignore (heuristic, 0 ms) · "Forecast News My Plans Radar Upgrade"
- **serene**: 7 requests blocked, 186 site hide rules + 11 generic, 2 candidates, 11 detector scans (max 28 ms), first action at 7834 ms, screenshot `shots/weather.com-serene-first.png`
  - `nav.bg-white.bottom-0.fixed` bottom 7%, h=content:0: content → ignore (heuristic, 0 ms) · "Forecast News My Plans Radar Upgrade"
  - `div.-translate-x-1\/2.fixed.left-1\/2` center 2%, h=app_install:0.8: app_install → click "Dismiss" (llm, 2220 ms) · "Get the App"

### wikipedia (control)

- **rules**: 0 requests blocked, 188 site hide rules + 1 generic, 0 candidates, 7 detector scans (max 3.3 ms), first action at – ms, screenshot `shots/wikipedia_control_-rules-first.png`
- **serene**: 0 requests blocked, 188 site hide rules + 1 generic, 0 candidates, 7 detector scans (max 4.5 ms), first action at – ms, screenshot `shots/wikipedia_control_-serene-first.png`

### github (control)

- **rules**: 0 requests blocked, 4 site hide rules + 0 generic, 1 candidates, 7 detector scans (max 10.2 ms), first action at – ms, screenshot `shots/github_control_-rules-first.png`
  - `div.header-wrapper.js-header-wrapper.position-relative` top 17%, h=content:0: content → ignore (heuristic, 0 ms) · "Skip to content GitHub Universe 2026 Join Universe virtually, from anywhere. Save your sea"
- **serene**: 0 requests blocked, 4 site hide rules + 0 generic, 1 candidates, 7 detector scans (max 13.5 ms), first action at – ms, screenshot `shots/github_control_-serene-first.png`
  - `div.header-wrapper.js-header-wrapper.position-relative` top 17%, h=content:0: content → ignore (heuristic, 0 ms) · "Skip to content GitHub Universe 2026 Join Universe virtually, from anywhere. Save your sea"

### duckduckgo (control)

- **rules**: 0 requests blocked, 194 site hide rules + 0 generic, 1 candidates, 6 detector scans (max 5.3 ms), first action at – ms, screenshot `shots/duckduckgo_control_-rules-first.png`
  - `div > div` center 33%, h=content:0: content → ignore (heuristic, 0 ms) · "Take back your privacy! Private Search Tracker Blocking Fewer Ads & Pop-Ups Get DuckDuckGo"
- **serene**: 0 requests blocked, 194 site hide rules + 0 generic, 1 candidates, 7 detector scans (max 6 ms), first action at – ms, screenshot `shots/duckduckgo_control_-serene-first.png`
  - `div > div` center 33%, h=content:0: content → ignore (heuristic, 0 ms) · "Take back your privacy! Private Search Tracker Blocking Fewer Ads & Pop-Ups Get DuckDuckGo"
