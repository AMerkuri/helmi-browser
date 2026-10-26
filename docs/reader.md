# Reader mode

**Reader mode** in the ⋮ menu shows the page's article alone, like an e-reader. It does not use the model.

## Finding the article

`engine/dist/reader.js` bundles [Mozilla Readability](https://github.com/mozilla/readability) 0.6.0 (Apache-2.0, the code behind Firefox's reader view) with `engine/src/content/reader-entry.js`. Unlike `serene.js` it does not run at document start: the app evaluates it on the loaded page when you ask, and reads `__sereneReader()`.

Before Readability sees a copy of the page, the entry script removes:

- the overlays Serene hid (`data-serene`) and fixed-position layers (cookie bars, chat bubbles, sticky headers);
- nothing else that is hidden: collapsed sections that find-in-page can open (`hidden="until-found"`, mobile Wikipedia) are opened instead, since Readability drops anything `[hidden]`. Without that, mobile Wikipedia gave 141 of 623 words.

The result is the article as HTML, with scripts, frames, forms, inline styles and event handlers stripped. Readability always finds something, so on a page that is not an article (a shop, a front page) the reader shows its best guess and the result says `article: false`.

Checked on 10 real pages on 2026-10-25 (BBC, ERR News, ERR in Estonian, the Guardian, Spiegel, Wikipedia, Paul Graham, Smashing Magazine): every one gave its article, in 35 to 85 ms on a desktop Chromium. `engine/test/reader.test.mjs` runs the extractor on hand-written pages.

## Reader view

`ui/ReaderView.kt` shows `reader/ReaderHtml.kt`'s document in a WebView of its own with JavaScript off, and the document's Content-Security-Policy (`default-src 'none'`, images and media allowed, no forms) holds even if a page tampers with the extract. Only a tapped link leaves the reader, and it opens in the tab.

Like an e-reader: serif text, justified and hyphenated, at most 34 em wide; Paper, Sepia and Night themes (Auto follows the app); text size 14 to 30; tap the left or right edge to turn the page; the top bar hides as you read down and comes back when you scroll up or reach the top; a footer with the percentage read and minutes left (230 words a minute). The settings are in the Aa sheet and kept apart from the app settings.
