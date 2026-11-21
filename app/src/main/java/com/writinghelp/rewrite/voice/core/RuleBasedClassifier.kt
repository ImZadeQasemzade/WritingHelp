package com.writinghelp.rewrite.voice.core

/** Offline regex matcher over [CommandPhrases]. Pure Kotlin — unit tested on the JVM. */
class RuleBasedClassifier(
    private val rules: List<CommandPhrases.Rule> = CommandPhrases.all,
) : IntentClassifier {

    /** Returns the command if [phrase] is one, otherwise null. */
    fun match(phrase: String): KeyboardIntent.Command? {
        val norm = TextNormalizer.forMatching(phrase)
        if (norm.isEmpty()) return null
        for (rule in rules) {
            val m = rule.pattern.matchEntire(norm) ?: continue
            val args = m.groupValues.drop(1).map(TextNormalizer::cleanArg)
            if (rule.type == CommandType.REPLACE) {
                if (args.size != 2 || args.any { it.isEmpty() }) continue
                return KeyboardIntent.Command(rule.type, originalArgs(phrase, args))
            }
            return KeyboardIntent.Command(rule.type, args)
        }
        return null
    }

    override suspend fun classify(request: ClassifyRequest): KeyboardIntent = classifySync(request)

    fun classifySync(request: ClassifyRequest): KeyboardIntent {
        val text = request.phrase.trim()
        // A mode name said on its own switches mode from anywhere, including DICTATE.
        match(text)?.takeIf { it.type.switchesTo != null }?.let { return it }
        return when (request.mode) {
            InputMode.DICTATE -> KeyboardIntent.Dictation(text)
            InputMode.COMMAND -> match(text) ?: KeyboardIntent.Rejected("Not a command")
            InputMode.AUTO -> match(text) ?: KeyboardIntent.Dictation(text)
        }
    }

    /**
     * Matching ran on a lower-cased, ZWNJ-free copy. Recover each argument's original spelling
     * from the phrase so "replace Monday with Tuesday" inserts "Tuesday", and Persian half-spaces
     * survive.
     */
    private fun originalArgs(phrase: String, normArgs: List<String>): List<String> {
        val source = TextNormalizer.persianLetters(phrase)
        var searchFrom = 0
        return normArgs.map { arg ->
            val pattern = arg.split(' ').joinToString("[\\s\\u200C]+") { Regex.escape(it) }
            val found = Regex(pattern, RegexOption.IGNORE_CASE).find(source, searchFrom)
            if (found != null) {
                searchFrom = found.range.last + 1
                found.value
            } else arg
        }
    }
}
