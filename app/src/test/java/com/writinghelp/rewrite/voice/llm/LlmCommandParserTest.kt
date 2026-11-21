package com.writinghelp.rewrite.voice.llm

import com.writinghelp.rewrite.voice.core.ClassifyRequest
import com.writinghelp.rewrite.voice.core.EditContext
import com.writinghelp.rewrite.voice.core.EditResult
import com.writinghelp.rewrite.voice.core.EditorEngine
import com.writinghelp.rewrite.voice.core.FakeTextField
import com.writinghelp.rewrite.voice.core.InputMode
import com.writinghelp.rewrite.voice.core.KeyboardIntent
import com.writinghelp.rewrite.voice.core.Language
import com.writinghelp.rewrite.voice.core.RangeEdit
import com.writinghelp.rewrite.voice.core.RuleBasedClassifier
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeLlmClient(
    private val reply: String = "",
    private val delayMs: Long = 0,
    private val error: LlmException? = null,
) : LlmClient {
    var calls = 0
    var lastUser: String? = null
    override suspend fun complete(system: String, user: String): String {
        calls++
        lastUser = user
        if (delayMs > 0) delay(delayMs)
        error?.let { throw it }
        return reply
    }
}

class LlmCommandParserTest {

    private val text = "See you on Monday. Bring the report."
    private val ctx = EditContext(text, "")

    private fun request(phrase: String = "change Monday to Friday", c: EditContext? = ctx, lang: Language = Language.EN) =
        ClassifyRequest(phrase, lang, InputMode.COMMAND, c)

    private fun rejected(i: KeyboardIntent, msg: String) = assertEquals(KeyboardIntent.Rejected(msg), i)

    @Test fun validJsonBecomesSmartEditAndApplies() = runTest {
        val reply = """{"action":"replace_range","start":11,"end":17,"original":"Monday","text":"Friday","summary":"Changed day"}"""
        val intent = LlmCommandParser(FakeLlmClient(reply)).classify(request())
        val edit = intent as KeyboardIntent.SmartEdit
        assertEquals(RangeEdit(11, 17, "Friday"), edit.edit)
        assertEquals("Changed day", edit.summary)

        val field = FakeTextField("$text|")
        val engine = EditorEngine()
        assertTrue(engine.applyRangeEdit(field, edit.context, edit.edit, edit.summary) is EditResult.Applied)
        assertEquals("See you on Friday. Bring the report.|", field.shown)
        engine.undo(field) // one undo step restores the original
        assertEquals("$text|", field.shown)
    }

    @Test fun codeFencesAreTolerated() {
        val reply = "```json\n{\"action\":\"delete_range\",\"start\":18,\"end\":36,\"text\":\"\"}\n```"
        val intent = LlmCommandParser.parse(reply, ctx, "remove the last sentence") as KeyboardIntent.SmartEdit
        assertEquals(RangeEdit(18, 36, ""), intent.edit)
        assertEquals("Deleted", intent.summary)
    }

    @Test fun malformedJsonIsRejected() {
        rejected(LlmCommandParser.parse("Sure! Here you go: {action: replace}", ctx, "x"), "Couldn't apply")
        rejected(LlmCommandParser.parse("", ctx, "x"), "Couldn't apply")
        rejected(LlmCommandParser.parse("""{"action":"rewrite_everything"}""", ctx, "x"), "Couldn't apply")
        // missing text
        rejected(LlmCommandParser.parse("""{"action":"replace_range","start":0,"end":3}""", ctx, "x"), "Couldn't apply")
        // offsets as strings: strict
        rejected(LlmCommandParser.parse("""{"action":"replace_range","start":"0","end":"3","text":"Hi"}""", ctx, "x"), "Couldn't apply")
        // non-integral offset
        rejected(LlmCommandParser.parse("""{"action":"insert","start":2.5,"text":"Hi"}""", ctx, "x"), "Couldn't apply")
    }

    @Test fun outOfRangeOffsetsAreRejected() {
        fun p(json: String) = LlmCommandParser.parse(json, ctx, "x")
        rejected(p("""{"action":"replace_range","start":30,"end":99,"text":"x"}"""), "Couldn't apply")
        rejected(p("""{"action":"replace_range","start":-1,"end":4,"text":"x"}"""), "Couldn't apply")
        rejected(p("""{"action":"replace_range","start":10,"end":5,"text":"x"}"""), "Couldn't apply")
        rejected(p("""{"action":"insert","start":3,"end":7,"text":"x"}"""), "Couldn't apply")
        // "original" not found anywhere: nothing is applied
        rejected(p("""{"action":"replace_range","start":0,"end":3,"original":"Tuesday","text":"x"}"""), "Couldn't apply")
    }

    @Test fun miscountedOffsetsAreCorrectedByOriginal() {
        // The model is off by two; "original" locates the real span.
        val reply = """{"action":"replace_range","start":13,"end":19,"original":"Monday","text":"Friday"}"""
        val edit = LlmCommandParser.parse(reply, ctx, "x") as KeyboardIntent.SmartEdit
        assertEquals(RangeEdit(11, 17, "Friday"), edit.edit)
    }

    @Test fun timeoutChangesNothing() = runTest {
        val client = FakeLlmClient("""{"action":"none"}""", delayMs = 9_000)
        rejected(LlmCommandParser(client, timeoutMs = 8_000).classify(request()), "Timed out")
    }

    @Test fun clientErrorsAreReported() = runTest {
        rejected(
            LlmCommandParser(FakeLlmClient(error = LlmException(LlmException.Kind.OFFLINE, "x"))).classify(request()),
            "Offline",
        )
        rejected(
            LlmCommandParser(FakeLlmClient(error = LlmException(LlmException.Kind.AUTH, "x"))).classify(request()),
            "Check API key",
        )
    }

    @Test fun noneAndDictationActions() {
        rejected(LlmCommandParser.parse("""{"action":"none","summary":"Nothing to fix"}""", ctx, "x"), "Nothing to fix")
        assertEquals(
            KeyboardIntent.Dictation("make sure you call me"),
            LlmCommandParser.parse("""{"action":"dictation","text":"make sure you call me"}""", ctx, "make sure you call me"),
        )
    }

    @Test fun persianWithZwnj() = runTest {
        val fa = "من به خانه می‌روم. فردا برمی‌گردم."
        val faCtx = EditContext(fa, "")
        val start = fa.indexOf("می‌روم")
        val reply = """{"action":"replace_range","start":$start,"end":${start + 6},"original":"می‌روم",""" +
            """"text":"رفتم","summary":"گذشته"}"""
        val client = FakeLlmClient(reply)
        val intent = LlmCommandParser(client).classify(request("این رو به گذشته تبدیل کن", faCtx, Language.FA))
        val edit = intent as KeyboardIntent.SmartEdit
        assertEquals(RangeEdit(start, start + 6, "رفتم"), edit.edit)
        // The half-space reached the model untouched.
        assertTrue(client.lastUser!!.contains("می‌روم"))

        val field = FakeTextField("$fa|")
        EditorEngine().applyRangeEdit(field, edit.context, edit.edit, edit.summary)
        assertEquals("من به خانه رفتم. فردا برمی‌گردم.|", field.shown)
    }

    @Test fun persianOriginalWithoutZwnjDoesNotMatchSilently() {
        // A model that dropped the half-space quotes text that isn't in the field: reject.
        val fa = "می‌روم"
        val reply = """{"action":"replace_range","start":0,"end":5,"original":"میروم","text":"رفتم"}"""
        rejected(LlmCommandParser.parse(reply, EditContext(fa, ""), "x"), "Couldn't apply")
    }

    @Test fun noContextNoCall() = runTest {
        val client = FakeLlmClient("{}")
        rejected(LlmCommandParser(client).classify(request(c = null)), "Can't read this field")
        assertEquals(0, client.calls)
    }
}

class SmartPipelineTest {
    private val ctx = EditContext("See you on Monday. Bring the report.", "")
    private val editReply = """{"action":"replace_range","start":19,"end":36,"original":"Bring the report.","text":"Please bring the report.","summary":"Made formal"}"""

    private fun pipeline(
        client: FakeLlmClient,
        enabled: Boolean = true,
        online: Boolean = true,
        hasKey: Boolean = true,
        notices: MutableList<String> = mutableListOf(),
    ) = SmartPipeline(
        rules = RuleBasedClassifier(),
        llm = { if (hasKey) LlmCommandParser(client) else null },
        smartEnabled = { enabled },
        isOnline = { online },
        onNotice = { notices += it },
    )

    private fun req(phrase: String, mode: InputMode = InputMode.AUTO, c: EditContext? = ctx) =
        ClassifyRequest(phrase, Language.EN, mode, c)

    @Test fun rulesWinWithoutCallingTheModel() = runTest {
        val client = FakeLlmClient(editReply)
        val i = pipeline(client).classify(req("delete last word"))
        assertTrue(i is KeyboardIntent.Command)
        assertEquals(0, client.calls)
    }

    @Test fun plainSpeechIsDictatedWithoutCallingTheModel() = runTest {
        val client = FakeLlmClient(editReply)
        assertEquals(KeyboardIntent.Dictation("see you soon"), pipeline(client).classify(req("see you soon")))
        assertEquals(0, client.calls)
    }

    @Test fun instructionGoesToTheModel() = runTest {
        val client = FakeLlmClient(editReply)
        val i = pipeline(client).classify(req("make the last sentence more formal"))
        assertEquals("Made formal", (i as KeyboardIntent.SmartEdit).summary)
        assertEquals(1, client.calls)
    }

    @Test fun replaceWhoseTargetIsMissingGoesToTheModel() = runTest {
        val client = FakeLlmClient(editReply)
        pipeline(client).classify(req("change the second sentence to past tense"))
        assertEquals(1, client.calls)
        // A real replace target stays rule-based.
        pipeline(client).classify(req("change Monday to Friday"))
        assertEquals(1, client.calls)
    }

    @Test fun commandModeAlwaysAsks() = runTest {
        val client = FakeLlmClient(editReply)
        pipeline(client).classify(req("please tidy this up", InputMode.COMMAND))
        assertEquals(1, client.calls)
    }

    @Test fun disabledOfflineNoKeyAndSensitiveFallBackToRules() = runTest {
        val client = FakeLlmClient(editReply)
        val notices = mutableListOf<String>()
        assertTrue(pipeline(client, enabled = false).classify(req("fix the grammar")) is KeyboardIntent.Dictation)
        pipeline(client, online = false, notices = notices).classify(req("fix the grammar"))
        val noKey = pipeline(client, hasKey = false, notices = notices)
        noKey.classify(req("fix the grammar"))
        noKey.classify(req("fix the grammar")) // said only once
        pipeline(client, notices = notices).classify(req("fix the grammar", c = null)) // password field
        assertEquals(0, client.calls)
        assertEquals(
            listOf("Offline — using rules only", "Smart edits need an API key — using rules only", "Smart edits are off in this field"),
            notices,
        )
        assertEquals(
            KeyboardIntent.Rejected("Not a command"),
            pipeline(client, enabled = false).classify(req("fix the grammar", InputMode.COMMAND)),
        )
    }

    @Test fun instructionDetection() {
        assertTrue(SmartPipeline.looksLikeInstruction("Fix the grammar"))
        assertTrue(SmartPipeline.looksLikeInstruction("translate that to Persian"))
        assertTrue(SmartPipeline.looksLikeInstruction("remove the part about Monday"))
        assertTrue(SmartPipeline.looksLikeInstruction("جمله آخر رو رسمی‌تر کن"))
        assertTrue(SmartPipeline.looksLikeInstruction("ترجمه کن به انگلیسی"))
        assertTrue(!SmartPipeline.looksLikeInstruction("I had a great day"))
        assertTrue(!SmartPipeline.looksLikeInstruction("امروز هوا خوب است"))
    }
}
