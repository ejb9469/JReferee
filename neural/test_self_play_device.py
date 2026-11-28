"""Device configuration checks; CUDA-path fixtures execute tensors on CPU."""

from contextlib import contextmanager, redirect_stdout
from dataclasses import replace
import io
import json
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import torch
from torch.utils import deterministic

from . import self_play_model as models
from . import self_play_train as training
from . import test_self_play_train as fixtures
from .self_play_cache import sha


class RuntimeTests(unittest.TestCase):
    def setUp(self):
        self.addCleanup(torch.set_default_dtype, torch.get_default_dtype())
        self.addCleanup(setattr, deterministic, "fill_uninitialized_memory",
                        deterministic.fill_uninitialized_memory)
        self.enterContext(patch.dict(training.os.environ, {
            "CUBLAS_WORKSPACE_CONFIG": ":4096:8", "TORCH_ALLOW_TF32_CUBLAS_OVERRIDE": "0",
        }))

    def test_cpu_defaults_leave_precision_and_allocation_policy_unchanged(self):
        torch.set_default_dtype(torch.float64)
        deterministic.fill_uninitialized_memory = False
        with patch.object(training, "_cuda_precision", side_effect=AssertionError("CUDA precision")), \
                patch.object(torch.cuda, "is_available", side_effect=AssertionError("CUDA lookup")):
            runtime = training.configure(training.Settings(threads=1))
            with training._allocation_fill(training.Settings()):
                self.assertFalse(deterministic.fill_uninitialized_memory)
        self.assertEqual(torch.get_default_dtype(), torch.float64)
        self.assertFalse(runtime["fill_uninitialized_memory"])
        self.assertEqual(runtime["precision"], {"api": "unchanged"})
        self.assertIsNone(runtime["gpu"])
        self.assertTrue(runtime["deterministic_algorithms"])

    def test_cuda_precision_selects_one_supported_api(self):
        for modern in (False, True):
            with self.subTest(modern=modern):
                cudnn = SimpleNamespace(benchmark=True, deterministic=False, allow_tf32=True)
                matmul = SimpleNamespace(allow_tf32=True)
                backends = SimpleNamespace(cudnn=cudnn, cuda=SimpleNamespace(matmul=matmul))
                if modern:
                    backends.fp32_precision = matmul.fp32_precision = cudnn.fp32_precision = "tf32"
                    cudnn.conv = SimpleNamespace(fp32_precision="tf32")
                    cudnn.rnn = SimpleNamespace(fp32_precision="tf32")
                torch.set_default_dtype(torch.float64)
                with patch.object(torch, "backends", backends), \
                        patch.object(torch, "set_float32_matmul_precision") as legacy:
                    precision = training._cuda_precision()
                self.assertEqual(torch.get_default_dtype(), torch.float32)
                self.assertFalse(cudnn.benchmark)
                self.assertTrue(cudnn.deterministic)
                if modern:
                    legacy.assert_not_called()
                    self.assertEqual(precision, {"api": "fp32_precision", "values": ["ieee"] * 5})
                    self.assertTrue(matmul.allow_tf32 and cudnn.allow_tf32)
                else:
                    legacy.assert_called_once_with("highest")
                    self.assertEqual(precision, {"api": "allow_tf32", "matmul": False, "cudnn": False})
                    self.assertFalse(matmul.allow_tf32 or cudnn.allow_tf32)

    def test_invalid_flags_and_cuda_environments_fail_explicitly(self):
        with self.assertRaisesRegex(ValueError, "requires --device cuda"):
            training.Settings(fill_uninitialized_memory=False)
        with self.assertRaisesRegex(ValueError, "boolean"):
            training.Settings(device="cuda", fill_uninitialized_memory=0)
        settings = training.Settings(device="cuda", threads=1)
        with patch.object(torch.cuda, "is_available", return_value=False):
            with self.assertRaisesRegex(ValueError, "unavailable"):
                training.configure(settings)
        for key, value, message in (
                ("CUBLAS_WORKSPACE_CONFIG", ":16:8", "CUBLAS_WORKSPACE_CONFIG"),
                ("TORCH_ALLOW_TF32_CUBLAS_OVERRIDE", "1", "TF32")):
            with patch.dict(training.os.environ, {key: value}), \
                    patch.object(torch.cuda, "is_available", return_value=True), \
                    patch.object(training, "_cuda_precision") as precision:
                with self.assertRaisesRegex(ValueError, message):
                    training.configure(settings)
                precision.assert_not_called()

    def test_cuda_allocation_scope_restores_nested_settings_and_failures(self):
        torch.use_deterministic_algorithms(True)
        on = training.Settings(device="cuda")
        off = replace(on, fill_uninitialized_memory=False)
        deterministic.fill_uninitialized_memory = True
        with training._allocation_fill(off):
            self.assertFalse(deterministic.fill_uninitialized_memory)
            with training._allocation_fill(on):
                self.assertTrue(deterministic.fill_uninitialized_memory)
            self.assertFalse(deterministic.fill_uninitialized_memory)
        self.assertTrue(deterministic.fill_uninitialized_memory)
        with self.assertRaisesRegex(RuntimeError, "fixture"):
            with training._allocation_fill(off):
                raise RuntimeError("fixture")
        self.assertTrue(deterministic.fill_uninitialized_memory)
        with self.assertRaisesRegex(ValueError, "settings changed"):
            with training._allocation_fill(off):
                deterministic.fill_uninitialized_memory = True
        try:
            torch.use_deterministic_algorithms(False)
            with self.assertRaisesRegex(ValueError, "require deterministic"):
                with training._allocation_fill(off):
                    self.fail("Invalid runtime was accepted")
        finally:
            torch.use_deterministic_algorithms(True)


class DeviceFitTests(fixtures.FixtureCase):
    def setUp(self):
        super().setUp()
        self.addCleanup(setattr, deterministic, "fill_uninitialized_memory",
                        deterministic.fill_uninitialized_memory)
        deterministic.fill_uninitialized_memory = True
        self.enterContext(patch.dict(training.os.environ, {
            "CUBLAS_WORKSPACE_CONFIG": ":4096:8", "TORCH_ALLOW_TF32_CUBLAS_OVERRIDE": "0",
        }))
        for module in (models, training):
            self.enterContext(patch.object(module, "SPEC_HASH", sha(fixtures.MOVEMENT_RAW)))
        self.settings = training.Settings(epochs=2, batch_size=1, candidate_batch_size=1,
                                          max_options=4, threads=1)
        training.configure(self.settings)
        self.initial = self.directory / "initial.pt"
        model = models.TypeConditionedGRU(self.spec, 16)
        with torch.no_grad():
            model.type_projection.weight.fill_(0.025)
        models.save_typed_policy(
            self.initial, model, self.data, fixtures.MOVEMENT_RAW.decode(),
            {"sha256": sha(b"supervised ancestor"), "schema": models.BASE_SCHEMA, "epoch": 1}, 3, 1.0)

    @contextmanager
    def cuda_on_cpu(self):
        original_to, original_batch = torch.nn.Module.to, models.CandidateBatch.from_chunk

        def move(model, device):
            self.assertEqual(device, "cuda")
            return original_to(model, "cpu")

        def batch(roots, chunk, device):
            self.assertEqual(device, "cuda")
            return original_batch(roots, chunk, "cpu")

        gpu = SimpleNamespace(name="CPU fixture only", major=0, minor=0, total_memory=0)
        with patch.object(torch.cuda, "is_available", return_value=True), \
                patch.object(torch.cuda, "current_device", return_value=0), \
                patch.object(torch.cuda, "get_device_properties", return_value=gpu), \
                patch.object(training, "_cuda_precision", return_value={"api": "CPU fixture only"}), \
                patch.object(torch.nn.Module, "to", move), \
                patch.object(models.CandidateBatch, "from_chunk", side_effect=batch):
            yield

    def test_full_fit_readback_and_receipts_preserve_guard_on_off_results(self):
        sources = [self.initial, self.anchor_path] + [p for p in self.path.rglob("*") if p.is_file()]
        before = {path: sha(path.read_bytes()) for path in sources}
        with redirect_stdout(io.StringIO()):
            cpu, baseline = training.fit(self.data, self.initial, self.directory / "cpu", self.settings)
        for enabled in (True, False):
            settings = replace(self.settings, device="cuda", fill_uninitialized_memory=enabled)
            original_loss, original_finish = training._cross_entropies, training._finish_update
            phases = []

            def loss(*args, **kwargs):
                self.assertIs(deterministic.fill_uninitialized_memory, enabled)
                phases.append("train" if kwargs.get("backward") else "evaluate")
                return original_loss(*args, **kwargs)

            def finish(*args):
                self.assertIs(deterministic.fill_uninitialized_memory, enabled)
                phases.append("optimizer")
                return original_finish(*args)

            output = self.directory / ("cuda-on" if enabled else "cuda-off")
            with self.cuda_on_cpu(), patch.object(training, "_cross_entropies", side_effect=loss), \
                    patch.object(training, "_finish_update", side_effect=finish), redirect_stdout(io.StringIO()):
                selected, report = training.fit(self.data, self.initial, output, settings)
            self.assertTrue(deterministic.fill_uninitialized_memory)
            self.assertEqual(set(phases), {"train", "evaluate", "optimizer"})
            self.assertTrue(report["complete"])
            self.assertEqual(report["runtime"]["device"], "cuda")
            self.assertEqual(report["initial_policy"]["schema"], models.MODEL_SCHEMA)
            self.assertEqual(report["initial_policy"]["epoch"], 3)
            self.assertIs(report["runtime"]["fill_uninitialized_memory"], enabled)
            self.assertTrue(report["runtime"]["deterministic_algorithms"])
            self.assertEqual(report, json.loads((output / "training.json").read_text()))
            self.assertEqual(report["best_epoch"], baseline["best_epoch"])
            self.assertEqual(report["best_validation_cross_entropy"], baseline["best_validation_cross_entropy"])
            self.assertEqual([row["online_cross_entropy"] for row in report["epochs"]],
                             [row["online_cross_entropy"] for row in baseline["epochs"]])
            loaded, payload, digest = models.load_typed_policy(output / "policy.pt", self.spec)
            self.assertEqual(digest, report["checkpoint_sha256"])
            self.assertEqual(payload["epoch"], report["best_epoch"])
            for name, value in cpu.state_dict().items():
                torch.testing.assert_close(selected.state_dict()[name], value, rtol=0, atol=0)
                torch.testing.assert_close(loaded.state_dict()[name], value, rtol=0, atol=0)
        self.assertEqual(before, {path: sha(path.read_bytes()) for path in sources})

    def test_training_and_verification_errors_restore_fill_and_keep_incomplete_output(self):
        settings = replace(self.settings, device="cuda", fill_uninitialized_memory=False)

        def failure(*args, **kwargs):
            self.assertFalse(deterministic.fill_uninitialized_memory)
            raise RuntimeError("fixture failure")

        output = self.directory / "failed"
        with self.cuda_on_cpu(), patch.object(training, "_finish_update", side_effect=failure), \
                redirect_stdout(io.StringIO()), self.assertRaisesRegex(RuntimeError, "fixture failure"):
            training.fit(self.data, self.initial, output, settings)
        self.assertTrue(deterministic.fill_uninitialized_memory)
        self.assertFalse(json.loads((output / "training.json").read_text())["complete"])
        with self.cuda_on_cpu(), patch.object(training, "_cross_entropies", side_effect=failure), \
                patch.object(torch.optim, "AdamW", side_effect=AssertionError("Unexpected optimizer")), \
                redirect_stdout(io.StringIO()), self.assertRaisesRegex(RuntimeError, "fixture failure"):
            training.verify(self.data, self.initial, settings)
        self.assertTrue(deterministic.fill_uninitialized_memory)

    def test_cli_routes_opt_in_and_unavailable_cuda_creates_no_output(self):
        arguments = ["--cache", str(self.path), "--initial-policy", str(self.initial),
                     "--anchor", str(self.anchor_path), "--verify-only"]
        with patch.object(training, "verify") as verify, redirect_stdout(io.StringIO()):
            training.main(arguments)
            self.assertEqual(verify.call_args.args[2].device, "cpu")
            self.assertTrue(verify.call_args.args[2].fill_uninitialized_memory)
            training.main(arguments + ["--device", "cuda", "--no-fill-uninitialized-memory"])
            self.assertEqual(verify.call_args.args[2].device, "cuda")
            self.assertFalse(verify.call_args.args[2].fill_uninitialized_memory)
        with self.assertRaisesRegex(ValueError, "requires --device cuda"):
            training.main(arguments + ["--no-fill-uninitialized-memory"])
        output = self.directory / "missing-cuda"
        with patch.object(torch.cuda, "is_available", return_value=False):
            with self.assertRaisesRegex(ValueError, "unavailable"):
                training.fit(self.data, self.initial, output, replace(self.settings, device="cuda"))
        self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main(verbosity=2)