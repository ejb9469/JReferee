"use strict";

// Reproducible real Chromium regression: node src/testing/StrategyBrowserDomFixture.js
// No browser library or dependencies; production HTML, JS, CSS and SVG are served unchanged.
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const os = require("node:os");
const http = require("node:http");
const { spawn } = require("node:child_process");

const root = path.resolve(__dirname, "../resources/ui");
let scratch;
const fixture = JSON.parse(fs.readFileSync(path.join(root, "fixtures/strategy-response.json")));
fixture.board.supplyCenterOwners.Bel = "FRANCE"; // Vacant recorded center, not inferred from units.
const colors = { AUSTRIA: "#c43131", ENGLAND: "#426dcc", FRANCE: "#53aecb",
    GERMANY: "#444444", ITALY: "#459f45", RUSSIA: "#a87fc4", TURKEY: "#cfae35" };
const defaults = { nation: "ENGLAND", year: "1901", phase: "SPRING_MOVEMENT",
    policy: "REFERENCE_FILTERED", coordinationMode: "CONDITIONAL",
    units: fixture.board.units.map(unit => `${unit.nation} ${unit.type} ${unit.province}`).join("\n"),
    centers: "Lon ENGLAND\nSpa FRANCE\nStp RUSSIA\nBul TURKEY\nBel FRANCE", objectives: "",
    centerWeight: "1", dislodgementPenalty: "3", caution: "0.5", tacticalBiasWeight: "5",
    minimum: "3", choices: "4", plans: "8", scenarios: "2" };
const config = { humanWeight: 2, globalValues: { NTH: { value: 1, unitTypes: ["FLEET"] } },
    nationProfiles: Object.fromEntries(Object.keys(colors).map(nation =>
        [nation, { adjustments: {}, objectives: [] }])) };
config.nationProfiles.ENGLAND.objectives = [{ name: "<img src=x onerror=alert(1)>",
    targets: ["Bel", "Hol"], priority: 2, unitTypes: ["ARMY", "FLEET"], horizon: 4, decay: 0.5 }];
fixture.context.scoringConfiguration = config;
fixture.coordination.geographyGuided = true;
fixture.coordination.replenishedChoices = 2;
fixture.coordination.guidanceChoicesUnexamined = 3;
fixture.coordination.geographicChoiceLimit = 64;
fixture.context.limits.geographicChoiceLimit = 64;
fixture.context.limits.geographicSearchWorkBudget = 8000000;
for (const plan of fixture.plans) {
    plan.shapedMean = plan.mean + 1;
    plan.shapedWorst = plan.worst + 1;
    plan.shapedScore = plan.baseScore + 1;
    plan.humanWeight = 2;
    plan.humanContribution = 2 * Math.log(0.4);
    plan.score = plan.shapedScore - plan.penaltyTotal + plan.humanContribution;
    plan.scoringConfiguration = config;
    plan.humanPreference = { available: true, score: Math.log(0.4), diagnostic: "",
        units: plan.orders.map(order => ({
            identity: { nation: "ENGLAND", unitType: fixture.board.units
                .find(unit => unit.province === order.origin).type, origin: order.origin },
            order, selectedCount: 4, denominator: 10, basis: "FULL-policy-fixture",
            available: true, logFrequency: Math.log(0.4), diagnostic: "" })) };
    for (const scenario of plan.scenarios) {
        scenario.provinceContribution = 0.25;
        scenario.regionalContribution = 0.75;
        scenario.augmentedScore = scenario.score + 1;
        scenario.shaping = { units: [{ identity: { nation: "ENGLAND", unitType: "FLEET", origin: "Lon" },
            before: "Lon", after: "NTH", beforeValue: 0, afterValue: 0.25, contribution: 0.25,
            surviving: true }], objectives: [{ name: config.nationProfiles.ENGLAND.objectives[0].name,
            beforePotential: 0.25, afterPotential: 1, contribution: 0.75,
            units: [{ identity: { unitType: "FLEET", origin: "Lon" }, beforeDistance: 3, afterDistance: 2,
                beforePotential: 0.25, afterPotential: 1 },
                { identity: { unitType: "ARMY", origin: "Wal" }, beforeDistance: -1, afterDistance: -1,
                    beforePotential: 0, afterPotential: 0 }] }] };
    }
    for (const comparison of plan.comparisons ?? []) {
        comparison.positionalDelta = 0;
        comparison.humanDelta = 0;
    }
}

let lastForm;
let delayed = false;
let browser;
let socket;
let server;
let session;
const pending = new Map();
let sequence = 0;

function command(method, params = {}, sessionId = session) {
    return new Promise((resolve, reject) => {
        const id = ++sequence;
        pending.set(id, { resolve, reject });
        socket.send(JSON.stringify({ id, method, params, ...(sessionId ? { sessionId } : {}) }));
    });
}

async function evaluate(expression) {
    const response = await command("Runtime.evaluate", { expression, awaitPromise: true, returnByValue: true });
    if (response.exceptionDetails)
        throw new Error(response.exceptionDetails.exception?.description ?? response.exceptionDetails.text);
    return response.result.value;
}

async function wait(expression) {
    for (let attempt = 0; attempt < 150; attempt++) {
        if (await evaluate(expression)) return;
        await new Promise(resolve => setTimeout(resolve, 30));
    }
    throw new Error("Browser condition timed out: " + expression);
}

async function action(source) {
    await evaluate(`(() => { ${source} })()`);
}

async function main() {
    const scratchParent = os.tmpdir();
    fs.mkdirSync(scratchParent, { recursive: true });
    scratch = fs.mkdtempSync(path.join(scratchParent, "strategy-dom-"));
    server = http.createServer((request, response) => {
        const url = new URL(request.url, "http://127.0.0.1");
        response.setHeader("Cache-Control", "no-store");
        if (url.pathname === "/api/bootstrap") {
            response.setHeader("Content-Type", "application/json");
            response.end(JSON.stringify({ token: "dom-fixture", ruleset: "fixture-only",
                trainingGames: 0, colors, defaults, board: fixture.board }));
        } else if (url.pathname === "/api/openings") {
            response.setHeader("Content-Type", "application/json");
            response.end(JSON.stringify({ board: fixture.board, colors, scanned: 6, represented: 6,
                skipped: 0, nationalOpenings: 6, diagnostics: [], openings: [
                    { nation: "ENGLAND", signature: "popular", count: 3, orders: fixture.plans[0].orders },
                    { nation: "ENGLAND", signature: "singleton", count: 1,
                        orders: [{ type: "HOLD", origin: "Lon", target: null, auxiliaryTarget: null, text: "F Lon H" }] },
                    { nation: "FRANCE", signature: "france", count: 2,
                        orders: [{ type: "HOLD", origin: "SpaSC", target: null, auxiliaryTarget: null, text: "F SpaSC H" }] }
                ] }));
        } else if (url.pathname === "/api/generate") {
            let body = "";
            request.on("data", chunk => { body += chunk; });
            request.on("end", () => {
                lastForm = Object.fromEntries(new URLSearchParams(body));
                const send = () => {
                    response.setHeader("Content-Type", "application/json");
                    const value = structuredClone(fixture);
                    value.board.supplyCenterOwners = Object.fromEntries(lastForm.centers.trim()
                        .split("\n").filter(Boolean).map(row => row.trim().split(/\s+/)));
                    response.end(JSON.stringify(value));
                };
                if (delayed) setTimeout(send, 350);
                else send();
            });
        } else {
            const relative = url.pathname === "/" ? "strategy.html" : url.pathname.replace(/^\/ui\//, "");
            const filename = path.resolve(root, relative);
            if (!filename.startsWith(root + path.sep) || !fs.existsSync(filename)) {
                response.writeHead(404).end();
                return;
            }
            response.setHeader("Content-Type", ({ ".html": "text/html", ".js": "text/javascript",
                ".css": "text/css", ".svg": "image/svg+xml" })[path.extname(filename)] ?? "text/plain");
            response.end(fs.readFileSync(filename));
        }
    });
    await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
    const origin = `http://127.0.0.1:${server.address().port}`;
    browser = spawn(process.env.CHROMIUM ?? "chromium", ["--headless", "--no-sandbox",
        "--disable-gpu", "--no-first-run", "--no-default-browser-check", "--remote-debugging-port=0",
        `--user-data-dir=${scratch}`, `--disk-cache-dir=${scratch}/cache`, "about:blank"],
    { env: { ...process.env, TMPDIR: scratch, DBUS_SESSION_BUS_ADDRESS: "disabled:" },
        stdio: ["ignore", "ignore", "pipe"] });
    const endpoint = await new Promise((resolve, reject) => {
        let output = "";
        const timer = setTimeout(() => {
            clearInterval(poll);
            reject(new Error("Chromium debugger not ready: " + output));
        }, 30000);
        const poll = setInterval(() => {
            const filename = path.join(scratch, "DevToolsActivePort");
            if (!fs.existsSync(filename)) return;
            const [port, endpointPath] = fs.readFileSync(filename, "utf8").trim().split("\n");
            clearInterval(poll);
            clearTimeout(timer);
            resolve(`ws://127.0.0.1:${port}${endpointPath}`);
        }, 100);
        browser.once("error", reject);
        browser.stderr.on("data", data => {
            output += data;
            const match = output.match(/DevTools listening on (ws:\/\/\S+)/);
            if (match) { clearInterval(poll); clearTimeout(timer); resolve(match[1]); }
        });
    });
    socket = new WebSocket(endpoint);
    await new Promise((resolve, reject) => {
        socket.addEventListener("open", resolve, { once: true });
        socket.addEventListener("error", reject, { once: true });
    });
    socket.addEventListener("message", event => {
        const value = JSON.parse(event.data);
        if (!pending.has(value.id)) return;
        const operation = pending.get(value.id);
        pending.delete(value.id);
        if (value.error) operation.reject(new Error(value.error.message));
        else operation.resolve(value.result);
    });
    const target = await command("Target.createTarget", { url: origin }, null);
    session = (await command("Target.attachToTarget", { targetId: target.targetId, flatten: true }, null)).sessionId;
    await command("Runtime.enable");
    await wait('document.querySelector("#fields")?.disabled === false');
    const labels = await evaluate('document.body.textContent.replace(/\\s+/g, " ")');
    assert.match(labels, /Distinct moves into your HOLD, SUPPORT, or CONVOY unit/);
    assert.match(labels, /first 64 historical observed choices per unit/);
    assert.match(labels, /Exact fleet coasts such as SpaNC are allowed/);
    assert.equal(await evaluate('document.querySelectorAll(".unit-token").length'), fixture.board.units.length);
    assert.equal(await evaluate('getComputedStyle(document.querySelector("#provinces > #bel")).fill'), "rgb(212, 235, 242)");
    assert.equal(await evaluate('getComputedStyle(document.querySelector("#g5246 circle")).fill'), "rgb(83, 174, 203)");
    assert.equal(await evaluate('document.querySelectorAll(\'[aria-label="FRANCE FLEET in SpaSC"]\').length'), 1);
    await action(`document.querySelector("#example-scoring").click();
        const nation = document.querySelector("#nation");
        nation.value = "GERMANY"; nation.dispatchEvent(new Event("change", {bubbles:true}));`);
    assert.match(await evaluate('document.querySelector("#profile-adjustments").value'), /Pru=-1/);
    await action(`const a = document.querySelector("#profile-adjustments"); a.value = "Pru=-7/ARMY";
        a.dispatchEvent(new Event("input", {bubbles:true}));
        const nation = document.querySelector("#nation"); nation.value = "FRANCE";
        nation.dispatchEvent(new Event("change", {bubbles:true}));`);
    assert.equal(await evaluate('document.querySelector("#profile-adjustments").value'), "");
    await action(`const nation = document.querySelector("#nation"); nation.value = "GERMANY";
        nation.dispatchEvent(new Event("change", {bubbles:true}));`);
    assert.equal(await evaluate('document.querySelector("#profile-adjustments").value'), "Pru=-7/ARMY");
    await action(`const nation = document.querySelector("#nation"); nation.value = "ENGLAND";
        nation.dispatchEvent(new Event("change", {bubbles:true}));
        document.querySelector('[name="compareAlternatives"]').checked = true;
        document.querySelector("#editor").requestSubmit();`);
    await wait('document.querySelector("#plan-inspector").hidden === false');
    assert.equal(Object.keys(lastForm).filter(key => key.startsWith("adjustments.")).length, 7);
    assert.equal(Object.keys(lastForm).filter(key => key.startsWith("regions.")).length, 7);
    assert.equal(lastForm["adjustments.GERMANY"], "Pru=-7/ARMY");
    assert.match(await evaluate('document.querySelector("#human-units").textContent'), /4\/10 FULL selected/);
    assert.equal(await evaluate('document.querySelectorAll("#human-units > li").length'), fixture.plans[0].orders.length);
    assert.match(await evaluate('document.querySelector("#human-summary").textContent'), /Final = shaped/);
    assert.match(await evaluate('document.querySelector("#coordination-summary").textContent'),
        /geography guided: yes; 2 positional choices replenished; 3 guidance choices unexamined/);
    assert.match(await evaluate('document.querySelector("#result-context").textContent'), /historical top K UNION positional top K/);
    assert.match(await evaluate('document.querySelector("#result-context").textContent'), /historical guidance scan cap 64\/unit/);
    assert.match(await evaluate('document.querySelector("#result-context").textContent'), /geographic search work budget 8000000 shaping entries/);
    assert.match(await evaluate('document.querySelector("#province-shaping").textContent'), /effective province value/);
    assert.match(await evaluate('document.querySelector("#regional-shaping").textContent'), /targets Bel, Hol/);
    assert.match(await evaluate('document.querySelector("#regional-shaping").textContent'),
        /static movement distance unreachable → unreachable/);
    assert.equal(await evaluate('document.querySelectorAll("#regional-shaping img").length'), 0);
    assert.match(await evaluate('document.querySelector("#regional-shaping").textContent'), /<img src=x/);
    await action(`document.querySelector("#show-dependencies").checked = true;
        document.querySelector("#show-dependencies").dispatchEvent(new Event("change"));`);
    assert.ok(await evaluate('document.querySelectorAll(".requirement-marker").length') > 0);
    await action('document.querySelector(".requirement-marker").focus();');
    await command("Input.dispatchKeyEvent", { type: "keyDown", key: "Enter", code: "Enter", windowsVirtualKeyCode: 13 });
    await command("Input.dispatchKeyEvent", { type: "keyUp", key: "Enter", code: "Enter", windowsVirtualKeyCode: 13 });
    assert.match(await evaluate('document.querySelector("#requirement-details").textContent'), /unconfirmed|not a confirmed/);
    await command("Emulation.setDeviceMetricsOverride", { width: 390, height: 844, deviceScaleFactor: 1, mobile: true });
    await action(`const token = [...document.querySelectorAll(".unit-token")]
        .find(item => item.getAttribute("aria-label").includes("FRANCE"));
        token.focus();`);
    await command("Input.dispatchKeyEvent", { type: "keyDown", key: " ", code: "Space", windowsVirtualKeyCode: 32 });
    await command("Input.dispatchKeyEvent", { type: "keyUp", key: " ", code: "Space", windowsVirtualKeyCode: 32 });
    assert.match(await evaluate('document.querySelector("#province-details").textContent'), /FRANCE/);
    await action(`document.querySelector("#next").click();`);
    assert.match(await evaluate('document.querySelector("#plan-comparisons").textContent'), /positional\/shaping Δ/);
    await action(`const selector = document.querySelector("#scenario-selector"); selector.value = "1";
        selector.dispatchEvent(new Event("change"));`);
    assert.match(await evaluate('document.querySelector("#scenario-metrics").textContent'), /augmented = raw/);
    await action(`document.querySelector('[name="humanWeight"]').value = "99";`);
    assert.match(await evaluate('document.querySelector("#frozen-scoring").textContent'), /"humanWeight": 2/);
    await action(`document.querySelector('[name="humanWeight"]').dispatchEvent(new Event("input", {bubbles:true}));`);
    assert.equal(await evaluate('document.querySelector("#plan-inspector").hidden'), true);
    delayed = true;
    await action('document.querySelector("#editor").requestSubmit(); document.querySelector("#cancel-generation").click();');
    await new Promise(resolve => setTimeout(resolve, 450));
    assert.equal(await evaluate('document.querySelector("#plan-inspector").hidden'), true);
    assert.equal(await evaluate('document.querySelector("#fields").disabled'), false);
    delayed = false;
    fixture.context.tacticalBiasWeight = 0;
    for (const plan of fixture.plans) {
        plan.penaltyWeight = 0;
        plan.penaltyTotal = 0;
        plan.score = plan.shapedScore + plan.humanContribution;
        for (const comparison of plan.comparisons ?? [])
            if (comparison.status === "EVALUATED") {
                comparison.penaltyDelta = 0;
                comparison.adjustedScoreDelta = comparison.scoreDelta;
            }
    }
    await action(`const centers = document.querySelector('[name="centers"]'); centers.value = "";
        document.querySelector('[name="tacticalBiasWeight"]').value = "0";
        centers.dispatchEvent(new Event("input", {bubbles:true}));
        document.querySelector("#editor").requestSubmit();`);
    await wait('document.querySelector("#plan-inspector").hidden === false');
    assert.match(await evaluate('document.querySelector("#bias-summary").textContent'),
        /Zero tactical weight disables collision-first ordering; geographic guidance may still reorder search/);
    assert.match(await evaluate('document.querySelector("#bias-summary").textContent'),
        /Geography-guided search enabled; scan cap 64\/unit; replenished 2; unexamined 3/);
    assert.equal(await evaluate('getComputedStyle(document.querySelector("#provinces > #bel")).fill'), "rgb(226, 198, 158)");
    assert.notEqual(await evaluate('getComputedStyle(document.querySelector("#g5246 circle")).fill'), "rgb(83, 174, 203)");
    await action('document.querySelector("#reset-position").click();');
    assert.equal(await evaluate('getComputedStyle(document.querySelector("#provinces > #bel")).fill'), "rgb(212, 235, 242)");
    // Actual file input/JSON import, without replacing FileReader, fetch, or application functions.
    const game = { format: "jreferee-strategy-game", version: 1, gameLabel: "DOM import",
        phases: [{ snapshot: 1, board: fixture.board, sourceStatus: "SOURCE_SNAPSHOT",
            orders: [{ nation: "ENGLAND", type: "HOLD", origin: "Lon", target: null, auxiliaryTarget: null,
                text: "HISTORICAL_SOURCE_ONLY F Lon H" }],
            missingSubmissionCount: fixture.board.units.length - 1 }] };
    const gamePath = path.join(scratch, "game.json");
    fs.writeFileSync(gamePath, JSON.stringify(game));
    await action('document.querySelector(\'[name="objectives"]\').value = "Bel 99";');
    await command("DOM.enable");
    const documentNode = await command("DOM.getDocument");
    const fileNode = await command("DOM.querySelector", { nodeId: documentNode.root.nodeId, selector: "#game-file" });
    await command("DOM.setFileInputFiles", { nodeId: fileNode.nodeId, files: [gamePath] });
    await wait('document.querySelector("#import-phase-button").disabled === false');
    assert.equal(await evaluate('document.querySelector(\'[name="objectives"]\').value'), "");
    await action('document.querySelector("#import-phase-button").click();');
    assert.equal(await evaluate('document.querySelectorAll(".unit-token").length'), fixture.board.units.length);
    assert.equal(await evaluate('document.querySelector(\'[name="adjustments.GERMANY"]\').value'), "Pru=-7/ARMY");
    await action('document.querySelector("#show-historical").click();');
    assert.match(await evaluate('document.querySelector("#selection-title").textContent'), /HISTORICAL SUBMISSIONS/);
    assert.equal(await evaluate('document.querySelectorAll("#opening-orders > g").length'), 1);
    assert.match(await evaluate('document.querySelector("#historical-orders").textContent'), /HISTORICAL_SOURCE_ONLY/);
    await action('document.querySelector("#editor").requestSubmit();');
    await wait('document.querySelector("#plan-inspector").hidden === false');
    assert.ok(!Object.values(lastForm).some(value => value.includes("HISTORICAL_SOURCE_ONLY")));
    assert.match(await evaluate('document.querySelector("#result-context").textContent'), /Imported source snapshot; snapshot 1; status SOURCE_SNAPSHOT/);
    await action('document.querySelector("#show-historical").click();');
    assert.equal(await evaluate('document.querySelector("#plan-inspector").hidden'), true);
    await action('document.querySelector("#opening-list button").click();');
    assert.match(await evaluate('document.querySelector("#selection-title").textContent'), /candidate rank/);
    assert.equal(await evaluate('document.querySelectorAll("#opening-orders > g").length'), fixture.plans[0].orders.length);
    const profilePath = path.join(scratch, "profiles.json");
    fs.writeFileSync(profilePath, JSON.stringify({ format: "jreferee-scoring-profiles", version: 1,
        humanWeight: "3", globalValues: "ION=4/FLEET",
        nationProfiles: Object.fromEntries(Object.keys(colors).map(nation =>
            [nation, { adjustments: nation === "ENGLAND" ? "NTH=2/FLEET" : "",
                regions: nation === "ENGLAND" ? "coast SpaNC,SpaSC 2 FLEET 4 0.5" : "" }])) }));
    const profileNode = await command("DOM.querySelector", { nodeId: documentNode.root.nodeId, selector: "#scoring-file" });
    await command("DOM.setFileInputFiles", { nodeId: profileNode.nodeId, files: [profilePath] });
    await wait('document.querySelector(\'[name="humanWeight"]\').value === "3"');
    assert.equal(await evaluate('document.querySelector("#profile-adjustments").value'), "NTH=2/FLEET");
    await command("Browser.setDownloadBehavior", { behavior: "allow", downloadPath: scratch }, null);
    await action('document.querySelector("#export-scoring").click();');
    const exportedPath = path.join(scratch, "jreferee-scoring-profiles.json");
    for (let attempt = 0; attempt < 100 && !fs.existsSync(exportedPath); attempt++)
        await new Promise(resolve => setTimeout(resolve, 30));
    assert.ok(fs.existsSync(exportedPath), "Profile export did not download");
    const exported = JSON.parse(fs.readFileSync(exportedPath));
    assert.equal(Object.keys(exported.nationProfiles).length, 7);
    assert.equal(exported.nationProfiles.ENGLAND.adjustments, "NTH=2/FLEET");
    assert.equal(exported.nationProfiles.ENGLAND.regions, "coast SpaNC,SpaSC 2 FLEET 4 0.5");
    await action('document.querySelector("#clear-profile").click(); document.querySelector("#reset-scoring").click();');
    assert.equal(await evaluate('document.querySelector(\'[name="globalValues"]\').value'), "");
    assert.equal(await evaluate('document.querySelector(\'[name="humanWeight"]\').value'), "0");
    const delayedProfilePath = path.join(scratch, "delayed-profiles.json");
    fs.writeFileSync(delayedProfilePath, JSON.stringify({ ...exported, humanWeight: "77", globalValues: "ION=77/FLEET" }));
    // Delay only the native File.text promise, not application functions or import data.
    await action(`window.__nativeFileText = File.prototype.text;
        File.prototype.text = function () {
            if (this.name !== "delayed-profiles.json") return window.__nativeFileText.call(this);
            return new Promise(resolve => {
                window.__releaseScoringRead = () => window.__nativeFileText.call(this).then(resolve);
            });
        };`);
    await command("DOM.setFileInputFiles", { nodeId: profileNode.nodeId, files: [delayedProfilePath] });
    await wait('typeof window.__releaseScoringRead === "function"');
    await action('document.querySelector("#reset-scoring").click();');
    await evaluate(`(async () => {
        await window.__releaseScoringRead();
        File.prototype.text = window.__nativeFileText;
        delete window.__nativeFileText;
        delete window.__releaseScoringRead;
        await new Promise(resolve => setTimeout(resolve, 100));
    })()`);
    assert.equal(await evaluate('document.querySelector(\'[name="humanWeight"]\').value'), "0",
        "Late file import overwrote an explicit reset");
    assert.equal(await evaluate('document.querySelector(\'[name="globalValues"]\').value'), "");
    const openingTarget = await command("Target.createTarget", { url: origin + "/ui/openings.html" }, null);
    session = (await command("Target.attachToTarget", { targetId: openingTarget.targetId, flatten: true }, null)).sessionId;
    await wait('document.querySelector("#nation")?.disabled === false');
    assert.equal(await evaluate('document.querySelectorAll("#opening-list button").length'), 2);
    assert.match(await evaluate('document.querySelector("#opening-list button").textContent'), /75.0%/);
    await action('document.querySelector("#next").click();');
    assert.equal(await evaluate('document.querySelector("#position").textContent'), "2 / 2");
    await action('document.querySelector("#previous").click(); document.activeElement.blur();');
    await command("Input.dispatchKeyEvent", { type: "keyDown", key: "ArrowRight", code: "ArrowRight", windowsVirtualKeyCode: 39 });
    await command("Input.dispatchKeyEvent", { type: "keyUp", key: "ArrowRight", code: "ArrowRight", windowsVirtualKeyCode: 39 });
    assert.equal(await evaluate('document.querySelector("#position").textContent'), "2 / 2");
    await action('document.querySelector("#hide-singletons").click();');
    assert.equal(await evaluate('document.querySelectorAll("#opening-list button").length'), 1);
    assert.match(await evaluate('document.querySelector("#list-summary").textContent'), /4 total submissions/);
    assert.match(await evaluate('document.querySelector("#opening-list button").textContent'), /75.0%/);
    await action(`document.querySelector("#hide-singletons").click();
        const search = document.querySelector("#search"); search.value = "Lon H";
        search.dispatchEvent(new Event("input"));`);
    assert.equal(await evaluate('document.querySelectorAll("#opening-list button").length'), 1);
    assert.match(await evaluate('document.querySelector("#opening-list button").textContent'), /25.0%/);
    await action(`document.querySelector("#search").value = "";
        const nation = document.querySelector("#nation"); nation.value = "FRANCE";
        nation.dispatchEvent(new Event("change"));`);
    assert.match(await evaluate('document.querySelector("#selection-title").textContent'), /FRANCE — Submitted orders/);
    assert.equal(await evaluate('document.querySelectorAll(".unit-token").length'), fixture.board.units.length);
    assert.equal(await evaluate('document.querySelectorAll(\'[aria-label="FRANCE FLEET in SpaSC"]\').length'), 1);
    console.log("StrategyBrowser real Chromium DOM fixture passed (profiles, frozen inspector, mobile keyboard, ownership/reset, historical/foreign overlays, import isolation, cancellation, openings country/search/navigation/singletons).");
}

main().catch(error => { console.error(error); process.exitCode = 1; }).finally(async () => {
    if (socket?.readyState === WebSocket.OPEN) {
        await Promise.race([
            command("Browser.close", {}, null).catch(() => {}),
            new Promise(resolve => setTimeout(resolve, 1000))
        ]);
    }
    socket?.close();
    if (browser && browser.exitCode === null) {
        browser.kill("SIGTERM");
        await new Promise(resolve => {
            const timer = setTimeout(() => { browser.kill("SIGKILL"); resolve(); }, 2000);
            browser.once("exit", () => { clearTimeout(timer); resolve(); });
        });
    }
    if (server) {
        server.closeAllConnections();
        await new Promise(resolve => server.close(resolve));
    }
    if (scratch)
        await fs.promises.rm(scratch, { recursive: true, force: true, maxRetries: 30, retryDelay: 100 });
});
