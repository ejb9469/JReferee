"""Console progress checks and quiet/verbose training parity."""

import argparse
from contextlib import redirect_stdout
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

import torch

from . import test_neural as fixtures
from .progress import Progress, duration, supports_color, windows_color
from .train import load_policy, main as train


class VerboseTests(unittest.TestCase):
    def test_throttle_and_completion(self):
        stream = io.StringIO()
        progress = Progress(True, stream)
        with patch("neural.progress.time.perf_counter", return_value=0) as clock:
            progress.start("TRAIN", "Epoch 1/3", 10)
            clock.return_value = 0.5
            progress.update(1)
            self.assertEqual(len(stream.getvalue().splitlines()), 1)
            clock.return_value = 2.1
            progress.update(4, "online NLL 2.5")
            clock.return_value = 2.2
            progress.update(8)
            with self.assertRaises(ValueError):
                progress.finish()
            clock.return_value = 2.5
            progress.update(10)
            progress.finish()
        lines = stream.getvalue().splitlines()
        self.assertEqual(len(lines), 3)
        self.assertIn("40.0%", lines[1])
        self.assertIn("ETA ~", lines[1])
        self.assertIn("online NLL 2.5", lines[1])
        self.assertIn("[DONE]", lines[2])
        self.assertIn("100.0%", lines[2])
        self.assertNotIn("\033", stream.getvalue())
        self.assertNotIn("\r", stream.getvalue())
        self.assertEqual(duration(3661), "1:01:01")

    def test_empty_and_quiet(self):
        stream = io.StringIO()
        progress = Progress(True, stream)
        progress.start("CACHE", "Empty split", 0)
        progress.finish()
        self.assertNotIn("inf", stream.getvalue())
        self.assertIn("0/0", stream.getvalue())
        stream = io.StringIO()
        quiet = Progress(False, stream)
        quiet.message("INFO", "hidden")
        quiet.start("TRAIN", "hidden", 2)
        quiet.update(1)
        quiet.finish()
        quiet.summary("INFO", "original output")
        self.assertEqual(stream.getvalue(), "original output\n")

    def test_color_selection(self):
        stream = io.StringIO()
        self.assertFalse(supports_color(stream))
        with patch.object(stream, "isatty", return_value=True):
            with patch.dict("os.environ", {"NO_COLOR": ""}):
                self.assertFalse(supports_color(stream))
            with patch.dict("os.environ", {"TERM": "dumb"}, clear=True):
                self.assertFalse(supports_color(stream))
            with patch.dict("os.environ", {}, clear=True), patch("neural.progress.os.name", "nt"):
                with patch("neural.progress.windows_color", return_value=True) as enable:
                    self.assertTrue(supports_color(stream))
                    enable.assert_called_once_with(stream)
                with patch("neural.progress.windows_color", return_value=False):
                    self.assertFalse(supports_color(stream))
        with patch("neural.progress.supports_color", return_value=True):
            Progress(True, stream).message("TRAIN", "colored")
        self.assertIn("\033[94m[TRAIN]\033[0m", stream.getvalue())

    def test_windows_console_mode(self):
        kernel = Mock()
        kernel.GetConsoleMode.side_effect = lambda handle, mode: setattr(mode._obj, "value", 2) or 1
        kernel.SetConsoleMode.return_value = 1
        runtime = Mock()
        runtime.get_osfhandle.return_value = 123
        stream = Mock()
        stream.fileno.return_value = 1
        with patch.dict("sys.modules", {"msvcrt": runtime}), patch("ctypes.WinDLL", return_value=kernel, create=True):
            self.assertTrue(windows_color(stream))
            runtime.get_osfhandle.assert_called_once_with(1)
            kernel.SetConsoleMode.assert_called_once_with(123, 6)
            kernel.SetConsoleMode.reset_mock()
            kernel.GetConsoleMode.side_effect = None
            kernel.GetConsoleMode.return_value = 0
            self.assertFalse(windows_color(stream))
            kernel.SetConsoleMode.assert_not_called()

    def test_cli_and_training_parity(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            cache = root / "cache"
            fixtures.write_cache(cache, {
                split: [fixtures.example(i, split) for i in range(4)] for split in fixtures.SPLITS})
            (cache / "test-00000" / "options.u8").write_bytes(b"TEST must stay unopened")
            output = root / "verify-must-not-create"
            stream = io.StringIO()
            with redirect_stdout(stream):
                train(["--cache", str(cache), "--output", str(output), "--verify-only", "--verbose"])
            self.assertFalse(output.exists())
            text = stream.getvalue()
            self.assertIn("The orders room", text)
            self.assertIn("Verify TRAIN: 4/4", text)
            self.assertIn("Verify VALIDATION: 4/4", text)
            self.assertNotIn("[TRAIN]", text)
            runs = []
            for name, flag in (("quiet", []), ("verbose", ["-v"])):
                output = root / name
                stream = io.StringIO()
                with redirect_stdout(stream):
                    train(["--cache", str(cache), "--output", str(output), "--epochs", "1",
                           "--hidden", "16", "--threads", "1", "--batch-size", "2",
                           "--validation-samples", "2", *flag])
                runs.append((load_policy(output / "policy.pt", fixtures.SPEC)[0],
                             json.loads((output / "training.json").read_text()), stream.getvalue()))
            quiet, verbose = runs
            for name, value in quiet[0].state_dict().items():
                torch.testing.assert_close(value, verbose[0].state_dict()[name], rtol=0, atol=0)
            self.assertEqual(quiet[1]["settings"], verbose[1]["settings"])
            self.assertEqual(quiet[1]["epochs"][0]["validation"]["overall"],
                             verbose[1]["epochs"][0]["validation"]["overall"])
            self.assertEqual(quiet[1]["epochs"][0]["online_order_nll"], verbose[1]["epochs"][0]["online_order_nll"])
            self.assertNotIn("[TRAIN]", quiet[2])
            self.assertIn("Epoch 1: online NLL", quiet[2])
            for text in ("[TRAIN]", "Initial validation", "Epoch 1 validation", "[SAVE]", "coverage@2"):
                self.assertIn(text, verbose[2])
            self.assertNotIn("\033", verbose[2])
            self.assertTrue(verbose[1]["complete"])
            self.assertFalse(verbose[1]["test_evaluated"])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--spec", type=Path, default=Path("data/movement-neural-cache-v1/specification.json"))
    args = parser.parse_args()
    fixtures.SPEC_RAW = args.spec.read_bytes()
    if hashlib.sha256(fixtures.SPEC_RAW).hexdigest() != fixtures.SPEC_HASH:
        raise ValueError("Self-check needs the supported Java-generated specification")
    fixtures.SPEC = fixtures.parse_json(fixtures.SPEC_RAW)
    unittest.main(argv=["neural.test_verbose"], verbosity=2)


if __name__ == "__main__":
    main()