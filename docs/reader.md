# Reader mode and summaries

Two page tools in the ⋮ menu: **Reader mode** shows the page's article alone, like an e-reader, and **Summarize page** lists its key points, written on the phone by the model Serene already downloads for overlays. Reader mode does not use the model.

## Finding the article

`engine/dist/reader.js` bundles [Mozilla Readability](https://github.com/mozilla/readability) 0.6.0 (Apache-2.0, the code behind Firefox's reader view) with `engine/src/content/reader-entry.js`. Unlike `serene.js` it does not run at document start: the app evaluates it on the loaded page when you ask, and reads `__sereneReader()`.

Before Readability sees a copy of the page, the entry script removes:

- the overlays Serene hid (`data-serene`) and fixed-position layers (cookie bars, chat bubbles, sticky headers);
- nothing else that is hidden: collapsed sections that find-in-page can open (`hidden="until-found"`, mobile Wikipedia) are opened instead, since Readability drops anything `[hidden]`. Without that, mobile Wikipedia gave 141 of 623 words.

The result has the article as HTML (scripts, frames, forms, inline styles and event handlers stripped) and as plain paragraphs for the summary (headings as `## `, no code blocks or captions). On a page that is not an article (a shop, a front page) the summary gets the page's visible text.

Checked on 10 real pages on 2026-10-25 (BBC, ERR News, ERR in Estonian, the Guardian, Spiegel, Wikipedia, Paul Graham, Smashing Magazine): every one gave its article, in 35 to 85 ms on a desktop Chromium. `engine/test/reader.test.mjs` runs the extractor on hand-written pages.

## Reader view

`ui/ReaderView.kt` shows `reader/ReaderHtml.kt`'s document in a WebView of its own with JavaScript off, and the document's Content-Security-Policy (`default-src 'none'`, images and media allowed, no forms) holds even if a page tampers with the extract. Only a tapped link leaves the reader, and it opens in the tab.

Like an e-reader: serif text, justified and hyphenated, at most 34 em wide; Paper, Sepia and Night themes (Auto follows the app); text size 14 to 30; tap the left or right edge to turn the page, the middle to show or hide the bar; a footer with the percentage read and minutes left (230 words a minute). The settings are in the Aa sheet and kept apart from the app settings.

## Summaries

`engine/src/host/summary.mjs` (ported in `reader/Summary.kt`) holds the prompts and the chunking; `reader/Summarizer.kt` runs them on LiteRT-LM.

- One call per part of about 2000 estimated tokens (≈6000 characters of English, fewer for Estonian, 2000 for Chinese). A typical news story is one call. Each part asks for 3 to 6 points (2 to 4 when there are several parts); when the parts give more than 7 points, one last call merges them into 5. At most 8 parts: a longer page is summarized from its start, and the sheet says so.
- Its own engine with a 4096-token window (the model files' limit), not the classifier's 1024. Loading it unloads the classifier's copy first, so one model is in memory at a time; the classifier loads again on the next uncertain overlay. It unloads after a minute without use.
- Greedy decoding, thinking off, at most 220 tokens a reply. The reply is parsed into points: list markers, quotes and repeats removed, a point cut off by the token limit dropped, lines of code skipped.
- The sheet fills in as the model writes, and closing it stops the model.

### How good it is

`engine/test/summary-eval.mjs` runs the same prompts on Qwen3-0.6B Q8_0 in llama.cpp (the closest desktop stand-in for the app's INT8 LiteRT-LM build). On the 10 pages above:

- English news (BBC, ERR News, Wikipedia): the points are mostly right and are the story's main facts. Errors are of the kind a 0.6B model makes: on the BBC Venezuela story it said Machado "was sworn in as interim president and signed an oil deal with the US", which the story says of Delcy Rodríguez. Mixing up who did what is the main risk.
- Long essays (11,000 words, Paul Graham) and tutorials (7,000 words) take 8 to 9 calls and come out generic: true, but not what makes the piece worth reading.
- German is understandable but has grammar slips, one invented detail ("vor 24 Stunden") and one twisted one (the 2428 guests were on board, not "betroffen"); Estonian is weaker, with one garbled sentence in six.

So the sheet says that the points come from a small model and can be wrong. A bigger model would do better; the prompts do not depend on Qwen3.

Speed: on a 4-core desktop CPU, 3 to 6 s for a news story and 45 to 50 s for the long pages. On the phone's GPU, LiteRT-LM's published numbers for this model on the same Snapdragon 8 Elite (646 tokens/s prefill, 25 tokens/s decode at a 4096 window) put a news story at about 5 s after a ~3 s load, and an essay at about 40 s. Not measured on the Fold 7 yet.
