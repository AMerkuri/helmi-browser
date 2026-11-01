package app.serene.browser

/**
 * Bot checks are left as WebView has them: Cloudflare's "Verify you are human" and the Turnstile
 * boxes sites put on sign-in pages run in a frame from challenges.cloudflare.com (and about:srcdoc
 * or about:blank frames inside it, which have its origin). Nothing in there is an annoyance, and
 * the check looks at the frame's window, its navigator and its style sheets for what scripts
 * changed. So Helmi's document-start scripts do nothing in such a frame, and the frame has no
 * SereneBridge either (addWebMessageListener can only be given the origins to add it to, not the
 * ones to leave out, so it is taken out again).
 *
 * In a private tab on techspot's sign-in page the check passed, set cf_clearance and asked again,
 * over and over, while Chrome's Incognito went on to the page.
 */
private const val IN_BOT_CHECK = "(location.origin === 'https://challenges.cloudflare.com')"

/** [js], except in a bot check's frame. */
internal fun outsideBotChecks(js: String): String = "if (!$IN_BOT_CHECK) {\n$js\n}"

/** Takes SereneBridge out of a bot check's frame. The first document-start script. */
internal const val LEAVE_BOT_CHECKS = "if ($IN_BOT_CHECK) { try { delete window.SereneBridge; } catch (e) {} }"
