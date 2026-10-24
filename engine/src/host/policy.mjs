// Decision policy: cache → heuristics → LLM. Pure functions so they port to Kotlin as-is.

export const DEFAULT_POLICY = {
  actScore: 0.85,   // heuristics alone decide above this
  ignoreScore: 0.25, // and ignore below this
  noLlmActScore: 0.6, // rules + heuristics only mode (no model, battery saver)
  llmStructure: 0.2,  // modal-shaped overlays with no keyword hit still go to the model
};

// We only ever click buttons that decline or close. Never "Accept", "Subscribe", "Allow".
const AGREE = /accept|agree|allow|got it|^ok(ay)?$|i understand|subscribe|sign ?up|continue with|log ?in|sign in|register|install|open( in)? app|enable|turn on|yes|akzeptieren|zustimmen|einverstanden|accepter|aceptar|accetta|aceitar|akkoord|zgadzam|souhlasím|elfogad/i;
const DECLINE = /reject|decline|refuse|deny|disagree|necessary|essential|continue without|without (accepting|agreeing)|no,? thanks|not now|maybe later|later|close|dismiss|skip|^×$|^✕$|^✖$|^x$|ablehnen|nur notwendige|refuser|continuer sans|rechazar|rifiuta|rejeitar|weiger|odrzu|odmítnout|nein/i;

// "Continue without accepting", "Continuer sans accepter": the agree word is negated.
const NEGATED = /without|sans |ohne |senza |sin |sem |bez |zonder /i;

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

export function verdict(label, target, f, source) {
  return { label, source, ...actionFor(label, target, f) };
}
