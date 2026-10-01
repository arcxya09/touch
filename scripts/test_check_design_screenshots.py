import json
from pathlib import Path
import tempfile
import unittest

from PIL import Image

from check_design_screenshots import compare


class ScreenshotComparisonTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="touch-screenshots-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.baseline, self.candidate, self.output = (self.root / name for name in ("baseline", "candidate", "output"))
        self.baseline.mkdir()
        self.candidate.mkdir()

    def image(self, folder, name="screen.png", size=(10, 10), color=(0, 0, 0, 255)):
        target = folder / name
        target.parent.mkdir(parents=True, exist_ok=True)
        Image.new("RGBA", size, color).save(target)
        return target

    def test_exact_match_preserves_baseline_and_writes_json(self):
        source = self.image(self.baseline, "dark/chat.png")
        original = source.read_bytes()
        self.image(self.candidate, "dark/chat.png")
        self.assertTrue(compare(self.baseline, self.candidate, self.output)["passed"])
        report = json.loads((self.output / "comparison.json").read_text(encoding="utf-8"))
        self.assertEqual(report["results"][0]["changed_pixels"], 0)
        self.assertEqual(source.read_bytes(), original)

    def test_change_fails_and_outputs_visible_difference(self):
        self.image(self.baseline)
        path = self.image(self.candidate)
        with Image.open(path) as image:
            image.putpixel((0, 0), (20, 0, 0, 255))
            image.save(path)
        report = compare(self.baseline, self.candidate, self.output)
        self.assertFalse(report["passed"])
        self.assertEqual(report["results"][0]["changed_pixels"], 1)
        with Image.open(self.output / report["results"][0]["diff_image"]) as difference:
            self.assertEqual(difference.getpixel((0, 0)), (255, 20, 20))
            self.assertEqual(difference.getpixel((1, 1)), (0, 0, 0))

    def test_pixel_and_ratio_threshold_boundaries_include_alpha(self):
        self.image(self.baseline)
        path = self.image(self.candidate)
        with Image.open(path) as image:
            image.putpixel((0, 0), (0, 0, 0, 235))
            image.save(path)
        self.assertTrue(compare(self.baseline, self.candidate, self.output, pixel_threshold=20)["passed"])
        self.assertFalse(compare(self.baseline, self.candidate, self.output, pixel_threshold=19)["passed"])
        self.assertTrue(compare(self.baseline, self.candidate, self.output, max_diff_ratio=0.01)["passed"])
        self.assertFalse(compare(self.baseline, self.candidate, self.output, max_diff_ratio=0.009)["passed"])

    def test_size_mismatch_cannot_be_tolerated(self):
        self.image(self.baseline)
        self.image(self.candidate, size=(11, 10))
        report = compare(self.baseline, self.candidate, self.output, 255, 1)
        self.assertFalse(report["passed"])
        self.assertEqual(report["results"][0]["status"], "size_mismatch")

    def test_missing_and_unexpected_images_cannot_be_tolerated(self):
        self.image(self.baseline, "missing.png")
        self.image(self.candidate, "unexpected.png")
        report = compare(self.baseline, self.candidate, self.output, 255, 1)
        self.assertFalse(report["passed"])
        self.assertEqual([item["status"] for item in report["results"]], ["missing", "unexpected"])

    def test_empty_baseline_invalid_png_and_output_inside_inputs_fail(self):
        self.assertFalse(compare(self.baseline, self.candidate, self.output)["passed"])
        self.image(self.baseline)
        (self.candidate / "screen.png").write_bytes(b"not a PNG")
        self.assertEqual(compare(self.baseline, self.candidate, self.output)["results"][0]["status"], "invalid_image")
        with self.assertRaisesRegex(ValueError, "outside"):
            compare(self.baseline, self.candidate, self.baseline / "report")


if __name__ == "__main__":
    unittest.main()
