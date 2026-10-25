package app.serene.engine

// Decision policy: cache → heuristics → LLM. Port of engine/src/host/policy.mjs; keep the two in
// step (the unit tests mirror engine/test/policy.test.mjs).

data class PolicyConfig(
    val actScore: Double = 0.85,     // heuristics alone decide above this
    val ignoreScore: Double = 0.25,  // and ignore below this
    val noLlmActScore: Double = 0.6, // rules + heuristics only mode (no model, battery saver)
    val llmStructure: Double = 0.2,  // modal-shaped overlays with no keyword hit still go to the model
    val llmMinConfidence: Double = 0.6, // below this the model's annoyance label is a guess: leave the overlay alone
)

/** What to do with a candidate. [needsLlm] means the heuristics are unsure: ask the model. */
data class Verdict(
    val label: String,
    val source: String,
    val action: String,
    val target: Int = -1,
    val buttonLabel: String? = null,
    val needsLlm: Boolean = false,
    val inFrame: String? = null,
) {
    companion object {
        val NEEDS_LLM = Verdict(label = "", source = "", action = "", needsLlm = true)
    }
}

object Policy {
    private fun re(s: String) = Regex(s, RegexOption.IGNORE_CASE)

    // We only ever click buttons that decline or close. Never "Accept", "Subscribe", "Allow".
    private val AGREE = re("""accept|agree|allow|got it|^ok(ay)?$|i understand|subscribe|abbona|abonn|suscri|subskry|assinar|pay |zahlen|payer|pagar|pagare|sign ?up|continue with|log ?in|sign in|register|install|open( in)? app|enable|turn on|yes|akzeptieren|zustimmen|einverstanden|accepter|aceptar|accetta|aceitar|akkoord|zgadzam|souhlasím|elfogad|godkänn|acceptera|hyväksy|kabul|принять|согласен|разрешить|同意|許可|承認|受け入れ|接受|允许|允許|동의|수락|허용""")
    private val DECLINE = re("""reject|decline|refuse|deny|disagree|necessary|essential|continue without|without (accepting|agreeing)|no,? thanks|not now|maybe later|later|close|dismiss|skip|^×$|^✕$|^✖$|^x$|ablehnen|nur notwendige|refuser|continuer sans|rechazar|rifiuta|rejeitar|weiger|odrzu|odmítnout|nein|schließen|später|nicht jetzt|fermer|plus tard|non merci|cerrar|ahora no|chiudi|non ora|fechar|agora não|sluiten|weigeren|zamknij|nie teraz|zavřít|avvisa|stäng|afvis|avvis|\bluk\b|hylkää|sulje|reddet|kapat|отклонить|отказаться|закрыть|не сейчас|拒否|同意しない|閉じる|後で|今はしない|拒绝|拒絕|关闭|關閉|不同意|稍后|거부|거절|동의하지 않|닫기|나중에""")

    // "Continue without accepting", "Continuer sans accepter": the agree word is negated.
    private val NEGATED = re("""without|sans |ohne |senza |sin |sem |bez |zonder |しない|不同意|않|^не """)

    // Thin bars at the top: site headers and nav. See looksLikeChrome() in policy.mjs.
    private val CHROME_TOKENS = re("header|nav|masthead|top-?bar|toolbar|menu")
    private val CHROME_TAG = Regex("^(header|nav)$")
    private val CHROME_ROLE = Regex("banner|navigation|menubar")

    fun looksLikeChrome(f: Features): Boolean {
        if (f.edge != "top" || f.coverage >= 0.15) return false
        return CHROME_TAG.matches(f.tag) || CHROME_ROLE.containsMatchIn(f.role) ||
            CHROME_TOKENS.containsMatchIn(f.tokens) || f.textLen < 60
    }

    fun isSafeTarget(label: String?): Boolean =
        !label.isNullOrEmpty() && DECLINE.containsMatchIn(label) &&
            (NEGATED.containsMatchIn(label) || !AGREE.containsMatchIn(label))

    // What to do once we know the label.
    private val ACTIONS = mapOf(
        "cookie_consent" to "click",      // reject / necessary-only; hide if no such button
        "newsletter" to "click",          // "No thanks" / close; else hide
        "notification_prompt" to "click",
        "app_install" to "click",
        "survey" to "click",
        "paywall_teaser" to "hide",       // nag overlay only, never unlock content
        "login_wall" to "hide",
        "chat_widget" to "ignore",        // usually small and wanted; hidden only if it covers the page
        "content" to "ignore",
    )

    data class Action(val action: String, val target: Int, val buttonLabel: String?)

    fun actionFor(label: String, target: Int, f: Features): Action {
        var action = ACTIONS[label] ?: "ignore"
        var t = target
        if (label == "chat_widget" && f.coverage > 0.3) action = "hide"
        if (action == "click" && !isSafeTarget(f.buttons.getOrNull(t))) {
            // The model picked an agree button or none: find a decline button ourselves, else hide.
            t = f.buttons.indexOfFirst { isSafeTarget(it) }
            if (t < 0) action = "hide"
        }
        val click = action == "click"
        return Action(action, if (click) t else -1, if (click) f.buttons[t] else null)
    }

    /**
     * Verdict from the model's answer. The model only names the label; the button comes from the
     * decline list. [confidence] is null when the backend gives no label probabilities (LiteRT-LM's
     * constrained decoding), and then the label is taken as is. See fromModel() in policy.mjs.
     */
    fun fromModel(label: String, confidence: Double?, f: Features, p: PolicyConfig = PolicyConfig()): Verdict {
        if (label != "content" && confidence != null && confidence < p.llmMinConfidence) return verdict("content", -1, f, "llm-unsure")
        return verdict(label, -1, f, "llm")
    }

    fun verdict(label: String, target: Int, f: Features, source: String): Verdict {
        val a = actionFor(label, target, f)
        return Verdict(label = label, source = source, action = a.action, target = a.target, buttonLabel = a.buttonLabel)
    }

    /** Decide without the model. Returns [Verdict.NEEDS_LLM] when the heuristics are unsure. */
    fun preDecide(f: Features, llmAvailable: Boolean, p: PolicyConfig = PolicyConfig()): Verdict {
        if (f.userInitiated) return verdict("content", -1, f, "user-gesture")
        if (f.isBackdrop) return verdict("content", -1, f, "backdrop") // handled with its modal
        val h = f.heuristic
        if (looksLikeChrome(f)) {
            val navLike = CHROME_TAG.matches(f.tag) || CHROME_ROLE.containsMatchIn(f.role) || CHROME_TOKENS.containsMatchIn(f.tokens)
            // A thin top bar with cookie wording is still a cookie bar.
            val strong = !navLike && h.label != "content" &&
                (h.score >= p.actScore || (h.label == "cookie_consent" && h.score >= p.noLlmActScore))
            return if (strong) verdict(h.label, h.rejectIdx, f, "heuristic") else verdict("content", -1, f, "chrome")
        }
        // No keyword hit but shaped like a blocking modal: the long tail the model is for.
        if (h.label == "content") {
            if (llmAvailable && h.structure >= p.llmStructure && !f.looksLikeLayout) return Verdict.NEEDS_LLM
            return verdict("content", -1, f, "heuristic")
        }
        if (h.score < p.ignoreScore) return verdict("content", -1, f, "heuristic")
        if (h.score >= p.actScore) return verdict(h.label, h.rejectIdx, f, "heuristic")
        if (llmAvailable) return Verdict.NEEDS_LLM
        if (h.score >= p.noLlmActScore) return verdict(h.label, h.rejectIdx, f, "heuristic")
        return verdict("content", -1, f, "heuristic")
    }
}
