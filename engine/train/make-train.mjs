// Writes the fine-tuning set: overlay features built from the hand-written pools, each in several
// shapes, with the label and the decline button the model should answer. The 159 labelled records
// (test/data/labeled-features.jsonl) are the test set and never go in here; --check fails when a
// training text shares an 8-word run with one of them.
//   node train/make-train.mjs > train/train.jsonl
//   node train/make-train.mjs --check
import fs from 'node:fs';
import cookie from './pools/cookie.mjs';
import * as annoy from './pools/annoyances.mjs';
import content from './pools/content.mjs';

// Deterministic, so the set is the same on every run (mulberry32).
let seed = 20261028;
const rnd = () => { seed |= 0; seed = (seed + 0x6d2b79f5) | 0; let t = Math.imul(seed ^ (seed >>> 15), 1 | seed); t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t; return ((t ^ (t >>> 14)) >>> 0) / 4294967296; };
const pick = (a) => a[Math.floor(rnd() * a.length)];
const between = ([lo, hi]) => Math.round((lo + rnd() * (hi - lo)) * 100) / 100;

// Where each kind sits on screen. modal / lock: chance of aria-modal and a scroll lock.
const SHAPES = {
  cookie_consent: [{ edge: 'bottom', cov: [0.12, 0.35] }, { edge: 'center', cov: [0.35, 0.7], modal: 0.7, lock: 0.5 }, { edge: 'full', cov: [0.85, 1], modal: 0.6, lock: 0.9 }],
  newsletter: [{ edge: 'center', cov: [0.3, 0.65], modal: 0.5, lock: 0.3 }, { edge: 'bottom', cov: [0.15, 0.35] }],
  notification_prompt: [{ edge: 'top', cov: [0.08, 0.2] }, { edge: 'center', cov: [0.15, 0.3], modal: 0.3 }],
  paywall_teaser: [{ edge: 'bottom', cov: [0.3, 0.6], lock: 0.5 }, { edge: 'full', cov: [0.85, 1], lock: 0.8 }],
  app_install: [{ edge: 'top', cov: [0.06, 0.12] }, { edge: 'bottom', cov: [0.08, 0.2] }, { edge: 'full', cov: [0.85, 1], modal: 0.4 }],
  login_wall: [{ edge: 'center', cov: [0.4, 0.7], modal: 0.7, lock: 0.8 }, { edge: 'full', cov: [0.85, 1], lock: 0.9 }],
  chat_widget: [{ edge: 'bottom', cov: [0.02, 0.08] }],
  survey: [{ edge: 'bottom', cov: [0.15, 0.35] }, { edge: 'center', cov: [0.25, 0.5], modal: 0.5 }],
  header: [{ edge: 'top', cov: [0.05, 0.12], tag: ['header', 'div', 'nav'], role: ['banner', '', ''] }],
  bottom_nav: [{ edge: 'bottom', cov: [0.06, 0.1], tag: ['nav', 'div'], role: ['navigation', ''] }],
  cart: [{ edge: 'center', cov: [0.4, 0.9], modal: 0.4 }, { edge: 'bottom', cov: [0.2, 0.45] }, { edge: 'top', cov: [0.04, 0.08] }],
  alert: [{ edge: 'top', cov: [0.05, 0.12] }, { edge: 'bottom', cov: [0.06, 0.15] }],
  article: [{ edge: 'center', cov: [0.5, 0.9] }, { edge: 'full', cov: [0.85, 1] }],
  menu: [{ edge: 'full', cov: [0.85, 1], modal: 0.5, lock: 0.6 }, { edge: 'bottom', cov: [0.3, 0.6], modal: 0.4 }],
  player: [{ edge: 'bottom', cov: [0.08, 0.25] }],
  widget: [{ edge: 'bottom', cov: [0.04, 0.2] }, { edge: 'top', cov: [0.03, 0.08] }, { edge: 'center', cov: [0.2, 0.6] }],
};

// Class names: often meaningful, often hashed by a CSS-in-JS build, so the text has to decide.
const TOKENS = {
  cookie_consent: ['cookie-banner', 'consent-modal', 'cc-window cc-bottom', 'gdpr-notice', 'privacy-banner', 'cmp-container', 'cookie-notice-wrapper'],
  newsletter: ['newsletter-modal', 'signup-popup', 'email-capture', 'klaviyo-form', 'promo-modal'],
  notification_prompt: ['push-prompt', 'notification-optin', 'web-push-dialog', 'alerts-optin'],
  paywall_teaser: ['paywall', 'meter-modal', 'subscription-wall', 'premium-overlay', 'regwall'],
  app_install: ['app-banner', 'smart-banner', 'open-in-app', 'app-promo', 'download-app'],
  login_wall: ['login-modal', 'auth-wall', 'signup-wall', 'signin-overlay'],
  chat_widget: ['chat-launcher', 'support-widget', 'intercom-launcher', 'livechat-button'],
  survey: ['survey-modal', 'feedback-widget', 'nps-survey', 'rating-popup'],
  header: ['site-header', 'masthead', 'top-bar', 'header sticky', 'navbar'],
  bottom_nav: ['tab-bar', 'bottom-nav', 'mobile-nav', 'app-nav'],
  cart: ['mini-cart', 'cart-drawer', 'added-to-bag', 'basket-flyout', 'shipping-bar'],
  alert: ['breaking-news', 'alert-bar', 'weather-alert', 'live-ticker'],
  article: ['article-body', 'content', 'post', 'story'],
  menu: ['nav-drawer', 'menu-overlay', 'language-picker', 'filter-sheet', 'share-sheet'],
  player: ['audio-player', 'video-dock', 'sticky-player', 'mini-player'],
  widget: ['progress-bar', 'toc', 'delivery-picker', 'checkout-steps', 'comments-drawer', 'score-ticker', 'back-to-top'],
};
const hashed = () => `${pick(['sc', 'css', 'x', 'jsx', 'c'])}-${Math.floor(rnd() * 36 ** 5).toString(36)}`;
const CMP = ['https://cdn.consentmanager.example', 'https://cmp.privacy-tool.example', 'https://consent.cmp-vendor.example'];

function features(kind, item) {
  const s = pick(SHAPES[kind]);
  const tokens = rnd() < 0.4 ? `${hashed()} ${hashed()}` : pick(TOKENS[kind]) + (rnd() < 0.3 ? ` ${hashed()}` : '');
  const buttons = [...item.b];
  let d = item.d ?? -1;
  // A close cross is often there too; it becomes the decline when nothing else is.
  if (rnd() < 0.25 && !buttons.includes('×') && buttons.length < 6) {
    buttons.push('×');
    if (d < 0 && !['paywall_teaser', 'login_wall', 'chat_widget'].includes(kind) && !(kind in content)) d = buttons.length - 1;
  }
  const iframe = kind === 'cookie_consent' && rnd() < 0.1;
  return {
    sig: `div.${tokens.split(' ')[0]}`,
    tag: s.tag ? pick(s.tag) : pick(['div', 'div', 'div', 'section', 'aside']),
    role: s.role ? pick(s.role) : (s.modal && rnd() < 0.5 ? 'dialog' : ''),
    ariaModal: !!s.modal && rnd() < s.modal,
    ariaLabel: '',
    tokens,
    text: item.t,
    textLen: item.t.length,
    buttons,
    hasEmail: !!item.email && rnd() < 0.85,
    hasPassword: !!item.password,
    hasVideo: !!item.video,
    iframeOrigins: iframe ? [pick(CMP)] : [],
    coverage: between(s.cov),
    edge: s.edge,
    zIndex: pick([0, 10, 100, 999, 9999, 2147483647]),
    scrollLocked: !!s.lock && rnd() < s.lock,
    userInitiated: false,
    isBackdrop: false,
    looksLikeLayout: false,
    target: d,
  };
}

const records = [];
function add(label, kind, pool, perItem) {
  for (const [lang, items] of Object.entries(pool)) {
    items.forEach((item, i) => {
      for (let k = 0; k < perItem; k++) {
        const f = features(kind, item);
        const target = f.target; delete f.target;
        records.push({ id: `train/${kind}/${lang}/${i}/${k}`, expected: label, target: label === 'content' ? -1 : target, features: f });
      }
    });
  }
}
add('cookie_consent', 'cookie_consent', cookie, 6);
for (const [label, pool] of Object.entries(annoy)) add(label, label, pool, label === 'chat_widget' ? 4 : 6);
for (const [kind, pool] of Object.entries(content)) add('content', kind, pool, 9);

if (process.argv.includes('--check')) {
  const words = (t) => t.toLowerCase().split(/\s+/).filter(Boolean);
  const runs = new Set();
  for (const l of fs.readFileSync(new URL('../test/data/labeled-features.jsonl', import.meta.url), 'utf8').split('\n').filter(Boolean)) {
    const w = words(JSON.parse(l).features.text);
    for (let i = 0; i + 8 <= w.length; i++) runs.add(w.slice(i, i + 8).join(' '));
  }
  const clash = records.filter((r) => { const w = words(r.features.text); for (let i = 0; i + 8 <= w.length; i++) if (runs.has(w.slice(i, i + 8).join(' '))) return true; return false; });
  const byLabel = {};
  for (const r of records) byLabel[r.expected] = (byLabel[r.expected] || 0) + 1;
  console.log(`${records.length} records`, byLabel);
  if (clash.length) { console.log('shares an 8-word run with the test set:', [...new Set(clash.map((r) => r.features.text))]); process.exit(1); }
} else {
  for (const r of records) console.log(JSON.stringify(r));
}
