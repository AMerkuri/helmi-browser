package app.serene.browser

import app.serene.Settings
import org.json.JSONObject

/**
 * Settings › Theme › "Dim pictures": in the dark theme, a white picture (a chart, a photo on a
 * light background) glares on a darkened page at night. Pictures are never inverted, so they are
 * dimmed instead (CSS brightness(), colors kept): all of them alike, from the first paint.
 *
 * A document-start script, in every frame, built from the settings (the tab registers it again
 * when they change, and runs it on the page that is open). It applies where the dark theme
 * darkens sites: the setting, or the site's own choice in ⋮ › Dark theme (the top page's site,
 * also in its frames). The rule sits in `@media (prefers-color-scheme: dark)`, so it follows the
 * app theme as it changes. Run again, it replaces what it added before.
 *
 * Pictures are <img> and SVG images, and elements that show a picture as their CSS background
 * (techspot's "Featured Today" cards): those are found by their computed style as they are added
 * or restyled, before the next paint. An element with much text of its own is a part of the page
 * with a backdrop, not a picture, and stays as it is (a filter would dim its text too).
 */
fun dimPicturesScript(s: Settings): String {
    val level = if (s.dimPictures) s.dimStrength.percent / 100.0 else 1.0
    val choices = JSONObject(s.darkSiteChoices as Map<*, *>)
    return """(() => {
        const key = Symbol.for('serene.dimPictures');
        const st = window[key] || (window[key] = {});
        try { document.adoptedStyleSheets = document.adoptedStyleSheets.filter(x => x !== st.sheet); } catch (e) {}
        st.sheet = null;
        const level = $level, sites = $choices;
        let on = ${s.darkSites}, host = location.hostname;
        try { const a = location.ancestorOrigins; if (a && a.length) host = new URL(a[a.length - 1]).hostname; } catch (e) {}
        for (const site in sites) if (host === site || host.endsWith('.' + site)) on = sites[site];
        if (!on || level >= 1) return;
        const ATTR = 'data-serene-dim-bg';
        try {
            const sheet = new CSSStyleSheet();
            sheet.replaceSync('@media (prefers-color-scheme: dark) { img, svg image, input[type=image], [' + ATTR + '] { filter: brightness(' + level + ') !important; } }');
            document.adoptedStyleSheets = [...document.adoptedStyleSheets, sheet];
            st.sheet = sheet;
        } catch (e) { return; }
        if (st.watching) return;
        st.watching = true;
        const check = (el) => {
            if (el === document.documentElement || el === document.body || !el.isConnected) return;
            const pic = getComputedStyle(el).backgroundImage.includes('url(') && el.textContent.length < 300;
            if (pic !== el.hasAttribute(ATTR)) pic ? el.setAttribute(ATTR, '') : el.removeAttribute(ATTR);
        };
        // Elements to look at, with or without what is inside them; looked at before the next paint.
        const pending = new Map();
        let queued = false;
        const flush = () => {
            queued = false;
            for (const [el, deep] of pending) {
                check(el);
                if (deep) for (const d of el.getElementsByTagName('*')) check(d);
            }
            pending.clear();
        };
        const add = (el, deep) => {
            if (!(el instanceof Element)) return;
            pending.set(el, deep || pending.get(el) || false);
            if (!queued) { queued = true; requestAnimationFrame(flush); }
        };
        new MutationObserver((records) => {
            for (const r of records) {
                if (r.type === 'childList') {
                    // A new style rule can give any element a background: look at them all.
                    for (const n of r.addedNodes) add(n instanceof HTMLStyleElement ? document.documentElement : n, true);
                }
                // A class or style change restyles the element, and maybe a few inside it.
                else add(r.target, r.target.getElementsByTagName('*').length <= 200);
            }
        }).observe(document, { subtree: true, childList: true, attributes: true, attributeFilter: ['class', 'style'] });
        // So can a stylesheet that loads (one added late too), and the rest of the page loading.
        addEventListener('load', (e) => {
            if (e.target === document || e.target instanceof HTMLLinkElement) add(document.documentElement, true);
        }, true);
    })();"""
}
