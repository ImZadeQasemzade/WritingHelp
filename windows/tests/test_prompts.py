"""Run from windows/:  .venv\\Scripts\\python -m unittest discover tests"""

import unittest

from writinghelp.prompts import (
    Feature, Mode, explain_input, explain_prompt, parse_bullets, system_prompt_for,
)


class ParseBulletsTest(unittest.TestCase):
    def test_strips_markers_and_numbering(self):
        reply = "• 'has went' → 'has gone': past participle\n- comma before 'but'\n* tone\n1. one\n2) two"
        self.assertEqual(
            parse_bullets(reply),
            ["'has went' → 'has gone': past participle", "comma before 'but'", "tone", "one", "two"],
        )

    def test_drops_blank_lines_and_keeps_inner_dashes(self):
        self.assertEqual(parse_bullets("\n  • well-written — fine  \n\n"), ["well-written — fine"])

    def test_persian_bullets(self):
        self.assertEqual(parse_bullets("• «رفتم» درست است"), ["«رفتم» درست است"])


class PromptsTest(unittest.TestCase):
    def test_every_mode_has_a_prompt_with_the_tag_rules(self):
        for mode in Mode:
            self.assertIn("<output>", system_prompt_for(mode, "English", "Persian"))

    def test_write_prompt_uses_main_language(self):
        self.assertIn("Write in Persian", system_prompt_for(Mode.WRITE, "Persian", "English"))

    def test_explain(self):
        self.assertIn("explanations in English", explain_prompt("English"))
        self.assertEqual(explain_input("a", "b"), "ORIGINAL:\na\n\nREVISED:\nb")

    def test_features_cover_every_mode(self):
        for mode in Mode:
            self.assertIs(Feature.for_mode(mode).mode, mode)
        self.assertIsNone(Feature.EXPLAIN.mode)


if __name__ == "__main__":
    unittest.main()
