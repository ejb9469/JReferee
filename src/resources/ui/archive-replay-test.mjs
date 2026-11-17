import test from "node:test";
import assert from "node:assert/strict";
import { tick, timeline, locate, series } from "./archive-replay-model.mjs";

const frame = (year, phase, final = false, centers = 3, score = 0.1) =>
    ({ year, phase, final, centers: { FRANCE: centers }, scores: { FRANCE: score } });
const reference = { frames: [
    frame(1901, "SPRING_MOVEMENT"), frame(1901, "FALL_MOVEMENT"),
    frame(1901, "FALL_RETREAT"), frame(1901, "WINTER_ADJUSTMENT"),
    frame(1902, "SPRING_MOVEMENT"), frame(1902, "WINTER_ADJUSTMENT", true)
] };
const candidate = { frames: [
    frame(1901, "SPRING_MOVEMENT"), frame(1901, "FALL_MOVEMENT"),
    frame(1901, "WINTER_ADJUSTMENT", true, 18, 1)
] };

 test("pairs align by moment, not shifted snapshot indices", () => {
    const moments = timeline([reference, candidate]);
    const retreat = moments.find(moment => moment.phase === "FALL_RETREAT");
    assert.equal(locate(reference, retreat).status, "BEFORE");
    assert.equal(locate(candidate, retreat).status, "ABSENT");
    const winter = moments.find(moment => moment.phase === "WINTER_ADJUSTMENT");
    assert.equal(locate(reference, winter).status, "BEFORE");
    assert.equal(locate(candidate, winter).status, "FINAL");
});

 test("after resolution uses the actual successor and marks an earlier ending", () => {
    const fall = frame(1901, "FALL_MOVEMENT");
    assert.equal(locate(reference, fall, true).boardFrame.phase, "FALL_RETREAT");
    assert.equal(locate(candidate, fall, true).boardFrame.phase, "WINTER_ADJUSTMENT");
    const ended = locate(candidate, frame(1902, "SPRING_MOVEMENT"), true);
    assert.equal(ended.status, "ENDED");
    assert.equal(ended.boardFrame, candidate.frames.at(-1));
    assert.equal(ended.boardFrame.final, true);
});

 test("single-frame terminal games and histories need no fabricated turns", () => {
    const game = { frames: [frame(1901, "SPRING_MOVEMENT", true, 18, 1)] };
    assert.equal(timeline([game]).length, 1);
    assert.equal(locate(game, game.frames[0], true).boardFrame, game.frames[0]);
    assert.deepEqual(series(game, "FRANCE", "scores").map(point => point.value), [1]);
    assert.equal(series(candidate, "FRANCE", "centers").at(-1).time, tick(candidate.frames.at(-1)));
});

 test("malformed chronology and unknown scores fail explicitly", () => {
    assert.throws(() => timeline([{ frames: reference.frames.slice(0, 1) }]), /final/);
    assert.throws(() => timeline([{ frames: [candidate.frames[0], candidate.frames[0], candidate.frames.at(-1)] }]),
        /increasing/);
    assert.throws(() => tick(frame(1901, "UNKNOWN")), /moment/);
    assert.throws(() => series(candidate, "UNKNOWN", "scores"), /score/);
    assert.throws(() => series(candidate, "FRANCE", "predictions"), /metric/);
});