package com.writinghelp.rewrite.voice.llm

import com.writinghelp.rewrite.voice.core.ClassifyRequest
import com.writinghelp.rewrite.voice.core.CommandType
import com.writinghelp.rewrite.voice.core.InputMode
import com.writinghelp.rewrite.voice.core.IntentClassifier
import com.writinghelp.rewrite.voice.core.KeyboardIntent
import com.writinghelp.rewrite.voice.core.RuleBasedClassifier
import com.writinghelp.rewrite.voice.core.TextNormalizer

/**
 * AUTO / COMMAND pipeline:
 *  1. rule-based matcher (fast, offline);
 *  2. if no rule matched and the phrase looks like an instruction (or mode is COMMAND),
 *     ask the LLM — when smart edits are on, a key is set, the device is online and the field
 *     isn't sensitive;
 *  3. otherwise dictation (AUTO) or "Not a command" (COMMAND).
 */
class SmartPipeline(
    private val rules: RuleBasedClassifier,
    /** Null when smart edits are off or no API key is stored. */
    private val llm: () -> IntentClassifier?,
    private val smartEnabled: () -> Boolean,
    private val isOnline: () -> Boolean,
    /** Called before a (slow) LLM request so the UI can show a spinner. */
    private val onThinking: () -> Unit = {},
    /** One-time notices such as "No API key — rules only". */
    private val onNotice: (String) -> Unit = {},
) : IntentClassifier {

    private val shownNotices = mutableSetOf<String>()

    override suspend fun classify(request: ClassifyRequest): KeyboardIntent {
        if (request.mode == InputMode.DICTATE) return rules.classifySync(request)

        val rule = rules.match(request.phrase)
        if (rule != null && !(rule.type == CommandType.REPLACE && replaceTargetMissing(request, rule.args[0]))) {
            return rule
        }

        val wantsLlm = request.mode == InputMode.COMMAND || rule != null || looksLikeInstruction(request.phrase)
        if (wantsLlm && smartEnabled()) {
            val parser = llm()
            when {
                parser == null -> notice("Smart edits need an API key — using rules only")
                request.context == null -> notice("Smart edits are off in this field")
                !isOnline() -> notice("Offline — using rules only")
                else -> {
                    onThinking()
                    return parser.classify(request)
                }
            }
        }
        // Fallback: the rule (even a replace whose target wasn't seen — the editor reports it).
        if (rule != null) return rule
        return rules.classifySync(request)
    }

    private fun notice(text: String) {
        if (shownNotices.add(text)) onNotice(text)
    }

    /** "change the second sentence to past tense" parses as REPLACE but isn't one. */
    private fun replaceTargetMissing(request: ClassifyRequest, target: String): Boolean {
        val before = request.context?.let { it.before + it.selected } ?: return false
        fun norm(s: String) = TextNormalizer.forMatching(s).replace(" ", "")
        return !norm(before).contains(norm(target))
    }

    companion object {
        private val EN_VERBS = Regex(
            "^(?:please |can you |could you )?(?:make|change|rewrite|fix|correct|translate|remove|delete|" +
                "shorten|expand|rephrase|reword|convert|turn|capitalize|summarize|simplify|move|swap|put|" +
                "add|insert|replace|format|polish|improve|split|merge|combine|reorder|spell|punctuate)\\b",
            RegexOption.IGNORE_CASE,
        )
        private val FA_START = Regex("^(?:لطفا |لطفاً )?(?:ترجمه|اصلاح|خلاصه|بازنویسی|درست)")
        private val FA_IMPERATIVE = Regex("(?:کن|بکن|بده|بنویس|کنید|بدید|بنویسید)$")
        private val FA_OBJECT = Regex(" (?:رو|را) ")

        fun looksLikeInstruction(phrase: String): Boolean {
            val p = TextNormalizer.forMatching(phrase)
            if (EN_VERBS.containsMatchIn(p)) return true
            if (FA_START.containsMatchIn(p)) return true
            return FA_IMPERATIVE.containsMatchIn(p) && FA_OBJECT.containsMatchIn(" $p ")
        }
    }
}
