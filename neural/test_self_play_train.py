"""Focused data, gradient, causality and checkpoint checks for typed policy learning."""

import argparse
from contextlib import ExitStack, redirect_stdout
from dataclasses import replace
import io
import json
import math
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from . import self_play_cache as reader
from . import self_play_data as data_api
from . import test_self_play_cache as fixtures


VALUE_RAW = MOVEMENT_RAW = None


def prepare_spec(path=None):
    global VALUE_RAW, MOVEMENT_RAW
    if path is not None:
        VALUE_RAW = path.read_bytes()
        reader.require(reader.sha(VALUE_RAW) == reader.VALUE_HASH, "Wrong frozen value specification")
        text = VALUE_RAW.decode("utf-8")
        start = text.index('"board_specification":') + len('"board_specification":')
        while text[start].isspace():
            start += 1
        _, end = json.JSONDecoder().raw_decode(text, start)
        MOVEMENT_RAW = (text[start:end] + "\n").encode("utf-8")
        reader.require(reader.sha(MOVEMENT_RAW) == reader.MOVEMENT_HASH, "Wrong embedded movement specification")
    else:
        spec = json.loads(fixtures.synthetic_spec())["board_specification"]
        spec.update(
            schema="jreferee-neural-movement-v1",
            board_locations=[i for i in range(1, 82) if spec["canonical_locations"][i] == i],
            home_owners=[0] * 75, powers=list(reader.POWERS),
            year_origin=1901, year_scale=20,
        )
        MOVEMENT_RAW = (json.dumps(spec, separators=(",", ":")) + "\n").encode()
        VALUE_RAW = (json.dumps({"board_specification": spec}) + "\n").encode()


class FixtureCase(unittest.TestCase):
    def setUp(self):
        if VALUE_RAW is None:
            prepare_spec()
        stack = ExitStack()
        self.addCleanup(stack.close)
        stack.enter_context(patch.object(reader, "VALUE_HASH", reader.sha(VALUE_RAW)))
        stack.enter_context(patch.object(reader, "MOVEMENT_HASH", reader.sha(MOVEMENT_RAW)))
        stack.enter_context(patch.object(fixtures, "SPEC_BYTES", VALUE_RAW))
        temporary = stack.enter_context(tempfile.TemporaryDirectory(prefix="jreferee-policy-train-"))
        self.directory = Path(temporary)
        self.anchor_path = self.directory / "anchor.bin"
        self.anchor_path.write_bytes(b"synthetic immutable anchor identity")
        self.anchor_hash = reader.sha(self.anchor_path.read_bytes())
        self.path = self.make_cache("cache")
        self.data = data_api.PolicyData([reader.SelfPlayCache(self.path)], self.anchor_hash)
        self.spec = self.data.spec

    def make_cache(self, name, only_train=False):
        path = fixtures.fixture(self.directory / name, only_train=only_train)
        index_path = path / "games.jsonl"
        games = [json.loads(line) for line in index_path.read_text().splitlines()]
        for game in games:
            seat = game["configuration"]["seats"]["FRANCE"]
            seat["root"] = "COHERENT_GRU_HYBRID"
            seat["movement_models"] = {
                "anchor": self.anchor_hash, "gru": reader.sha(b"producer-java-model"),
                "checkpoint": reader.sha(b"producer-checkpoint"), "dataset": reader.sha(b"producer-dataset"),
            }
        fixtures.write_lines(index_path, games)
        fixtures.reseal(path)
        return path

    def roots(self):
        return next(self.data.batches("TRAIN", 2))[1]


class DataTests(FixtureCase):
    def test_type_transform_excludes_outcomes_and_selection(self):
        root = self.roots()[0]
        self.assertEqual(data_api.type_features(root),
                         (-2.0, -2.0, -1.5, -1.0, -0.5, 0.25, 0.25, 0.25, 0.25, 0.0, 1.0))
        changed = replace(root, selected=1 - root.selected, log_priors=(-0.1,) * 3,
                          historical_means=(0.9,) * 3, metadata={"ignored": "outcome/source diagnostics"})
        self.assertEqual(data_api.type_features(root), data_api.type_features(changed))
        final = replace(root, type_ids=(1, 1))
        self.assertEqual(data_api.type_features(final)[-2:], (1.0, 0.0))
        with self.assertRaisesRegex(ValueError, "positive"):
            data_api.type_features(replace(root, types=(0.0,) + root.types[1:]))

    def test_whole_actions_and_chunk_normalization(self):
        roots = self.roots()
        tiny = list(data_api.candidate_chunks(roots, 1, 4))
        wide = list(data_api.candidate_chunks(roots, 32, 100))
        wanted = [(0, 0), (0, 1), (1, 0), (1, 1)]
        self.assertEqual([reference for chunk in tiny for reference in chunk.references], wanted)
        self.assertEqual([reference for chunk in wide for reference in chunk.references], wanted)
        self.assertTrue(all(chunk.root_count == 2 for chunk in tiny + wide))
        logp = [[-0.6, -2.0], [-1.1, -0.8]]
        expected = (0.75 * 0.6 + 0.25 * 2.0 + 0.75 * 1.1 + 0.25 * 0.8) / 2
        for chunks in (tiny, wide):
            actual = math.fsum(-roots[r].probabilities[c] * logp[r][c] / chunk.root_count
                               for chunk in chunks for r, c in chunk.references)
            self.assertAlmostEqual(actual, expected, places=14)
        self.assertEqual(roots[0].actions, ((0, 0), (1, 1), (0, 1)))

    def test_bounds_and_invalid_mass_fail(self):
        roots = self.roots()
        with self.assertRaisesRegex(ValueError, "max-options"):
            list(data_api.candidate_chunks(roots, 32, 3))
        with self.assertRaisesRegex(ValueError, "probability mass"):
            list(data_api.candidate_chunks([replace(roots[0], probabilities=(0.6, 0.6, 0.0))], 32, 100))
        with self.assertRaisesRegex(ValueError, "Incomplete candidate"):
            list(data_api.candidate_chunks([replace(roots[0], actions=((0,), (1, 1), (0, 1)))], 32, 100))

    def test_frozen_anchor_and_generator_cohort(self):
        with self.assertRaisesRegex(ValueError, "human anchor"):
            data_api.PolicyData([reader.SelfPlayCache(self.path)], "f" * 64)
        path = self.path / "games.jsonl"
        games = [json.loads(line) for line in path.read_text().splitlines()]
        games[0]["configuration"]["seats"]["FRANCE"]["root"] = "UNIFORM"
        fixtures.write_lines(path, games)
        fixtures.reseal(self.path)
        with self.assertRaisesRegex(ValueError, "COHERENT_GRU_HYBRID"):
            data_api.PolicyData([reader.SelfPlayCache(self.path)], self.anchor_hash)

    def test_split_and_batch_guards(self):
        data = data_api.PolicyData([reader.SelfPlayCache(self.make_cache("smoke", True))], self.anchor_hash)
        data.preflight(4)
        with self.assertRaisesRegex(ValueError, "nonempty TRAIN and VALIDATION"):
            data.preflight(4, training=True)
        with self.assertRaisesRegex(ValueError, "Only TRAIN"):
            list(data.batches("TEST", 2))
        first = [row for group, _ in self.data.batches("TRAIN", 1, 7) for row in group]
        second = [row for group, _ in self.data.batches("TRAIN", 1, 7) for row in group]
        self.assertEqual(first, second)
        self.assertEqual(set(first), set(self.data.records["TRAIN"]))


class NumericalTests(FixtureCase):
    def setUp(self):
        super().setUp()
        import torch
        from . import model as base_api
        from . import train as legacy
        from . import self_play_model as model_api
        from . import self_play_train as train_api
        self.torch, self.model_api, self.train_api = torch, model_api, train_api
        for module in (legacy, model_api, train_api):
            context = patch.object(module, "SPEC_HASH", reader.sha(MOVEMENT_RAW))
            context.start()
            self.addCleanup(context.stop)
        torch.set_num_threads(1)
        torch.use_deterministic_algorithms(True)
        torch.manual_seed(7)
        base = base_api.MovementGRU(self.spec, 16)
        self.initial_path = self.directory / "initial.pt"
        cache_info = SimpleNamespace(
            spec_text=MOVEMENT_RAW.decode(), manifest={"dataset_index_sha256": reader.sha(b"historical-index")},
            manifest_hash=reader.sha(b"historical-cache"),
        )
        legacy.save_policy(self.initial_path, base, cache_info, 1)
        self.base = base
        self.model, self.specification, self.source = model_api.load_start(
            self.initial_path, self.spec, self.anchor_hash, self.data.split_seed)
        self.settings = train_api.Settings(epochs=2, batch_size=3, candidate_batch_size=1,
                                          max_options=4, learning_rate=0.002, weight_decay=0, threads=1)

    def batch(self, roots, references=None):
        if references is None:
            chunk = next(data_api.candidate_chunks(roots, 32, 100))
        else:
            chunk = data_api.CandidateChunk(tuple(references), len(roots))
        return self.model_api.CandidateBatch.from_chunk(roots, chunk)

    def test_zero_adapter_preserves_baseline(self):
        roots = self.roots()
        batch = self.batch(roots)
        self.assertEqual(self.model.hidden, self.base.hidden)
        self.assertEqual(self.torch.count_nonzero(self.model.type_projection.weight).item(), 0)
        for (old, _), (new, _) in zip(self.base(batch), self.model(batch)):
            self.torch.testing.assert_close(old, new, rtol=0, atol=0)
        changed = [replace(root, selected=1 - root.selected, log_priors=(-0.1,) * 3,
                           historical_means=(0.9,) * 3, metadata={"ignored": "outcomes"}) for root in roots]
        self.torch.testing.assert_close(
            self.model_api.joint_log_probabilities(self.model, batch),
            self.model_api.joint_log_probabilities(self.model, self.batch(changed)), rtol=0, atol=0)

    def test_loss_is_not_pool_normalized(self):
        root = self.roots()[0]
        single = replace(root, actions=(root.actions[0],), probabilities=(1.0,))
        batch = self.batch([single])
        output = self.model(batch)
        wanted = -sum(logp[step.targets].double().sum() for step, (logp, _) in zip(batch.steps, output))
        joint = self.model_api.joint_log_probabilities(self.model, batch)
        loss = self.model_api.weighted_loss(joint, batch)
        self.assertGreater(loss.item(), 0.01)
        self.torch.testing.assert_close(loss, wanted)
        with self.assertRaisesRegex(ValueError, "Zero-weight"):
            self.batch([root], [(0, 2)])

    def test_chunked_gradients_and_independent_weighted_loss(self):
        roots = self.roots()
        short = roots[1]
        board = list(short.board)
        row = self.spec["location_board_rows"][short.locations[1]]
        board[row] = (0, 0, 0, board[row][3])
        roots[1] = replace(short, board=tuple(board), locations=short.locations[:1], counts=short.counts[:1],
                           options=short.options[:short.counts[0]], actions=((0,), (1,)),
                           probabilities=(0.75, 0.25))
        gradients, totals = [], []
        for candidates, options in ((32, 100), (1, 4)):
            self.model.zero_grad(set_to_none=True)
            total = 0.0
            for chunk in data_api.candidate_chunks(roots, candidates, options):
                batch = self.model_api.CandidateBatch.from_chunk(roots, chunk)
                joint = self.model_api.joint_log_probabilities(self.model, batch)
                loss = self.model_api.weighted_loss(joint, batch)
                loss.backward()
                total += loss.item()
            gradients.append({name: p.grad.detach().clone() for name, p in self.model.named_parameters()
                              if p.grad is not None})
            totals.append(total)
        self.assertAlmostEqual(totals[0], totals[1], places=5)
        self.assertEqual(gradients[0].keys(), gradients[1].keys())
        for name in gradients[0]:
            self.torch.testing.assert_close(gradients[0][name], gradients[1][name], rtol=2e-4, atol=2e-6)
        expected = 0.0
        with self.torch.no_grad():
            for root in roots:
                for action, weight in zip(root.actions, root.probabilities):
                    if weight:
                        single = replace(root, actions=(action,), probabilities=(1.0,))
                        expected -= weight * self.model_api.joint_log_probabilities(
                            self.model, self.batch([single])).item() / len(roots)
        self.assertAlmostEqual(totals[0], expected, places=5)

    def test_type_gradient_finite_difference(self):
        self.model.double()
        batch = self.batch(self.roots())
        batch.type_features = batch.type_features.double()
        self.model.zero_grad(set_to_none=True)
        loss = self.model_api.weighted_loss(
            self.model_api.joint_log_probabilities(self.model, batch), batch)
        loss.backward()
        parameter = self.model.type_projection.weight
        index = divmod(parameter.grad.abs().argmax().item(), parameter.shape[1])
        analytic, original = parameter.grad[index].item(), parameter[index].item()
        self.assertGreater(abs(analytic), 1e-10)
        values, epsilon = [], 1e-5
        with self.torch.no_grad():
            for direction in (1, -1):
                parameter[index] = original + direction * epsilon
                values.append(self.model_api.weighted_loss(
                    self.model_api.joint_log_probabilities(self.model, batch), batch).item())
            parameter[index] = original
        numerical = (values[0] - values[1]) / (2 * epsilon)
        self.assertAlmostEqual(analytic, numerical, delta=max(1e-6, abs(analytic) * 1e-4))

    def test_autoregressive_causality_and_type_input(self):
        root = self.roots()[0]
        make = lambda action: replace(root, actions=(action,), probabilities=(1.0,))
        original = self.model(self.batch([make((0, 0))]))
        future = self.model(self.batch([make((0, 1))]))
        prefix = self.model(self.batch([make((1, 0))]))
        for (left, _), (right, _) in zip(original, future):
            self.torch.testing.assert_close(left, right, rtol=0, atol=0)
        self.torch.testing.assert_close(original[0][0], prefix[0][0], rtol=0, atol=0)
        self.assertFalse(self.torch.equal(original[1][0], prefix[1][0]))
        with self.torch.no_grad():
            self.model.type_projection.weight[:, 0] = 0.1
        first = make((0, 0))
        other = replace(first, types=(0.001,) + first.types[1:])
        self.assertFalse(self.torch.equal(self.model(self.batch([first]))[0][0],
                                          self.model(self.batch([other]))[0][0]))

    def test_joint_target_learning(self):
        model = self.model_api.TypeConditionedGRU(self.spec, 32)
        root = self.roots()[0]
        batch = self.batch([root])
        optimizer = self.torch.optim.AdamW(model.parameters(), lr=0.01, weight_decay=0)
        for _ in range(200):
            optimizer.zero_grad(set_to_none=True)
            loss = self.model_api.weighted_loss(self.model_api.joint_log_probabilities(model, batch), batch)
            loss.backward()
            self.torch.nn.utils.clip_grad_norm_(model.parameters(), 1, error_if_nonfinite=True)
            optimizer.step()
        self.assertLess(loss.item(), 0.9)
        probe = replace(root, actions=((0, 0), (0, 1), (1, 0), (1, 1)), probabilities=(0.25,) * 4)
        with self.torch.no_grad():
            probabilities = self.model_api.joint_log_probabilities(model, self.batch([probe])).exp()
        self.assertAlmostEqual(probabilities.sum().item(), 1.0, places=5)
        self.assertLess((probabilities[1] + probabilities[2]).item(), 0.15)

    def test_fit_selection_reload_and_frozen_inputs(self):
        before = (self.initial_path.read_bytes(), self.anchor_path.read_bytes())
        output = self.directory / "trained"
        with redirect_stdout(io.StringIO()):
            selected, report = self.train_api.fit(self.data, self.initial_path, output, self.settings)
        scores = [report["initial_validation"]["cross_entropy"]]
        scores.extend(row["validation"]["cross_entropy"] for row in report["epochs"])
        self.assertEqual(report["best_epoch"], min(range(len(scores)), key=scores.__getitem__))
        self.assertTrue(report["complete"])
        self.assertFalse(report["anchor_updated"] or report["value_trained"] or report["test_evaluated"])
        self.assertEqual(before, (self.initial_path.read_bytes(), self.anchor_path.read_bytes()))
        loaded, payload, checksum = self.model_api.load_typed_policy(output / "policy.pt", self.spec)
        self.assertEqual(checksum, report["checkpoint_sha256"])
        batch = self.batch(self.roots())
        self.torch.testing.assert_close(
            self.model_api.joint_log_probabilities(selected, batch),
            self.model_api.joint_log_probabilities(loaded, batch), rtol=0, atol=0)
        warm, _, _ = self.model_api.load_start(output / "policy.pt", self.spec, self.anchor_hash, self.data.split_seed)
        self.torch.testing.assert_close(warm.type_projection.weight, loaded.type_projection.weight)
        for field, value, message in (
                ("anchor_sha256", "f" * 64, "anchor mismatch"),
                ("split_seed", "8", "split seed mismatch")):
            changed = dict(payload)
            changed[field] = value
            path = self.directory / f"bad-{field}.pt"
            self.torch.save(changed, path)
            with self.assertRaisesRegex(ValueError, message):
                self.model_api.load_start(path, self.spec, self.anchor_hash, self.data.split_seed)
        changed = dict(payload)
        changed["state_dict"] = dict(payload["state_dict"])
        changed["state_dict"]["rows"] = changed["state_dict"]["rows"].clone()
        changed["state_dict"]["rows"][0] = 0
        path = self.directory / "bad-map.pt"
        self.torch.save(changed, path)
        with self.assertRaisesRegex(ValueError, "Checkpoint map"):
            self.model_api.load_typed_policy(path, self.spec)
        changed = dict(payload)
        changed["type_encoding"] = dict(payload["type_encoding"], output_encoding="omitted")
        path = self.directory / "bad-type.pt"
        self.torch.save(changed, path)
        with self.assertRaisesRegex(ValueError, "type encoding"):
            self.model_api.load_typed_policy(path, self.spec)
        changed = dict(payload)
        changed["state_dict"] = dict(payload["state_dict"])
        del changed["state_dict"]["type_projection.weight"]
        path = self.directory / "missing-adapter.pt"
        self.torch.save(changed, path)
        with self.assertRaisesRegex(ValueError, "tensor names"):
            self.model_api.load_typed_policy(path, self.spec)
        changed["state_dict"]["type_projection.weight"] = payload["state_dict"]["type_projection.weight"].clone()
        changed["state_dict"]["type_projection.weight"][0, 0] = float("nan")
        path = self.directory / "nonfinite-adapter.pt"
        self.torch.save(changed, path)
        with self.assertRaisesRegex(ValueError, "Invalid checkpoint tensor"):
            self.model_api.load_typed_policy(path, self.spec)

    def test_epoch_zero_wins_ties(self):
        output = self.directory / "unchanged"
        with patch.object(self.torch.optim.AdamW, "step", lambda optimizer, closure=None: None):
            with redirect_stdout(io.StringIO()):
                _, report = self.train_api.fit(self.data, self.initial_path, output, replace(self.settings, epochs=1))
        self.assertEqual(report["best_epoch"], 0)
        self.assertFalse(report["improved_over_initial"])

    def test_verify_only_and_preflight_failures(self):
        empty = data_api.PolicyData([reader.SelfPlayCache(self.make_cache("only-train", True))], self.anchor_hash)
        with patch.object(self.torch.optim, "AdamW", side_effect=AssertionError("verify created an optimizer")):
            with redirect_stdout(io.StringIO()):
                result = self.train_api.verify(empty, self.initial_path, self.settings)
        self.assertEqual(result["splits"]["VALIDATION"]["status"], "EMPTY_SPLIT")
        for data, settings in ((empty, self.settings), (self.data, replace(self.settings, max_options=3))):
            output = self.directory / "must-not-exist"
            with self.assertRaises(ValueError):
                self.train_api.fit(data, self.initial_path, output, settings)
            self.assertFalse(output.exists())
        output = self.directory / "existing"
        output.mkdir()
        marker = output / "keep"
        marker.write_text("untouched")
        with self.assertRaises(FileExistsError):
            self.train_api.fit(self.data, self.initial_path, output, self.settings)
        self.assertEqual(marker.read_text(), "untouched")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--spec", type=Path, help="value/specification.json from the verified Java cache")
    parser.add_argument("--data-only", action="store_true", help="Run only standard-library checks; no gradient tests")
    args = parser.parse_args()
    prepare_spec(args.spec)
    if args.spec is None:
        print("Synthetic map fixture with test-only hashes; production specification checks are unchanged.")
    classes = [DataTests] if args.data_only else [DataTests, NumericalTests]
    suite = unittest.TestSuite(unittest.defaultTestLoader.loadTestsFromTestCase(cls) for cls in classes)
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    raise SystemExit(0 if result.wasSuccessful() else 1)


if __name__ == "__main__":
    main()