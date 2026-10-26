// Decision policy: cache → heuristics → LLM. Pure functions so they port to Kotlin as-is.

export const DEFAULT_POLICY = {
  actScore: 0.85,   // heuristics alone decide above this
  ignoreScore: 0.25, // and ignore below this
  noLlmActScore: 0.6, // rules + heuristics only mode (no model, battery saver)
  llmStructure: 0.2,  // modal-shaped overlays with no keyword hit still go to the model
  llmMinConfidence: 0.6, // below this the model's annoyance label is a guess: leave the overlay alone
};

// We only ever click buttons that decline or close. Never "Accept", "Subscribe", "Allow".
const AGREE = /accept|agree|allow|got it|^ok(ay)?$|i understand|subscribe|abbona|abonn|suscri|subskry|assinar|pay |zahlen|payer|pagar|pagare|sign ?up|continue with|log ?in|sign in|register|install|open( in)? app|enable|turn on|yes|akzeptieren|zustimmen|einverstanden|accepter|aceptar|accetta|aceitar|akkoord|zgadzam|souhlasím|elfogad|godkänn|acceptera|hyväksy|kabul|принять|согласен|разрешить|同意|許可|承認|受け入れ|接受|允许|允許|동의|수락|허용/i;
const DECLINE = /reject|decline|refuse|deny|disagree|necessary|essential|continue without|without (accepting|agreeing)|no,? thanks|not now|maybe later|later|close|dismiss|skip|^×$|^✕$|^✖$|^x$|ablehnen|nur notwendige|refuser|continuer sans|rechazar|rifiuta|rejeitar|weiger|odrzu|odmítnout|nein|schließen|später|nicht jetzt|fermer|plus tard|non merci|cerrar|ahora no|chiudi|non ora|fechar|agora não|sluiten|weigeren|zamknij|nie teraz|zavřít|avvisa|stäng|afvis|avvis|\bluk\b|hylkää|sulje|reddet|kapat|отклонить|отказаться|закрыть|не сейчас|拒否|同意しない|閉じる|後で|今はしない|拒绝|拒絕|关闭|關閉|不同意|稍后|거부|거절|동의하지 않|닫기|나중에/i;

// "Continue without accepting", "Continuer sans accepter": the agree word is negated.
const NEGATED = /without|sans |ohne |senza |sin |sem |bez |zonder |しない|不同意|않|^не /i;

// Thin bars at the top: site headers and nav ("Log in", "Subscribe", "Anmelden" are links in
// them, not prompts). On the 2026-10-24 crawl the model hid three of these as login walls or
// newsletters, so they never go to the model and only a strong heuristic can act on them.
// Also thin nav bars at the bottom (mobile tab bars: Booking's "Search Saved Sign in" went to the
// model as a login wall) and top bars with six or more links (Le Figaro's header, called a push
// prompt). Neither matches any annoyance in the labelled set.
const CHROME_TOKENS = /header|nav|masthead|top-?bar|toolbar|tab-?bar|menu/i;
export function looksLikeChrome(f) {
  if (f.coverage >= 0.15) return false;
  if (f.edge === 'bottom') return f.tag === 'nav' || /navigation|menubar/.test(f.role || '') || CHROME_TOKENS.test(f.tokens || '');
  if (f.edge !== 'top') return false;
  return /^(header|nav)$/.test(f.tag) || /banner|navigation|menubar/.test(f.role || '') || CHROME_TOKENS.test(f.tokens || '') ||
    (f.textLen ?? 0) < 60 || f.buttons.length >= 6;
}

export function isSafeTarget(label) {
  return !!label && DECLINE.test(label) && (NEGATED.test(label) || !AGREE.test(label));
}

// What to do once we know the label.
const ACTIONS = {
  cookie_consent: 'click',     // reject / necessary-only; hide if no such button
  newsletter: 'click',         // "No thanks" / close; else hide
  notification_prompt: 'click',
  app_install: 'click',
  survey: 'click',
  paywall_teaser: 'hide',      // nag overlay only, never unlock content
  login_wall: 'hide',
  chat_widget: 'ignore',       // usually small and wanted; hidden only if it covers the page
  adblock_notice: 'click',     // its own close button; else hide (heuristics only, not a model label)
  content: 'ignore',
};

export function actionFor(label, target, f) {
  let action = ACTIONS[label] ?? 'ignore';
  if (label === 'chat_widget' && f.coverage > 0.3) action = 'hide';
  if (action === 'click') {
    const btn = f.buttons[target];
    if (!isSafeTarget(btn)) {
      // The model picked an agree button or none: find a decline button ourselves, else hide.
      target = f.buttons.findIndex(isSafeTarget);
      if (target < 0) action = 'hide';
    }
  }
  return { action, target: action === 'click' ? target : -1, buttonLabel: action === 'click' ? f.buttons[target] : undefined };
}

/**
 * Decide what to do with a candidate without the model.
 * Returns a verdict, or { needsLlm: true } when the heuristics are unsure.
 */
export function preDecide(f, { llmAvailable, policy = DEFAULT_POLICY }) {
  if (f.userInitiated) return verdict('content', -1, f, 'user-gesture');
  if (f.isBackdrop) return verdict('content', -1, f, 'backdrop'); // handled with its modal
  const h = f.heuristic;
  if (looksLikeChrome(f)) {
    const navLike = /^(header|nav)$/.test(f.tag) || /banner|navigation|menubar/.test(f.role || '') || CHROME_TOKENS.test(f.tokens || '');
    // A thin top bar with cookie wording is still a cookie bar.
    const strong = !navLike && h.label !== 'content' && (h.score >= policy.actScore || (h.label === 'cookie_consent' && h.score >= policy.noLlmActScore));
    return strong ? verdict(h.label, h.rejectIdx, f, 'heuristic') : verdict('content', -1, f, 'chrome');
  }
  // No keyword hit but shaped like a blocking modal: the long tail the model is for
  // (unfamiliar wording, other languages).
  if (h.label === 'content') {
    if (llmAvailable && h.structure >= policy.llmStructure && !f.looksLikeLayout) return { needsLlm: true };
    return verdict('content', -1, f, 'heuristic');
  }
  if (h.score < policy.ignoreScore) return verdict('content', -1, f, 'heuristic');
  if (h.score >= policy.actScore) return verdict(h.label, h.rejectIdx, f, 'heuristic');
  if (llmAvailable) return { needsLlm: true };
  if (h.score >= policy.noLlmActScore) return verdict(h.label, h.rejectIdx, f, 'heuristic');
  return verdict('content', -1, f, 'heuristic');
}

/**
 * Verdict from the model's answer {label, confidence?}. The model only names the label; the
 * button comes from the decline list. On the eval set every correct annoyance label scored
 * ≥ 0.82, and most wrong ones (headers, players read as app banners or walls) ≤ 0.56.
 */
export function fromModel(r, f, policy = DEFAULT_POLICY) {
  if (r.label !== 'content' && r.confidence != null && r.confidence < policy.llmMinConfidence) return verdict('content', -1, f, 'llm-unsure');
  return verdict(r.label, -1, f, 'llm');
}

export function verdict(label, target, f, source) {
  return { label, source, ...actionFor(label, target, f) };
}
