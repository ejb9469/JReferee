import { MovementMap } from "./movement-map.js";
import { tick, timeline, locate, series } from "./archive-replay-model.mjs";

const $ = selector => document.querySelector(selector);
const SVG_NS = "http://www.w3.org/2000/svg";
let catalog, replay, moments = [], panels = [], selected = 0, version = 0, request;

function node(name, text, attributes = {}, svg = false) {
    const element = svg ? document.createElementNS(SVG_NS, name) : document.createElement(name);
    if (text !== null) element.textContent = text;
    for (const [key, value] of Object.entries(attributes)) element.setAttribute(key, String(value));
    return element;
}

const momentName = frame => `${frame.year} ${frame.phase.replaceAll("_", " ")}`;
const percent = value => (100 * value).toFixed(2);

async function json(url, signal) {
    const response = await fetch(url, { signal });
    const body = await response.json();
    if (!response.ok) throw new Error(body.error || `Request failed (${response.status})`);
    return body;
}

function fail(error) {
    $("#status").className = "error";
    $("#status").textContent = error.message;
    $("#navigation").disabled = true;
}

async function start() {
    catalog = await json("/api/catalog");
    if (catalog.schema !== "jreferee-archive-replay-v1" || !catalog.entries.length)
        throw new Error("Unsupported or empty replay catalog.");
    document.title = `JReferee Replay - ${catalog.title}`;
    for (const power of Object.keys(catalog.colors))
        $("#power").append(node("option", power, { value: power }));
    for (const entry of catalog.entries) {
        const comparison = entry.candidateScore === undefined ? ""
            : ` | SoS ${percent(entry.referenceScore)}% / ${percent(entry.candidateScore)}%`;
        $("#entry").append(node("option",
            `${entry.id + 1}: ${catalog.overview.mode === "MATCH" ? entry.power + " | " : ""}seed ${entry.seed}${comparison}`,
            { value: entry.id }));
    }
    const overall = catalog.overview.overall;
    $("#overview").textContent = overall
        ? `${catalog.title}: ${catalog.overview.rotations} rotations; final SoS delta `
            + `${percent(overall.mean_delta)} percentage points; rotation SE `
            + `${overall.standard_error === null ? "unavailable" : percent(overall.standard_error) + " points"}. `
            + `Safety year ${catalog.overview.lastYear}; includes explicit cutoff scores.`
        : `${catalog.title}: ${catalog.overview.termination}; safety year ${catalog.overview.lastYear}.`;
    $("#entry").addEventListener("change", () => loadEntry().catch(fail));
    $("#moment").addEventListener("change", () => show(Number($("#moment").value)));
    $("#previous").addEventListener("click", () => show(selected - 1));
    $("#next").addEventListener("click", () => show(selected + 1));
    for (const id of ["power", "all-orders", "board-mode"])
        $(`#${id}`).addEventListener("change", () => show(selected));
    document.addEventListener("keydown", event => {
        if ($("#navigation").disabled || event.target instanceof Element
                && event.target.closest("input,select,button,summary"))
            return;
        if (!["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) return;
        event.preventDefault();
        show(event.key === "Home" ? 0 : event.key === "End" ? moments.length - 1
            : selected + (event.key === "ArrowLeft" ? -1 : 1));
    });
    await loadEntry();
}

async function loadEntry() {
    const current = ++version;
    request?.abort();
    request = new AbortController();
    $("#selection").disabled = true;
    $("#navigation").disabled = true;
    $("#status").className = "";
    $("#status").textContent = "Loading and replay-verifying the selected archive(s)...";
    $("#panels").replaceChildren();
    $("#center-chart").replaceChildren();
    $("#score-chart").replaceChildren();
    $("#metadata").textContent = "";
    replay = null;
    try {
        const loaded = await json(`/api/replay?id=${$("#entry").value}`, request.signal);
        if (current !== version) return;
        const aligned = timeline(loaded.games);
        const views = [];
        $("#panels").className = loaded.games.length === 1 ? "single" : "";
        for (const game of loaded.games) {
            const element = $("#panel").content.firstElementChild.cloneNode(true);
            $("#panels").append(element);
            const shadow = element.querySelector(".map").attachShadow({ mode: "open" });
            const map = await MovementMap.create(shadow, element.querySelector(".province-details"),
                element.querySelector(".warning"), catalog.colors, true);
            shadow.prepend(node("link", null, { rel: "stylesheet", href: "/ui/archive-replay.css" }));
            views.push({ element, map, game });
        }
        if (current !== version) return;
        replay = loaded;
        moments = aligned;
        panels = views;
        $("#moment").replaceChildren(...moments.map((moment, index) =>
            node("option", momentName(moment), { value: index })));
        if (catalog.overview.mode === "MATCH") $("#power").value = loaded.entry.power;
        $("#metadata").textContent = JSON.stringify({
            overview: catalog.overview,
            games: loaded.games.map(({ frames, ...metadata }) => metadata)
        }, null, 2);
        $("#navigation").disabled = false;
        $("#status").textContent = "Verified saved replay. Phase navigation performs no search or adjudication.";
        show(0);
    } catch (error) {
        if (error.name !== "AbortError") {
            $("#panels").replaceChildren();
            throw error;
        }
    } finally {
        if (current === version) $("#selection").disabled = false;
    }
}

function show(index) {
    if (!replay) return;
    try {
        selected = Math.max(0, Math.min(moments.length - 1, index));
        $("#moment").value = selected;
        $("#previous").disabled = selected === 0;
        $("#next").disabled = selected === moments.length - 1;
        for (const panel of panels) renderPanel(panel);
        chart("center-chart", "centers");
        chart("score-chart", "scores");
    } catch (error) {
        fail(error);
    }
}

function renderPanel({ element, map, game }) {
    const power = $("#power").value;
    const found = locate(game, moments[selected], $("#board-mode").value === "after");
    const final = game.frames.at(-1);
    element.querySelector("h2").textContent = `${game.label} | ${power}`;
    element.querySelector(".ending").textContent =
        `${game.termination}${game.winner ? " (" + game.winner + ")" : ""} at ${momentName(final)}`
        + ` | final ${final.centers[power]} centers, ${percent(final.scores[power])}% SoS`;
    const mapHost = element.querySelector(".map");
    const list = element.querySelector(".orders");
    const table = element.querySelector("tbody");
    list.replaceChildren();
    table.replaceChildren();
    element.querySelector(".empty-actions").textContent = "";
    element.querySelector(".warning").textContent = "";
    element.querySelector(".province-details").textContent = "";
    mapHost.hidden = found.status === "ABSENT";
    if (found.status === "ABSENT") {
        element.querySelector(".position").textContent =
            `No recorded phase at ${momentName(moments[selected])}; this phase was skipped or precedes the archive.`;
        return;
    }
    const { frame, boardFrame } = found;
    element.querySelector(".position").textContent = found.status === "ENDED"
        ? `Ended earlier. Showing final board from ${momentName(frame)}, not a new phase.`
        : found.status === "FINAL" ? `Final position: ${momentName(frame)}; no orders followed.`
        : found.status === "AFTER" ? `After ${momentName(frame)}: successor is ${momentName(boardFrame)}.`
        : `Before ${momentName(frame)}.`;
    const all = $("#all-orders").checked;
    const orders = frame.orders.filter(order => all || order.nation === power);
    map.show(boardFrame.board, all ? "" : power,
        found.status === "BEFORE" ? frame.orders : [], boardFrame.dislodged);
    for (const nation of Object.keys(catalog.colors)) {
        const row = node("tr", null, { class: nation === power ? "focused" : "" });
        row.append(node("td", nation), node("td", boardFrame.centers[nation]),
            node("td", percent(boardFrame.scores[nation])));
        table.append(row);
    }
    const empty = frame.emptyActions.filter(nation => all || nation === power);
    element.querySelector(".empty-actions").textContent = empty.length
        ? `No orders selected: ${empty.join(", ")}.` : "";
    for (const order of orders)
        list.append(node("li", `${order.nation}: ${order.text}${order.implicit ? " [omission]" : ""}`,
            { class: order.implicit ? "implicit" : "" }));
}

function chart(id, metric) {
    const power = $("#power").value;
    const paths = replay.games.map(game => series(game, power, metric));
    const first = tick(moments[0]), last = tick(moments.at(-1));
    const maximum = metric === "scores" ? 1 : Math.max(18, ...paths.flat().map(point => point.value));
    const x = value => 45 + 580 * (value - first) / Math.max(1, last - first);
    const y = value => 135 - 110 * value / maximum;
    const svg = node("svg", null, {
        viewBox: "0 0 660 175", role: "img", "aria-label": `${power}: recorded ${metric} history`
    }, true);
    svg.append(node("title", `${power}: recorded ${metric}, no predictions or post-ending extension`, {}, true));
    for (const value of [0, maximum / 2, maximum]) {
        svg.append(node("line", null, { x1: 45, x2: 625, y1: y(value), y2: y(value),
            stroke: "#cbd5e1" }, true));
        svg.append(node("text", metric === "scores" ? percent(value) : value.toFixed(0),
            { x: 38, y: y(value) + 4, "text-anchor": "end", class: "chart-label" }, true));
    }
    for (let index = 0; index < paths.length; index++) {
        const points = paths[index];
        let d = `M${x(points[0].time)} ${y(points[0].value)}`;
        for (const point of points.slice(1)) d += ` H${x(point.time)} V${y(point.value)}`;
        const color = index === 0 ? "#1d4ed8" : "#b45309";
        svg.append(node("path", null, { d, fill: "none", "stroke-width": 2.3,
            class: index === 0 ? "chart-reference" : "chart-candidate" }, true));
        for (const point of points) {
            const dot = node("circle", null, { cx: x(point.time), cy: y(point.value), r: 2.2, fill: color }, true);
            dot.append(node("title", `${replay.games[index].label}: ${momentName(point)} = `
                + (metric === "scores" ? percent(point.value) + "%" : point.value), {}, true));
            svg.append(dot);
        }
    }
    svg.append(node("line", null, { x1: x(tick(moments[selected])), x2: x(tick(moments[selected])),
        y1: 20, y2: 140, stroke: "#64748b", "stroke-dasharray": "2 3" }, true),
        node("text", moments[0].year, { x: 45, y: 158, class: "chart-label" }, true),
        node("text", moments.at(-1).year, { x: 625, y: 158, "text-anchor": "end", class: "chart-label" }, true));
    $(`#${id}`).replaceChildren(svg);
}

start().catch(fail);