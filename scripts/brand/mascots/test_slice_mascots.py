import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch

from PIL import Image

from slice_mascots import content_box, framed_square, write_drawable_map


class SliceMascotsTest(unittest.TestCase):
    def test_preserves_semitransparent_pixels(self):
        cell = Image.new("RGBA", (100, 100), (255, 255, 255, 128))
        square = framed_square(cell, content_box(cell), 114)
        self.assertEqual(128, square.getpixel((128, 128))[3])

    def test_empty_cell_is_rejected(self):
        self.assertIsNone(content_box(Image.new("RGBA", (100, 100))))

    def test_map_references_only_existing_resources_and_keeps_missing_moods_nullable(self):
        with TemporaryDirectory() as folder:
            drawable = Path(folder) / "mascot_cat_happy.webp"
            drawable.touch()
            mapping = Path(folder) / "MascotDrawables.kt"
            with patch("slice_mascots.OUT", folder), patch("slice_mascots.DRAWABLE_MAP", str(mapping)):
                write_drawable_map()
                source = mapping.read_text()
                self.assertIn("Mood.HAPPY -> R.drawable.mascot_cat_happy", source)
                self.assertIn("Mood.CONTENT -> null", source)
                self.assertEqual(1, source.count("R.drawable."))
                drawable.unlink()
                write_drawable_map()
                self.assertNotIn("R.drawable.", mapping.read_text())


if __name__ == "__main__":
    unittest.main()
