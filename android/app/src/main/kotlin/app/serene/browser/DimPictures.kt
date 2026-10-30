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
 */
fun dimPicturesScript(s: Settings): String {
    val level = if (s.dimPictures) s.dimLevel / 100.0 else 1.0
    val choices = JSONObject(s.darkSiteChoices as Map<*, *>)
    return """(() => {
        const key = Symbol.for('serene.dimPictures');
        try { document.adoptedStyleSheets = document.adoptedStyleSheets.filter(x => x !== window[key]); } catch (e) {}
        const level = $level, sites = $choices;
        let on = ${s.darkSites}, host = location.hostname;
        try { const a = location.ancestorOrigins; if (a && a.length) host = new URL(a[a.length - 1]).hostname; } catch (e) {}
        for (const site in sites) if (host === site || host.endsWith('.' + site)) on = sites[site];
        if (!on || level >= 1) return;
        try {
            const sheet = new CSSStyleSheet();
            sheet.replaceSync('@media (prefers-color-scheme: dark) { img, svg image, input[type=image] { filter: brightness(' + level + ') !important; } }');
            document.adoptedStyleSheets = [...document.adoptedStyleSheets, sheet];
            window[key] = sheet;
        } catch (e) {}
    })();"""
}
