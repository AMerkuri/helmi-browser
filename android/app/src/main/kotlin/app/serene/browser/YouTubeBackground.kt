package app.serene.browser

/**
 * YouTube background play, a flag (Settings › Flags, off by default): m.youtube.com treats every
 * video as one YouTube Premium may play in the background, so a fullscreen video goes
 * picture-in-picture when you leave Helmi (PictureInPictureHost) and keeps playing.
 *
 * Why it is needed: YouTube's phone player gives picture-in-picture and background play only to
 * a video its server marks backgroundable (Premium). For any other video it sets
 * disablePictureInPicture on the <video> (so Helmi, like Chrome, opens no window), leaves
 * fullscreen when the window shrinks to picture-in-picture size, and pauses while in
 * picture-in-picture or when the page is hidden. Every one of those checks reads the same mark,
 * playerResponse.playabilityStatus.backgroundability.backgroundabilityRenderer.backgroundable,
 * so [YOUTUBE_BACKGROUND_JS] sets it in each player response the page gets: the first one
 * (window.ytInitialPlayerResponse, written by the watch page) and those fetched later from
 * /youtubei/v1/ (the next video, Shorts). Nothing else in the response changes.
 *
 * YouTube's terms forbid getting around features that limit the service, and YouTube can drop
 * or move the mark in any player update, so this stays a flag the user turns on.
 */

/** Where [YOUTUBE_BACKGROUND_JS] runs: YouTube's phone site. Not www (desktop site: no checks) or music. */
internal val YOUTUBE_BACKGROUND_ORIGINS = setOf("https://m.youtube.com")

internal const val YOUTUBE_BACKGROUND_JS = """(() => {
    if (location.origin !== 'https://m.youtube.com' || window.__helmiYouTubeBackground) return;
    Object.defineProperty(window, '__helmiYouTubeBackground', { value: true });
    // A player response is at the top of what /youtubei/v1/player answers, and one level down in
    // others (playerResponse in a Shorts item). Deeper is page content, left alone.
    const mark = (o, depth) => {
        if (!o || typeof o !== 'object' || depth > 3) return o;
        const s = o.playabilityStatus;
        if (s && typeof s === 'object') {
            s.backgroundability = { backgroundabilityRenderer: { backgroundable: true } };
        }
        for (const k in o) {
            const v = o[k];
            if (k !== 'playabilityStatus' && v && typeof v === 'object') mark(v, depth + 1);
        }
        return o;
    };
    const markSafely = o => { try { return mark(o, 0); } catch (e) { return o; } };
    // The watch page's own script: var ytInitialPlayerResponse = {...}. A var of the page goes
    // through this setter, as the property is already on window.
    let initial = window.ytInitialPlayerResponse;
    try {
        Object.defineProperty(window, 'ytInitialPlayerResponse', {
            get: () => initial,
            set: v => { initial = markSafely(v); },
            enumerable: true,
            configurable: true,
        });
    } catch (e) {}
    const fromYouTubeI = url => typeof url === 'string' && url.indexOf('/youtubei/v1/') >= 0;
    const json = Response.prototype.json;
    Response.prototype.json = function () {
        const p = json.apply(this, arguments);
        return fromYouTubeI(this.url) ? p.then(markSafely) : p;
    };
    // A response read as text and parsed by the page: only text that has a player response in it.
    const parse = JSON.parse;
    JSON.parse = function (text) {
        const v = parse.apply(this, arguments);
        return typeof text === 'string' && text.indexOf('"playabilityStatus"') >= 0 ? markSafely(v) : v;
    };
})()"""
