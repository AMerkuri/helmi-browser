// LLM classifier: features in, {label, target} out.
//
// The prompt and the output contract are the part that carries over to Android unchanged.
// The backend here is llama.cpp (node-llama-cpp) running the same Qwen3-0.6B weights as GGUF;
// on the phone it is LiteRT-LM with the .litertlm build.

export const LABELS = [
  'cookie_consent', 'newsletter', 'notification_prompt', 'paywall_teaser',
  'app_install', 'login_wall', 'chat_widget', 'survey', 'content',
];

export const SYSTEM_PROMPT = `You label overlays that appear on web pages in a mobile browser.
Labels:
cookie_consent: cookie, privacy, tracking or consent notice, also "accept or pay" walls
newsletter: offers emails, updates or a discount for signing up, often with an email field
notification_prompt: asks to enable push notifications or alerts
paywall_teaser: says you reached a free article limit, or must subscribe or pay to keep reading
app_install: asks to open or install an app: "open in app", "get the app", App Store, Google Play
login_wall: blocks the page until you sign in or create an account
chat_widget: support chat bubble
survey: feedback or rating request
content: anything else the reader wants: site header, navigation, menus, players, carts, filters, article text
target: index of the button that rejects, declines or closes WITHOUT agreeing or subscribing, else -1.
Answer with JSON only: {"label": "...", "target": N}`;

export const OUTPUT_SCHEMA = {
  type: 'object',
  properties: {
    label: { enum: LABELS },
    target: { type: 'integer', minimum: -1, maximum: 7 },
  },
  required: ['label', 'target'],
};

/** Compact text form of the features, aimed at ~100-200 tokens. */
export function buildPrompt(f) {
  const where = { full: 'covers the whole screen', bottom: 'banner at the bottom', top: 'bar at the top', center: 'box in the middle' }[f.edge] || f.edge;
  const lines = [
    `Overlay: ${where}, ${Math.round(f.coverage * 100)}% of screen${f.scrollLocked ? ', page scrolling locked' : ''}${f.ariaModal ? ', modal dialog' : ''}`,
  ];
  if (f.tokens || f.role) lines.push(`Element: <${f.tag}${f.role ? ` role=${f.role}` : ''}> ${f.tokens}`.trim());
  if (f.iframeOrigins?.length) lines.push(`Iframe from: ${f.iframeOrigins.join(', ')}`);
  const inputs = [f.hasEmail && 'email field', f.hasPassword && 'password field', f.hasVideo && 'video'].filter(Boolean);
  if (inputs.length) lines.push(`Contains: ${inputs.join(', ')}`);
  lines.push(`Text: "${(f.text || '').slice(0, 300)}"`);
  lines.push(`Buttons: ${f.buttons.length ? f.buttons.map((b, i) => `[${i}] ${b}`).join(' ') : 'none'}`);
  return lines.join('\n') + '\n/no_think';
}

/** Validate a raw model reply. Returns null when it is unusable. */
export function parseReply(raw, nButtons) {
  const m = /\{[\s\S]*\}/.exec(raw || '');
  if (!m) return null;
  let o;
  try { o = JSON.parse(m[0]); } catch { return null; }
  if (!LABELS.includes(o.label)) return null;
  let target = Number.isInteger(o.target) ? o.target : -1;
  if (target >= nButtons) target = -1;
  return { label: o.label, target };
}

/** llama.cpp backend. Loads lazily on the first call, like the app will. */
export class LlamaClassifier {
  constructor({ modelPath, gpu = 'auto', contextSize = 2048 }) {
    this.opts = { modelPath, gpu, contextSize };
    this.ready = null;
    this.queue = Promise.resolve();
  }

  async load() {
    const { getLlama, LlamaChatSession, QwenChatWrapper } = await import('node-llama-cpp');
    const llama = await getLlama({ gpu: this.opts.gpu });
    const model = await llama.loadModel({ modelPath: this.opts.modelPath });
    const context = await model.createContext({ contextSize: this.opts.contextSize });
    const session = new LlamaChatSession({
      contextSequence: context.getSequence(),
      systemPrompt: SYSTEM_PROMPT,
      // Qwen3: pre-fill an empty <think> block so it answers straight away (plus /no_think).
      ...(/qwen3/i.test(this.opts.modelPath) ? { chatWrapper: new QwenChatWrapper({ thoughts: 'discourage' }) } : {}),
    });
    const grammar = await llama.createGrammarForJsonSchema(OUTPUT_SCHEMA);
    return { session, grammar, base: session.getChatHistory(), model };
  }

  /** Serialised: one inference at a time, as on the phone. */
  classify(features) {
    const run = async () => {
      this.ready ??= this.load();
      const { session, grammar, base, model } = await this.ready;
      // Same system prefix every call, so llama.cpp reuses its KV cache for it.
      session.setChatHistory(base);
      const prompt = buildPrompt(features);
      const t0 = performance.now();
      const raw = await session.prompt(prompt, { grammar, maxTokens: 32, temperature: 0 });
      const ms = Math.round(performance.now() - t0);
      return { ...(parseReply(raw, features.buttons.length) ?? { label: 'content', target: -1, invalid: true }), raw, ms, promptTokens: model.tokenize(SYSTEM_PROMPT + prompt).length };
    };
    const p = this.queue.then(run, run);
    this.queue = p.catch(() => {});
    return p;
  }
}
