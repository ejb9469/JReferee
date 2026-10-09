"use strict";

// Dependency-free DOM self-checks: node --test src/testing/selfcheck/GamesDetectivesSelfCheck.js
const assert = require("node:assert/strict");
const { readFileSync } = require("node:fs");
const { resolve } = require("node:path");
const { test } = require("node:test");
const vm = require("node:vm");
const { once } = require("node:events");

const ui = resolve(__dirname, "../../resources/ui");
const html = readFileSync(resolve(ui, "games.html"), "utf8");
const panelSource = readFileSync(resolve(ui, "games-detectives.js"), "utf8");
const browserSource = readFileSync(resolve(ui, "games.js"), "utf8");

class Element {
    constructor(tag) {
        this.tagName = tag;
        this.children = [];
        this.listeners = {};
        this.attributes = {};
        this.dataset = {};
        this.style = {};
        this.value = "";
        this.text = "";
        this.disabled = false;
        this.checked = false;
        this.classes = new Set();
        this.classList = {
            add: name => this.classes.add(name),
            remove: name => this.classes.delete(name),
            toggle: (name, enabled) => enabled
                ? this.classes.add(name) : this.classes.delete(name)
        };
    }

    set textContent(text) {
        this.text = String(text);
        this.children = [];
    }

    get textContent() {
        return this.text + this.children.map(child => child.textContent).join("");
    }

    append(...children) {
        for (const child of children) {
            child.parent = this;
            this.children.push(child);
        }
        if (this.tagName === "select" && this.children.length === children.length)
            this.value = this.children[0]?.value || "";
    }

    replaceChildren(...children) {
        this.children = [];
        this.text = "";
        if (this.tagName === "select")
            this.value = "";
        this.append(...children);
    }

    addEventListener(type, listener) {
        (this.listeners[type] ||= []).push(listener);
    }

    dispatch(type, event = {}) {
        event.target ||= this;
        for (const listener of this.listeners[type] || [])
            listener(event);
        this.parent?.dispatch(type, event);
    }

    setAttribute(name, value) {
        this.attributes[name] = value;
    }

    querySelectorAll(selector) {
        return this.children.flatMap(child => [
            ...(child.tagName === selector ? [child] : []),
            ...child.querySelectorAll(selector)
        ]);
    }

    closest(selector) {
        const tags = selector.split(",").map(tag => tag.trim());
        return tags.includes(this.tagName) ? this : this.parent?.closest(selector);
    }

    scrollIntoView() {
        this.scrolled = true;
    }
}

function harness() {
    const nodes = new Map([...html.matchAll(/<(\w+)[^>]*\bid="([^"]+)"/g)]
        .map(([, tag, id]) => [id, new Element(tag)]));
    const get = id => nodes.get(id);
    get("detectives-kinds").append(get("detectives-kind-options"),
        get("detectives-select-all"), get("detectives-clear-selection"));
    const document = new Element("document");
    document.querySelector = selector => get(selector.slice(1));
    document.createElement = tag => new Element(tag);
    document.createElementNS = (_, tag) => new Element(tag);
    document.importNode = node => node;
    const context = vm.createContext({
        document, Element, console, AbortController, URLSearchParams,
        setTimeout, clearTimeout,
        CSS: { escape: value => value },
        getComputedStyle: () => ({ fill: "#abcdef" }),
        Option: function (text, value) {
            const option = new Element("option");
            option.textContent = text;
            option.value = value;
            return option;
        }
    });
    vm.runInContext(panelSource, context);
    const panel = vm.runInContext("DetectivePanel", context);
    const checkboxes = () => get("detectives-kind-options").querySelectorAll("input");
    const displayed = () => get("detectives-findings").children
        .filter(child => child.tagName === "article");
    const toggle = (kind, checked) => {
        const checkbox = checkboxes().find(input => input.value === kind);
        assert.ok(checkbox, `Missing checkbox for ${kind}`);
        checkbox.checked = checked;
        checkbox.dispatch("change");
    };
    const completeness = value => {
        get("detectives-completeness").value = value;
        get("detectives-completeness").dispatch("change");
    };
    return { context, document, get, panel, checkboxes, displayed, toggle, completeness };
}

function finding(kind, complete, nations = ["FRANCE"], focus = "Bur") {
    return {
        kind, complete, focus, interpretation: "STRUCTURAL_PATTERN",
        participants: nations.map((nation, index) => ({
            nation, role: "ATTACKER", unit: `${nation} A ${index}`,
            order: `${nation} A ${index} - ${focus}`, provenance: "SUBMITTED"
        })),
        missingOrders: complete ? [] : ["GERMANY A Mun"],
        semantics: "A known local pattern.",
        detectiveVersion: "1", definitionVersion: "1"
    };
}

function report() {
    return {
        status: "EVALUATED", knownOrders: 3, activeUnits: 4,
        unknownUnits: ["GERMANY A Mun"], contextId: "snapshot-1",
        coverageScope: "WHOLE_SNAPSHOT",
        coverage: [
            ["ATTACK", true, "EVALUATED_WITH_FINDINGS", 2],
            ["SUPPORT", true, "EVALUATED_WITH_FINDINGS", 1],
            ["ZERO_FINDINGS", true, "EVALUATED_WITH_NO_FINDINGS", 0],
            ["SKIPPED_KIND", true, "SKIPPED_MISSING_EVIDENCE", 0],
            ["UNSUPPORTED", false, "UNSUPPORTED_UNIMPLEMENTED", 0]
        ].map(([kind, implemented, status, findingCount]) => ({
            kind, implemented, status, findingCount,
            missingEvidence: status === "SKIPPED_MISSING_EVIDENCE" ? ["ORDERS"] : []
        })),
        findings: [
            finding("ATTACK", true, ["FRANCE", "GERMANY"]),
            finding("ATTACK", false, ["ENGLAND"], "Lon"),
            finding("SUPPORT", true, ["FRANCE"], "Par")
        ]
    };
}

function freeze(value) {
    for (const child of Object.values(value)) {
        if (child && typeof child === "object")
            freeze(child);
    }
    return Object.freeze(value);
}

test("native disabled fieldset has a legend, help, and keyboard-operable buttons", () => {
    assert.match(html, /<fieldset id="detectives-kinds" disabled\s+aria-describedby="detectives-kind-help">/);
    assert.match(html, /<legend>Registered kinds<\/legend>/);
    assert.match(html, /id="detectives-kind-help"/);
    assert.match(html, /id="detectives-select-all" type="button">Select all<\/button>/);
    assert.match(html, /id="detectives-clear-selection" type="button">Clear selection<\/button>/);
    assert.doesNotMatch(html, /<select id="detectives-kind"/);
});

test("default selects every registered kind, including zero and skipped coverage", () => {
    const h = harness();
    h.panel.show(report(), "", () => {});
    assert.deepEqual(h.checkboxes().map(input => input.value),
        ["ATTACK", "SUPPORT", "ZERO_FINDINGS", "SKIPPED_KIND"]);
    assert.ok(h.checkboxes().every(input => input.checked && input.type === "checkbox"));
    assert.equal(h.get("detectives-kinds").disabled, false);
    assert.equal(h.displayed().length, 3);
    for (const input of h.checkboxes()) {
        assert.equal(input.parent.tagName, "label");
        assert.equal(input.parent.children[1].textContent, input.value.replaceAll("_", " "));
        assert.equal(input.attributes.tabindex, undefined);
    }
});

test("multiple selected kinds are ORed; none is an explicit empty selection", () => {
    const h = harness();
    h.panel.show(report(), "", () => {});
    h.get("detectives-clear-selection").dispatch("click");
    assert.equal(h.displayed().length, 0);
    assert.match(h.get("detectives-findings").textContent, /No kinds selected/);
    assert.match(h.get("detectives-status").textContent, /^0 of 3/);
    assert.ok(h.checkboxes().every(input => !input.checked));
    h.toggle("ATTACK", true);
    assert.equal(h.displayed().length, 2);
    h.toggle("SUPPORT", true);
    assert.equal(h.displayed().length, 3);
    h.toggle("ATTACK", false);
    assert.equal(h.displayed().length, 1);
    h.get("detectives-select-all").dispatch("click");
    assert.equal(h.displayed().length, 3);
    assert.ok(h.checkboxes().every(input => input.checked));
});

test("kind selection is ANDed with country and local completeness", () => {
    const h = harness();
    const r = report();
    h.panel.show(r, "", () => {});
    h.get("detectives-clear-selection").dispatch("click");
    h.toggle("ATTACK", true);
    h.toggle("SUPPORT", true);
    h.panel.show(r, "FRANCE", () => {});
    assert.equal(h.displayed().length, 2);
    h.completeness("incomplete");
    assert.equal(h.displayed().length, 0);
    assert.match(h.get("detectives-findings").textContent, /No findings match/);
    h.panel.show(r, "ENGLAND", () => {});
    assert.equal(h.displayed().length, 1);
    h.completeness("complete");
    assert.equal(h.displayed().length, 0);
    h.panel.show(r, "", () => {});
    assert.equal(h.displayed().length, 2);
    h.completeness("");
    assert.equal(h.displayed().length, 3);
});

test("country filtering keeps all participants and evidence, coverage, and report intact", () => {
    const h = harness();
    const r = freeze(report());
    const original = JSON.stringify(r);
    h.panel.show(r, "", () => {});
    const rows = [...h.get("detectives-coverage-body").children];
    const coverage = h.get("detectives-coverage-body").textContent;
    const summary = h.get("detectives-coverage-summary").textContent;
    const evidence = h.get("detectives-evidence").textContent;
    h.panel.show(r, "GERMANY", () => {});
    assert.equal(h.displayed().length, 1);
    assert.match(h.displayed()[0].textContent, /FRANCE A 0/);
    assert.match(h.displayed()[0].textContent, /GERMANY A 1/);
    h.completeness("incomplete");
    h.get("detectives-clear-selection").dispatch("click");
    h.get("detectives-select-all").dispatch("click");
    assert.deepEqual(h.get("detectives-coverage-body").children, rows);
    assert.equal(h.get("detectives-coverage-body").textContent, coverage);
    assert.equal(h.get("detectives-coverage-summary").textContent, summary);
    assert.equal(h.get("detectives-evidence").textContent, evidence);
    assert.match(coverage, /ZERO FINDINGSEvaluated — no findings0/);
    assert.match(coverage, /SKIPPED KINDSkipped — missing evidenceNot evaluatedORDERS/);
    assert.match(coverage, /UNSUPPORTEDNot implemented \/ not registeredNot evaluated/);
    assert.equal(JSON.stringify(r), original);
});

test("same-report country changes preserve checkboxes, empty selections, and completeness", () => {
    const h = harness();
    const r = report();
    h.panel.show(r, "", () => {});
    const originalCheckboxes = h.checkboxes();
    h.toggle("SUPPORT", false);
    h.completeness("complete");
    h.panel.show(r, "FRANCE", () => {});
    assert.deepEqual(h.checkboxes(), originalCheckboxes);
    assert.equal(h.checkboxes().find(input => input.value === "SUPPORT").checked, false);
    assert.equal(h.get("detectives-completeness").value, "complete");
    h.get("detectives-clear-selection").dispatch("click");
    h.panel.show(r, "", () => {});
    assert.ok(h.checkboxes().every(input => !input.checked));
    assert.equal(h.displayed().length, 0);
    assert.match(h.get("detectives-findings").textContent, /No kinds selected/);
});

test("a new report identity resets all filters even with identical context identifiers", () => {
    const h = harness();
    h.panel.show(report(), "", () => {});
    h.get("detectives-clear-selection").dispatch("click");
    h.completeness("incomplete");
    h.panel.show(report(), "", () => {});
    assert.ok(h.checkboxes().every(input => input.checked));
    assert.equal(h.get("detectives-completeness").value, "");
    assert.equal(h.displayed().length, 3);
});

test("registration is derived dynamically from coverage rather than a fixed kind list", () => {
    const h = harness();
    const r = report();
    r.coverage.push({
        kind: "NEW_REGISTERED_KIND", implemented: true,
        status: "EVALUATED_WITH_FINDINGS", findingCount: 1, missingEvidence: []
    });
    r.findings.push(finding("NEW_REGISTERED_KIND", true));
    h.panel.show(r, "", () => {});
    assert.equal(h.checkboxes().length, 5);
    h.get("detectives-clear-selection").dispatch("click");
    h.toggle("NEW_REGISTERED_KIND", true);
    assert.equal(h.displayed().length, 1);
    assert.match(h.displayed()[0].textContent, /NEW REGISTERED KIND/);
    h.panel.show(report(), "", () => {});
    assert.equal(h.checkboxes().length, 4);
    assert.ok(!h.checkboxes().some(input => input.value === "NEW_REGISTERED_KIND"));
});

test("zero-findings and skipped kinds are selectable with clear empty-state messages", () => {
    const h = harness();
    h.panel.show(report(), "", () => {});
    h.get("detectives-clear-selection").dispatch("click");
    h.toggle("ZERO_FINDINGS", true);
    assert.equal(h.displayed().length, 0);
    assert.match(h.get("detectives-findings").textContent, /No findings match/);
    h.toggle("SKIPPED_KIND", true);
    assert.equal(h.displayed().length, 0);
    h.get("detectives-select-all").dispatch("click");
    assert.equal(h.displayed().length, 3);
});

test("reports with no findings distinguish no patterns from no selected kinds", () => {
    const h = harness();
    const r = report();
    r.findings = [];
    h.panel.show(r, "", () => {});
    assert.equal(h.checkboxes().length, 4);
    assert.match(h.get("detectives-findings").textContent, /found no patterns/);
    h.get("detectives-clear-selection").dispatch("click");
    assert.match(h.get("detectives-findings").textContent, /No kinds selected/);
    h.get("detectives-select-all").dispatch("click");
    assert.match(h.get("detectives-findings").textContent, /found no patterns/);
});

test("evaluated reports with no registered kinds never display unregistered findings", () => {
    const h = harness();
    const r = report();
    r.coverage = r.coverage.filter(entry => !entry.implemented);
    h.panel.show(r, "", () => {});
    assert.equal(h.checkboxes().length, 0);
    assert.equal(h.displayed().length, 0);
    assert.match(h.get("detectives-findings").textContent, /No kinds selected/);
    h.get("detectives-select-all").dispatch("click");
    assert.equal(h.displayed().length, 0);
});

test("loading reset, absent reports, and unavailable snapshots clear stale UI", () => {
    const h = harness();
    h.panel.show(report(), "", () => {});
    h.completeness("incomplete");
    h.get("detectives-coverage").open = true;
    h.panel.reset("Loading snapshot investigations…");
    assert.equal(h.checkboxes().length, 0);
    assert.equal(h.displayed().length, 0);
    assert.equal(h.get("detectives-kinds").disabled, true);
    assert.equal(h.get("detectives-completeness").disabled, true);
    assert.equal(h.get("detectives-completeness").value, "");
    assert.equal(h.get("detectives-coverage").open, false);
    assert.equal(h.get("detectives-coverage-body").children.length, 0);
    assert.equal(h.get("detectives-evidence").textContent, "");
    assert.match(h.get("detectives-status").textContent, /Loading/);
    h.panel.show(null, "", () => {});
    assert.match(h.get("detectives-status").textContent, /No investigation report/);
    h.panel.show({ status: "UNAVAILABLE", message: "No board." }, "", () => {});
    assert.match(h.get("detectives-status").textContent, /UNAVAILABLE: No board/);
    assert.equal(h.get("detectives-kinds").disabled, true);
    h.panel.show(report(), "", () => {});
    assert.equal(h.displayed().length, 3);
    assert.ok(h.checkboxes().every(input => input.checked));
});

test("finding details, missing orders, safe text, and latest map callback survive filtering", () => {
    const h = harness();
    const r = report();
    r.findings[1].semantics = "<img src=x onerror=alert(1)>";
    let inspected = "";
    h.panel.show(r, "", () => { throw new Error("Old callback used"); });
    h.completeness("incomplete");
    h.panel.show(r, "ENGLAND", focus => { inspected = focus; });
    const card = h.displayed()[0];
    assert.match(card.textContent, /Missing prerequisites: GERMANY A Mun/);
    assert.match(card.textContent, /<img src=x onerror=alert\(1\)>/);
    assert.equal(card.querySelectorAll("img").length, 0);
    card.querySelectorAll("button")[0].dispatch("click");
    assert.equal(inspected, "Lon");
    assert.equal(h.get("map-host").scrolled, true);
});

function deferred() {
    let resolve;
    let reject;
    const promise = new Promise((success, failure) => {
        resolve = success;
        reject = failure;
    });
    return { promise, resolve, reject };
}

const response = body => ({ ok: true, json: async () => body });
const listing = games => ({ games, offset: 0, limit: 50, total: games.length });
const entry = id => ({ id, title: `Game ${id}`, externalKey: id, phaseCount: 1 });
const storedGame = id => ({
    ...entry(id), colors: { FRANCE: "#123456" },
    phases: [0, 1].map(index => ({
        board: { year: 1901, phase: "SPRING_MOVEMENT", units: [], supplyCenterOwners: {} },
        orders: [], annotations: [], sourcePhase: String(index), status: "STORED",
        investigation: report()
    }))
});

async function browserHarness() {
    const h = harness();
    const map = new Element("svg");
    map.localName = "svg";
    map.querySelector = () => new Element("path");
    h.context.DOMParser = class {
        parseFromString() {
            return { documentElement: map, querySelector: () => null };
        }
    };
    const requests = [];
    h.context.fetch = (url, options) => {
        if (url === "/ui/maps/standard.svg")
            return Promise.resolve({ ok: true, text: async () => "<svg/>" });
        if (url.startsWith("/api/games?") && requests.length === 0) {
            requests.push({ initial: true });
            return Promise.resolve(response(listing([])));
        }
        const pending = deferred();
        requests.push({ url, options, ...pending });
        return pending.promise;
    };
    vm.runInContext(browserSource, h.context);
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(h.get("search").disabled, false);
    h.run = expression => vm.runInContext(expression, h.context);
    h.requests = requests;
    h.select = id => {
        h.context.nextEntry = entry(id);
        return h.run("selectGame(nextEntry)");
    };
    return h;
}

test("latest game response wins even when an aborted older request resolves later", async () => {
    const h = await browserHarness();
    const first = h.select("old");
    const older = h.requests.at(-1);
    const second = h.select("new");
    const newer = h.requests.at(-1);
    assert.equal(older.options.signal.aborted, true);
    assert.equal(h.checkboxes().length, 0);
    newer.resolve(response(storedGame("new")));
    await second;
    h.toggle("SUPPORT", false);
    older.resolve(response(storedGame("old")));
    await first;
    assert.equal(h.get("game-title").textContent, "Game new");
    assert.equal(h.checkboxes().find(input => input.value === "SUPPORT").checked, false);
    assert.equal(h.run("selectedId"), "new");
});

test("a stale rejected game request cannot erase newer findings or show an error", async () => {
    const h = await browserHarness();
    const first = h.select("old");
    const older = h.requests.at(-1);
    const second = h.select("new");
    h.requests.at(-1).resolve(response(storedGame("new")));
    await second;
    h.get("detectives-clear-selection").dispatch("click");
    older.reject(new Error("stale failure"));
    await first;
    assert.equal(h.get("game-title").textContent, "Game new");
    assert.doesNotMatch(h.get("game-status").textContent, /Unable/);
    assert.match(h.get("detectives-findings").textContent, /No kinds selected/);
});

test("new game loading and current errors clear filters; empty games have no report", async () => {
    const h = await browserHarness();
    let pending = h.select("first");
    h.requests.at(-1).resolve(response(storedGame("first")));
    await pending;
    h.get("detectives-clear-selection").dispatch("click");
    h.completeness("incomplete");
    pending = h.select("failing");
    assert.equal(h.checkboxes().length, 0);
    assert.equal(h.get("detectives-kinds").disabled, true);
    h.requests.at(-1).reject(new Error("current failure"));
    await pending;
    assert.match(h.get("game-status").textContent, /current failure/);
    assert.match(h.get("detectives-status").textContent, /game loading failed/);
    pending = h.select("empty");
    h.requests.at(-1).resolve(response({ ...storedGame("empty"), phases: [] }));
    await pending;
    assert.match(h.get("detectives-status").textContent, /No snapshots/);
    assert.equal(h.checkboxes().length, 0);
});

test("snapshot switches reset selection; country rerenders preserve it", async () => {
    const h = await browserHarness();
    const pending = h.select("game");
    h.requests.at(-1).resolve(response(storedGame("game")));
    await pending;
    h.get("detectives-clear-selection").dispatch("click");
    h.completeness("complete");
    h.get("nation").value = "FRANCE";
    h.get("nation").dispatch("change");
    assert.match(h.get("detectives-findings").textContent, /No kinds selected/);
    assert.equal(h.get("detectives-completeness").value, "complete");
    h.run("showPhase(1)");
    assert.ok(h.checkboxes().every(input => input.checked));
    assert.equal(h.get("detectives-completeness").value, "");
    assert.equal(h.displayed().length, 2);
    h.run("showPhase(0)");
    assert.ok(h.checkboxes().every(input => input.checked));
});

test("global snapshot shortcuts do not intercept keyboard interaction on filter inputs/buttons", async () => {
    const h = await browserHarness();
    const pending = h.select("game");
    h.requests.at(-1).resolve(response(storedGame("game")));
    await pending;
    for (const target of [h.checkboxes()[0], h.get("detectives-select-all"),
        h.get("detectives-clear-selection"), h.get("detectives-completeness")]) {
        let prevented = false;
        h.document.dispatch("keydown", {
            target, key: "ArrowRight", preventDefault: () => { prevented = true; }
        });
        assert.equal(prevented, false);
        assert.equal(h.run("phaseIndex"), 0);
    }
    let prevented = false;
    h.document.dispatch("keydown", {
        target: h.get("map-host"), key: "ArrowRight",
        preventDefault: () => { prevented = true; }
    });
    assert.equal(prevented, true);
    assert.equal(h.run("phaseIndex"), 1);
});

test("late and stale failed list requests do not replace a newer listing or selected report", async () => {
    const h = await browserHarness();
    const gamePending = h.select("selected");
    h.requests.at(-1).resolve(response(storedGame("selected")));
    await gamePending;
    h.get("detectives-clear-selection").dispatch("click");
    const first = h.run("loadGames(0)");
    const older = h.requests.at(-1);
    const second = h.run("loadGames(0)");
    h.requests.at(-1).resolve(response(listing([entry("new-list")])));
    await second;
    older.resolve(response(listing([entry("old-list")])));
    await first;
    assert.match(h.get("game-list").textContent, /Game new-list/);
    assert.doesNotMatch(h.get("game-list").textContent, /old-list/);
    assert.match(h.get("detectives-findings").textContent, /No kinds selected/);
    const third = h.run("loadGames(0)");
    const staleFailure = h.requests.at(-1);
    const fourth = h.run("loadGames(0)");
    h.requests.at(-1).resolve(response(listing([entry("latest-list")])));
    await fourth;
    staleFailure.reject(new Error("stale list failure"));
    await third;
    assert.match(h.get("game-list").textContent, /Game latest-list/);
    assert.doesNotMatch(h.get("summary").textContent, /Unable/);
});

// Set GAMES_BROWSER_CDP to an already-running Chromium debugging endpoint.
// Serve src/resources on port 8765 for this optional native-control integration check.
test("native browser labels, Space/Tab/Enter, focus, and disabled controls",
    { skip: !process.env.GAMES_BROWSER_CDP }, async () => {
        const endpoint = process.env.GAMES_BROWSER_CDP;
        const target = await (await fetch(`${endpoint}/json/new?about:blank`,
            { method: "PUT" })).json();
        const socket = new WebSocket(target.webSocketDebuggerUrl);
        await once(socket, "open");
        let nextId = 0;
        const pending = new Map();
        socket.addEventListener("message", event => {
            const message = JSON.parse(event.data);
            const request = pending.get(message.id);
            if (!request)
                return;
            pending.delete(message.id);
            if (message.error)
                request.reject(new Error(message.error.message));
            else
                request.resolve(message.result);
        });
        const command = (method, params = {}) => new Promise((resolve, reject) => {
            const id = ++nextId;
            pending.set(id, { resolve, reject });
            socket.send(JSON.stringify({ id, method, params }));
        });
        const evaluate = async expression => {
            const result = await command("Runtime.evaluate", {
                expression, returnByValue: true, awaitPromise: true
            });
            assert.equal(result.exceptionDetails, undefined);
            return result.result.value;
        };
        const key = async (key, code, windowsVirtualKeyCode) => {
            await command("Input.dispatchKeyEvent", {
                type: "keyDown", key, code, windowsVirtualKeyCode,
                text: key === "Enter" ? "\r" : key === " " ? " " : undefined
            });
            await command("Input.dispatchKeyEvent", {
                type: "keyUp", key, code, windowsVirtualKeyCode
            });
        };
        try {
            await command("Page.navigate", {
                url: "http://127.0.0.1:8765/ui/games.html"
            });
            let ready = false;
            for (let attempt = 0; attempt < 100 && !ready; attempt++) {
                await new Promise(resolve => setTimeout(resolve, 20));
                ready = await evaluate(
                    "document.querySelector('#search')?.disabled === false");
            }
            assert.equal(ready, true, "Game browser did not start");
            assert.equal(await evaluate(
                "document.querySelector('#detectives-select-all').matches(':disabled')"), true);
            await evaluate(`globalThis.testReport = ${JSON.stringify(report())};
                DetectivePanel.show(testReport, "", () => {});`);
            assert.equal(await evaluate(
                "document.querySelectorAll('#detectives-findings article').length"), 3);
            assert.deepEqual(await evaluate(
                "[...document.querySelectorAll('#detectives-kind-options input')]"
                + ".map(input => input.labels[0].textContent)"),
            ["ATTACK", "SUPPORT", "ZERO FINDINGS", "SKIPPED KIND"]);
            await evaluate("document.querySelector('#detectives-kind-options label').click()");
            assert.equal(await evaluate(
                "document.querySelector('#detectives-kind-options input').checked"), false);
            await evaluate("document.querySelector('#detectives-kind-options input').focus()");
            await key(" ", "Space", 32);
            assert.equal(await evaluate(
                "document.querySelector('#detectives-kind-options input').checked"), true);
            assert.equal(await evaluate("phaseIndex"), -1);
            await key("Tab", "Tab", 9);
            assert.equal(await evaluate("document.activeElement.value"), "SUPPORT");
            await key(" ", "Space", 32);
            assert.equal(await evaluate("document.activeElement.checked"), false);
            await evaluate("document.querySelector('#detectives-clear-selection').focus()");
            await key("Enter", "Enter", 13);
            assert.equal(await evaluate(
                "document.querySelectorAll('#detectives-kind-options input:checked').length"), 0);
            assert.match(await evaluate(
                "document.querySelector('#detectives-findings').textContent"), /No kinds selected/);
            await evaluate("document.querySelector('#detectives-select-all').focus()");
            await key("Enter", "Enter", 13);
            assert.equal(await evaluate(
                "document.querySelectorAll('#detectives-kind-options input:checked').length"), 4);
            assert.equal(await evaluate("document.activeElement.id"), "detectives-select-all");
            await evaluate('DetectivePanel.reset("Loading…")');
            assert.equal(await evaluate(
                "document.querySelector('#detectives-clear-selection').matches(':disabled')"), true);
        } finally {
            socket.close();
            await fetch(`${endpoint}/json/close/${target.id}`);
        }
    });
