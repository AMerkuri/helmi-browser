// LLM classifier: features in, {label, confidence} out.
//
// The prompt and the output contract are the part that carries over to Android unchanged.
// The backend here is llama.cpp (node-llama-cpp) running the same Qwen3-0.6B weights as GGUF;
// on the phone it is LiteRT-LM with the .litertlm build.
//
// Default mode is single-pass label scoring: prefill the prompt plus the start of the answer,
// `{"label": "`, then read the model's next-token logits for the first token of every label.
// One forward pass, no decoding, and a probability per label instead of one sampled answer.
// Every label starts with a different token (checked at load), so the first token decides.
// The older generate mode (grammar-constrained JSON) is kept for comparison in llm-eval.
//
// The prompt still asks for a `target` button. Nothing reads it any more (the policy picks the
// button), and in scoring mode the model never gets that far, but taking the line out of the
// prompt cost 5 points of label accuracy on the eval set (84% → 79%), so it stays.

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

// What the answer starts with. Scoring mode prefills it; the label is the next token.
export const ANSWER_PREFIX = '{"label": "';

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

/** Validate a raw model reply (generate mode). Returns null when it is unusable. */
export function parseReply(raw) {
  const m = /\{[\s\S]*\}/.exec(raw || '');
  if (!m) return null;
  let o;
  try { o = JSON.parse(m[0]); } catch { return null; }
  if (!LABELS.includes(o.label)) return null;
  return { label: o.label };
}

/**
 * Turn per-label logits into a verdict: softmax over the labels only (the same as the model's
 * probabilities renormalised to the allowed answers). `offLabel` is the share of probability
 * the model put on tokens that start no label; high values mean the prompt confused it.
 * @param logits  {label: logit}
 * @param total   optional sum of exp(logit - maxLogit) over the whole vocabulary
 * @param max     the max logit over the whole vocabulary (needed with total)
 */
export function scoreLabels(logits, { total, max } = {}) {
  const top = Math.max(...Object.values(logits));
  const w = Object.fromEntries(Object.entries(logits).map(([l, x]) => [l, Math.exp(x - top)]));
  const sum = Object.values(w).reduce((a, b) => a + b, 0);
  const scores = Object.fromEntries(Object.entries(w).map(([l, x]) => [l, x / sum]));
  const label = Object.keys(scores).reduce((a, b) => (scores[b] > scores[a] ? b : a));
  const out = { label, confidence: scores[label], scores };
  if (total && max != null) out.offLabel = Math.max(0, 1 - (sum * Math.exp(top - max)) / total);
  return out;
}

/** llama.cpp backend. Loads lazily on the first call, like the app will. */
export class LlamaClassifier {
  /** mode: 'score' (single pass, default) or 'generate' (grammar-constrained JSON). */
  constructor({ modelPath, gpu = 'auto', contextSize = 2048, mode = 'score' }) {
    this.opts = { modelPath, gpu, contextSize, mode };
    this.ready = null;
    this.queue = Promise.resolve();
  }

  async load() {
    const { getLlama, LlamaChatSession, QwenChatWrapper, resolveChatWrapper } = await import('node-llama-cpp');
    const llama = await getLlama({ gpu: this.opts.gpu });
    const model = await llama.loadModel({ modelPath: this.opts.modelPath });
    const context = await model.createContext({ contextSize: this.opts.contextSize });
    // Qwen3: pre-fill an empty <think> block so it answers straight away (plus /no_think).
    const chatWrapper = /qwen3/i.test(this.opts.modelPath) ? new QwenChatWrapper({ thoughts: 'discourage' }) : undefined;
    const sequence = context.getSequence();
    if (this.opts.mode === 'generate') {
      const session = new LlamaChatSession({ contextSequence: sequence, systemPrompt: SYSTEM_PROMPT, ...(chatWrapper ? { chatWrapper } : {}) });
      const grammar = await llama.createGrammarForJsonSchema(OUTPUT_SCHEMA);
      return { model, session, grammar, base: session.getChatHistory() };
    }
    const wrapper = chatWrapper ?? resolveChatWrapper(model);
    // First token of each label, as it follows ANSWER_PREFIX. They must all differ.
    const prefix = model.tokenize(ANSWER_PREFIX, true);
    const firstTokens = {};
    for (const l of LABELS) {
      const t = model.tokenize(ANSWER_PREFIX + l, true);
      if (!prefix.every((p, i) => p === t[i])) throw new Error(`label ${l} changes how the answer prefix tokenizes`);
      firstTokens[l] = t[prefix.length];
    }
    if (new Set(Object.values(firstTokens)).size !== LABELS.length) throw new Error('two labels share a first token; rename one');
    return { model, sequence, wrapper, prefix, firstTokens };
  }

  /** Token ids for system + user turn + the start of the answer, using the model's chat template. */
  promptTokens({ model, wrapper, prefix }, text) {
    const { contextText } = wrapper.generateContextState({
      chatHistory: [{ type: 'system', text: SYSTEM_PROMPT }, { type: 'user', text }, { type: 'model', response: [] }],
    });
    return [...contextText.tokenize(model.tokenizer), ...prefix];
  }

  async score(state, features) {
    const { model, sequence, firstTokens } = state;
    const tokens = this.promptTokens(state, buildPrompt(features));
    // Keep the shared system-prompt prefix in the KV cache; drop the rest of the last prompt.
    const prev = sequence.contextTokens;
    let keep = 0;
    while (keep < prev.length && keep < tokens.length - 1 && prev[keep] === tokens[keep]) keep++;
    if (keep < sequence.nextTokenIndex) await sequence.eraseContextTokenRanges([{ start: keep, end: sequence.nextTokenIndex }]);
    const input = tokens.slice(keep);
    const last = input.length - 1;
    input[last] = [input[last], { generateNext: { logits: { filter: { tokens: Object.values(firstTokens), includeMax: true } } } }];
    const res = await sequence.controlledEvaluate(input);
    const next = res[last].next;
    const logits = Object.fromEntries(LABELS.map((l) => [l, next.logits.get(firstTokens[l])]));
    const max = next.logits.values().next().value; // the map is sorted, highest first
    return { ...scoreLabels(logits, { total: next.totalLogitWeight, max }), promptTokens: tokens.length, prefilled: input.length };
  }

  async generate({ model, session, grammar, base }, features) {
    // Same system prefix every call, so llama.cpp reuses its KV cache for it.
    session.setChatHistory(base);
    const prompt = buildPrompt(features);
    const raw = await session.prompt(prompt, { grammar, maxTokens: 32, temperature: 0 });
    return { ...(parseReply(raw) ?? { label: 'content', invalid: true }), raw, promptTokens: model.tokenize(SYSTEM_PROMPT + prompt).length };
  }

  /** Serialised: one inference at a time, as on the phone. */
  classify(features) {
    const run = async () => {
      this.ready ??= this.load();
      const state = await this.ready;
      const t0 = performance.now();
      const out = this.opts.mode === 'generate' ? await this.generate(state, features) : await this.score(state, features);
      return { ...out, ms: Math.round(performance.now() - t0) };
    };
    const p = this.queue.then(run, run);
    this.queue = p.catch(() => {});
    return p;
  }
}
