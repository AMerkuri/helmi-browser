// Prompt variants for test/litert-eval.py --variant NAME: the app's prompt (base) and edits of it
// being tried for Gemma 3 1B. Each has the system text and the user turn for a feature record.
import { SYSTEM_PROMPT, buildPrompt, LABELS } from '../src/host/classifier.mjs';

const list = LABELS.join(', ');
const noTarget = SYSTEM_PROMPT.replace(/\ntarget: .*\n/, '\n').replace('{"label": "...", "target": N}', '{"label": "..."}');
const strict = `The label must be exactly one of: ${list}. Never use text from the overlay as the label.`;
const bare = (f) => buildPrompt(f).replace(/\n\/no_think$/, '');
// Gemma leans to whichever label it reads first; these move the label lines around.
const labelLines = noTarget.split('\n').filter((l) => /^[a-z_]+: /.test(l));
const withLabels = (lines) => noTarget.replace(labelLines.join('\n'), lines.join('\n'));
const cookieLast = withLabels([...labelLines.slice(1, -1), labelLines[0], labelLines.at(-1)]);
const contentFirst = withLabels([labelLines.at(-1), ...labelLines.slice(0, -1)]);

// Worked examples sent as earlier turns, hand-written (not from the eval set).
const ex = (f, label) => [bare({ tag: 'div', role: '', tokens: '', buttons: [], coverage: 0.2, edge: 'center', ...f }), `{"label": "${label}"}`];
const EX3 = [
  ex({ tokens: 'promo-modal', text: 'Get 15% off your first order. Join our list for deals and new arrivals. Email address Sign up No thanks', buttons: ['Sign up', 'No thanks'], hasEmail: true, coverage: 0.45 }, 'newsletter'),
  ex({ tokens: 'mobile-cta', text: 'Faster in the app. Open in app Continue in browser', buttons: ['Open in app', 'Continue in browser'], edge: 'bottom', coverage: 0.15 }, 'app_install'),
  ex({ tokens: 'mini-cart', text: 'Your basket 2 items Subtotal 48.00 Checkout View basket', buttons: ['Checkout', 'View basket'], edge: 'top', coverage: 0.3 }, 'content'),
];
const EX6 = [
  ...EX3,
  ex({ tokens: 'meter-wall', text: 'You have read your 3 free articles this month. Subscribe for 1 euro to keep reading. Subscribe Sign in', buttons: ['Subscribe', 'Sign in'], edge: 'bottom', coverage: 0.5 }, 'paywall_teaser'),
  ex({ role: 'dialog', ariaModal: true, scrollLocked: true, tokens: 'auth-gate', text: 'Sign in to continue. Create a free account to see more. Continue with Google Log in Sign up', buttons: ['Continue with Google', 'Log in', 'Sign up'], edge: 'full', coverage: 1 }, 'login_wall'),
  ex({ tokens: 'push-ask', text: 'Never miss breaking news. Allow notifications? Allow Later', buttons: ['Allow', 'Later'], edge: 'top', coverage: 0.12 }, 'notification_prompt'),
];

export const VARIANTS = {
  base: { system: SYSTEM_PROMPT, user: buildPrompt },
  // Spell out that the label is one of nine words, after the format line.
  strict: { system: `${SYSTEM_PROMPT}\n${strict}`, user: bare },
  // The same reminder at the end of the user turn, right before the answer.
  'strict-tail': { system: SYSTEM_PROMPT, user: (f) => `${bare(f)}\n\n${strict}` },
  'no-target': { system: noTarget, user: bare },
  'no-target-strict': { system: `${noTarget}\n${strict}`, user: bare },
  'no-target-strict-tail': { system: noTarget, user: (f) => `${bare(f)}\n\n${strict}` },
  'nt-cookie-last': { system: cookieLast, user: bare },
  'nt-content-first': { system: contentFirst, user: bare },
  'nt-question': { system: noTarget, user: (f) => `${bare(f)}\n\nWhich label fits this overlay?` },
  'nt-preface': { system: noTarget, user: bare, preface: true },
  'nt-ex3': { system: noTarget, user: bare, examples: EX3 },
  'nt-ex6': { system: noTarget, user: bare, examples: EX6 },
};
