"use strict";

const SVG_NS = "http://www.w3.org/2000/svg";
const $ = selector => document.querySelector(selector);

const SUPPLY_CENTER_INDICATORS = {
    Bel: "g5246",
    Hol: "g5246-3",
    Kie: "g5246-6",
    Ber: "g5246-9",
    Mun: "g5246-0",
    Vie: "g5246-8",
    Bud: "g5246-02",
    Tri: "g5246-92",
    Ser: "g5246-36",
    Bul: "g5246-93",
    Rum: "g5246-7",
    Con: "g5246-03",
    Ank: "g5246-63",
    Smy: "g5246-5",
    Sev: "g5246-56",
    Mos: "g5246-74",
    Stp: "g5246-84",
    War: "g5246-2",
    Ven: "g5246-926",
    Rom: "g5246-04",
    Nap: "g5246-72",
    Tun: "g5246-1",
    Mar: "g5246-94",
    Spa: "g5246-77",
    Por: "g5246-776",
    Par: "g5246-639",
    Bre: "g5246-939",
    Lon: "g5246-50",
    Lvp: "g5246-561",
    Edi: "g5246-4",
    Den: "g5246-044",
    Swe: "g5246-17",
    Nwy: "g5246-178",
    Gre: "g5246-85"
};

const COAST_PARENTS = {
    StpNC: "Stp",
    StpSC: "Stp",
    SpaNC: "Spa",
    SpaSC: "Spa",
    BulEC: "Bul",
    BulSC: "Bul"
};

// Positions in standard.svg's root coordinate system.
const COAST_ANCHORS = {
    StpNC: { x: 113, y: 10 },
    StpSC: { x: 108, y: 34 },
    SpaNC: { x: 25, y: 78 },
    SpaSC: { x: 36, y: 88 },
    BulEC: { x: 109, y: 84 },
    BulSC: { x: 103, y: 88 }
};

// Sample the original SVG before any ownership coloring is applied.
const MAP_COLOR_SAMPLES = {
    AUSTRIA: "vie",
    ENGLAND: "lon",
    FRANCE: "par",
    GERMANY: "ber",
    ITALY: "rom",
    RUSSIA: "mos",
    TURKEY: "ank"
};

const RETREAT_SCALE = 0.6;

let svgRoot;
let unitLayer;
let orderLayer;
let retreatLayer;
let adjustmentLayer;

let mapColors = Object.freeze({});
let neutralMapColor;

let game = null;
let phaseIndex = -1;
let selectedId = null;
let selectedProvince = null;

let offset = 0;
let pageSize = 50;
let listVersion = 0;
let gameVersion = 0;
let listRequest = null;
let gameRequest = null;
let searchTimer = null;


// Startup \\

async function start() {

    const response = await fetch("/ui/maps/standard.svg");

    if (!response.ok)
        throw new Error("Unable to load the map.");

    const parsed = new DOMParser().parseFromString(
        await response.text(), "image/svg+xml");

    if (parsed.querySelector("parsererror")
            || parsed.documentElement.localName !== "svg")
        throw new Error("The map asset is not valid SVG.");

    // Only the application's local SVG asset is inserted as markup.
    svgRoot = document.importNode(parsed.documentElement, true);
    $("#map-host").replaceChildren(svgRoot);
    svgRoot.querySelector("#units")?.replaceChildren();

    captureMapPalette();

    orderLayer = svgElement("g", {
        id: "game-orders"
    });

    unitLayer = svgElement("g", {
        id: "game-units"
    });

    retreatLayer = svgElement("g", {
        id: "game-retreats",
        "pointer-events": "none"
    });

    adjustmentLayer = svgElement("g", {
        id: "game-adjustments",
        "pointer-events": "none"
    });

    // Upper overlays do not intercept unit hover or click events.
    svgRoot.append(
        orderLayer,
        unitLayer,
        retreatLayer,
        adjustmentLayer);

    installProvinceInteractions();
    resetGame();

    $("#search").disabled = false;
    $("#refresh").disabled = false;

    $("#search").addEventListener("input", () => {

        clearTimeout(searchTimer);

        // Invalidate immediately, not only when the debounce expires.
        listVersion++;
        listRequest?.abort();

        $("#page-previous").disabled = true;
        $("#page-next").disabled = true;

        searchTimer = setTimeout(() => loadGames(0), 250);

    });

    $("#refresh").addEventListener("click", () => {
        clearTimeout(searchTimer);
        loadGames(0);
    });

    $("#page-previous").addEventListener("click",
        () => loadGames(Math.max(0, offset - pageSize)));

    $("#page-next").addEventListener("click",
        () => loadGames(offset + pageSize));

    $("#phase").addEventListener("change",
        () => showPhase(Number($("#phase").value)));

    $("#phase-previous").addEventListener("click",
        () => showPhase(phaseIndex - 1));

    $("#phase-next").addEventListener("click",
        () => showPhase(phaseIndex + 1));

    $("#nation").addEventListener("change", renderPhase);

    document.addEventListener("keydown", event => {

        if (event.target instanceof Element
                && event.target.closest(
                    "input, select, textarea, button, [contenteditable]"))
            return;

        if (event.key !== "ArrowLeft" && event.key !== "ArrowRight")
            return;

        if (!game)
            return;

        event.preventDefault();

        showPhase(
            phaseIndex + (event.key === "ArrowLeft" ? -1 : 1));

    });

    await loadGames(0);

}

function captureMapPalette() {

    const colors = {};

    for (const [nation, provinceId] of Object.entries(MAP_COLOR_SAMPLES)) {

        const path = svgRoot.querySelector(
            `#provinces > path#${CSS.escape(provinceId)}`);

        if (!path)
            throw new Error(`Missing map-color sample: ${provinceId}`);

        colors[nation] = getComputedStyle(path).fill;

    }

    const neutral = svgRoot.querySelector("#provinces > path#bel");

    if (!neutral)
        throw new Error("Missing neutral map-color sample.");

    neutralMapColor = getComputedStyle(neutral).fill;
    mapColors = Object.freeze(colors);

}

async function json(url, signal) {

    const response = await fetch(url, { signal });
    const body = await response.json();

    if (!response.ok)
        throw new Error(body.error || `Request failed (${response.status})`);

    return body;

}


// Database listing \\

async function loadGames(requestedOffset) {

    const version = ++listVersion;

    listRequest?.abort();
    listRequest = new AbortController();

    $("#summary").textContent = "Searching database…";
    $("#page-previous").disabled = true;
    $("#page-next").disabled = true;

    try {

        const parameters = new URLSearchParams({
            q: $("#search").value.trim(),
            offset: String(requestedOffset)
        });

        const result = await json(
            `/api/games?${parameters}`, listRequest.signal);

        if (version !== listVersion)
            return;

        if (result.games.length === 0 && requestedOffset > 0) {
            await loadGames(0);
            return;
        }

        offset = result.offset;
        pageSize = result.limit;

        $("#summary").textContent =
            `${result.total} matching database game(s). Read-only browser.`;

        $("#page-position").textContent = result.total === 0
            ? "0 / 0"
            : `${offset + 1}–${offset + result.games.length} / ${result.total}`;

        $("#page-previous").disabled = offset === 0;
        $("#page-next").disabled =
            offset + result.games.length >= result.total;

        $("#game-list").replaceChildren();

        for (const entry of result.games) {

            const button = document.createElement("button");

            button.type = "button";
            button.className = "opening";
            button.dataset.id = entry.id;

            button.setAttribute(
                "aria-pressed",
                String(entry.id === selectedId));

            const title = document.createElement("strong");

            title.textContent =
                entry.title || `Game ${entry.externalKey}`;

            const description = document.createElement("span");

            description.className = "game-description";
            description.textContent =
                `${entry.competition || "No competition"} · `
                + `${entry.source}/${entry.externalKey} · `
                + `${entry.phaseCount} stored phase(s)`;

            button.append(title, description);
            button.addEventListener("click", () => selectGame(entry));

            $("#game-list").append(button);

        }

        if (result.games.length === 0) {

            const empty = document.createElement("p");
            empty.textContent = "No matching games.";

            $("#game-list").append(empty);

        }

    } catch (error) {

        if (version !== listVersion || error.name === "AbortError")
            return;

        $("#game-list").replaceChildren();
        $("#page-position").textContent = "";

        $("#summary").textContent =
            "Unable to list games: " + error.message;

    }

}


// Game loading \\

async function selectGame(entry) {

    const version = ++gameVersion;

    gameRequest?.abort();
    gameRequest = new AbortController();

    selectedId = entry.id;
    resetGame();

    $("#game-title").textContent =
        entry.title || `Game ${entry.externalKey}`;

    $("#game-status").textContent = "Loading stored game…";

    markSelectedGame();

    try {

        const result = await json(
            "/api/game?" + new URLSearchParams({ id: entry.id }),
            gameRequest.signal);

        if (version !== gameVersion)
            return;

        if (!Array.isArray(result.phases) || !result.colors)
            throw new Error("Invalid game response.");

        game = result;

        $("#game-title").textContent =
            game.title || `Game ${game.externalKey}`;

        $("#game-details").textContent =
            `${game.competition || "No competition"} · DBN ${game.externalKey}`;

        $("#nation").replaceChildren(new Option("All countries", ""));

        for (const nation of Object.keys(game.colors))
            $("#nation").append(new Option(nation, nation));

        $("#nation").disabled = false;

        game.phases.forEach((phase, index) => {

            $("#phase").append(new Option(
                `${index + 1}. ${phase.board.year} `
                    + `${phase.board.phase.replaceAll("_", " ")} `
                    + `[${phase.status}]`,
                String(index)));

        });

        $("#phase").disabled = game.phases.length === 0;

        $("#game-status").textContent =
            "Snapshot order is preserved exactly as stored in the source.";

        if (game.phases.length > 0)
            showPhase(0);
        else
            $("#game-status").textContent =
                "This game contains no snapshots.";

    } catch (error) {

        if (version !== gameVersion || error.name === "AbortError")
            return;

        resetGame();

        $("#game-status").textContent =
            "Unable to open game: " + error.message;

    }

}

function markSelectedGame() {

    for (const button of $("#game-list").querySelectorAll("button"))
        button.setAttribute(
            "aria-pressed",
            String(button.dataset.id === selectedId));

}

function resetGame() {

    game = null;
    phaseIndex = -1;
    selectedProvince = null;

    $("#phase").replaceChildren();
    $("#phase").disabled = true;

    $("#nation").replaceChildren(new Option("All countries", ""));
    $("#nation").disabled = true;

    $("#phase-previous").disabled = true;
    $("#phase-next").disabled = true;

    $("#game-details").textContent = "";
    $("#phase-details").textContent = "";
    $("#province-details").textContent =
        "Select a province to inspect it.";

    $("#map-warning").textContent = "";

    $("#orders").replaceChildren();
    $("#annotations").replaceChildren();

    // Removing tokens also removes their old tooltip contents.
    unitLayer.replaceChildren();
    orderLayer.replaceChildren();
    retreatLayer.replaceChildren();
    adjustmentLayer.replaceChildren();

    for (const path of svgRoot.querySelectorAll(".province.selected"))
        path.classList.remove("selected");

    paintCenters({}, {});

}

function showPhase(index) {

    if (!game || index < 0 || index >= game.phases.length)
        return;

    phaseIndex = index;
    selectedProvince = null;

    $("#phase").value = String(index);
    $("#phase-previous").disabled = index === 0;
    $("#phase-next").disabled = index === game.phases.length - 1;

    renderPhase();

}

function currentPhase() {
    return game && phaseIndex >= 0 ? game.phases[phaseIndex] : null;
}


// Phase rendering \\

function renderPhase() {

    const phase = currentPhase();

    if (!phase)
        return;

    const nation = $("#nation").value;
    const visible = item => !nation || item.nation === nation;

    const orders = phase.orders.filter(visible);
    const annotations = phase.annotations.filter(visible);

    const retreatCount = orders.filter(
        order => order.type === "RETREAT").length;

    $("#phase-details").textContent =
        `Snapshot ${phaseIndex + 1}/${game.phases.length} · `
        + `source code ${phase.sourcePhase} · ${phase.status} · `
        + `${phase.board.units.length} units · `
        + `${orders.length}/${phase.orders.length} displayed orders · `
        + `${retreatCount} displayed retreat(s)`;

    paintCenters(phase.board.supplyCenterOwners, game.colors);

    const warnings = [];

    /*
     * Tooltips use every source order, including orders for dimmed units.
     * The country filter controls visible overlays and list entries only.
     */
    renderUnits(phase.board.units, nation, phase.orders, warnings);
    renderOrders(orders, warnings);

    $("#map-warning").textContent = warnings.length
        ? "Some map elements could not be drawn: " + warnings.join("; ")
        : "";

    fillList(
        $("#orders"),
        orders,
        order => order.text,
        "No parsed orders for this selection.");

    fillList(
        $("#annotations"),
        annotations,
        annotation =>
            `${annotation.retreatOrder ? "RETREAT" : "MAIN ORDERS"} · `
            + `${annotation.nation} ${annotation.origin}: `
            + (annotation.successful === null ? "UNKNOWN"
                : annotation.successful ? "SUCCESS" : "FAILURE")
            + (annotation.reason ? ` — ${annotation.reason}` : ""),
        "No source result annotations for this selection.");

    for (const path of svgRoot.querySelectorAll(".province.selected"))
        path.classList.remove("selected");

    if (selectedProvince)
        inspectProvince(selectedProvince);
    else
        $("#province-details").textContent =
            "Select a province to inspect it.";

}

function fillList(list, entries, text, emptyMessage) {

    list.replaceChildren();

    if (entries.length === 0) {

        const item = document.createElement("li");
        item.textContent = emptyMessage;
        list.append(item);

        return;

    }

    for (const entry of entries) {

        const item = document.createElement("li");
        item.textContent = text(entry);
        list.append(item);

    }

}


// Supply-center ownership \\

function paintCenters(owners, colors) {

    const canonicalOwners = new Map();

    for (const [province, owner] of Object.entries(owners))
        canonicalOwners.set(territoryKey(province), owner);

    for (const [province, indicatorId]
            of Object.entries(SUPPLY_CENTER_INDICATORS)) {

        const key = territoryKey(province);
        const owner = canonicalOwners.get(key);

        const territoryColor = owner && Object.hasOwn(mapColors, owner)
            ? mapColors[owner]
            : neutralMapColor;

        const path = svgRoot.querySelector(
            `#provinces > path#${CSS.escape(key)}`);

        if (path) {
            path.style.fill = territoryColor;
            path.style.fillOpacity = "1";
        }

        const circles = svgRoot.querySelectorAll(
            `#${CSS.escape(indicatorId)} circle`);

        if (circles.length < 2)
            continue;

        // Repaint every center, including neutral or previously captured ones.
        circles[0].style.fill = territoryColor;
        circles[0].style.stroke = "#111827";

        circles[1].style.fill =
            owner && Object.hasOwn(colors, owner) ? colors[owner] : "#ffffff";

        circles[1].style.stroke = "none";

    }

}


// Unit order tooltips \\

function ordersForUnit(unit, orders) {

    const location = territoryKey(unit.province);

    return orders.filter(order => {

        // A proposed new unit is not the existing unit at that location.
        if (order.type === "BUILD" || order.type === "WAIVE")
            return false;

        if (!order.origin || order.nation !== unit.nation)
            return false;

        if (order.unitType && order.unitType !== unit.type)
            return false;

        return territoryKey(order.origin) === location;

    });

}

function unitTooltip(unit, orders) {

    const matching = ordersForUnit(unit, orders);

    if (matching.length === 0)
        return `${unit.nation} ${unit.type} ${unit.province}\n`
            + "Order unknown / not supplied in this snapshot";

    /*
     * The server formats order.text using OrderForm.format(...).
     * Preserve its standard notation rather than rebuilding it in JavaScript.
     *
     * A combined snapshot may contain both movement and retreat submissions.
     */
    return matching.map(order => order.text).join("\n");

}


// Board units \\

function renderUnits(units, selectedNation, orders, warnings) {

    unitLayer.replaceChildren();

    for (const unit of units) {

        try {

            const position = anchor(unit.province);
            const tooltip = unitTooltip(unit, orders);

            const group = svgElement("g", {
                class: "unit-token",
                transform: `translate(${position.x} ${position.y})`,
                opacity:
                    !selectedNation || unit.nation === selectedNation ? 1 : 0.25,
                tabindex: 0,
                role: "button",
                "aria-label": tooltip.replaceAll("\n", "; ")
            });

            // Native SVG hover tooltip. Text is never inserted as HTML.
            const title = svgElement("title");
            title.textContent = tooltip;

            const circle = svgElement("circle", {
                r: 1.7,
                fill: game.colors[unit.nation],
                stroke: "#ffffff",
                "stroke-width": 0.35
            });

            const label = svgElement("text", {
                "text-anchor": "middle",
                "dominant-baseline": "central",
                "font-size": 1.9,
                "font-weight": "bold",
                fill: "#ffffff",
                "pointer-events": "none"
            });

            label.textContent = unit.type === "ARMY" ? "A" : "F";

            group.append(title, circle, label);

            group.addEventListener(
                "click",
                () => inspectProvince(unit.province));

            group.addEventListener("keydown", event => {

                if (event.key === "Enter" || event.key === " ") {

                    event.preventDefault();
                    event.stopPropagation();

                    inspectProvince(unit.province);

                }

            });

            unitLayer.append(group);

        } catch (error) {

            warnings.push(`${unit.province}: ${error.message}`);

        }

    }

}


// Submitted orders \\

function renderOrders(orders, warnings) {

    orderLayer.replaceChildren();
    retreatLayer.replaceChildren();
    adjustmentLayer.replaceChildren();

    for (const order of orders) {

        if (order.type === "WAIVE")
            continue;

        try {

            const origin = anchor(order.origin);
            const color = game.colors[order.nation] || "#111827";

            if (order.type === "HOLD") {

                orderLayer.append(svgElement("circle", {
                    cx: origin.x,
                    cy: origin.y,
                    r: 2.5,
                    fill: "none",
                    stroke: color,
                    "stroke-width": 0.7
                }));

            } else if (order.type === "MOVE") {

                drawLine(origin, anchor(order.target), color, "", true);

            } else if (order.type === "RETREAT") {

                drawRetreat(origin, order, color);

            } else if (order.type === "SUPPORT" || order.type === "CONVOY") {

                const target = anchor(order.target);
                const convoy = order.type === "CONVOY";
                const dash = convoy ? "0.4 1.2" : "2 1.2";

                drawLine(origin, target, color, dash, false);

                if (order.auxiliaryTarget)
                    drawLine(
                        target,
                        anchor(order.auxiliaryTarget),
                        color,
                        dash,
                        true);

                drawLabel(origin, convoy ? "C" : "S", color);

            } else if (order.type === "BUILD") {

                drawBuild(origin, order, color);

            } else if (order.type === "DESTROY") {

                drawDisband(origin, order);

            }

        } catch (error) {

            warnings.push(`${order.text}: ${error.message}`);

        }

    }

}


// Retreat overlay \\

function drawRetreat(origin, order, color) {

    const destination = anchor(order.target);

    const group = svgElement("g", {
        role: "img",
        "aria-label": `Submitted retreat: ${order.text}`
    });

    const title = svgElement("title");
    title.textContent = `Submitted retreat: ${order.text}`;
    group.append(title);

    // A smaller, thinner departure ring around the existing unit.
    group.append(svgElement("circle", {
        cx: origin.x,
        cy: origin.y,
        r: 2.1,
        fill: "none",
        stroke: "#ffffff",
        "stroke-width": 0.7
    }));

    group.append(svgElement("circle", {
        cx: origin.x,
        cy: origin.y,
        r: 2.1,
        fill: "none",
        stroke: color,
        "stroke-width": 0.4,
        "stroke-dasharray": "0.9 0.6"
    }));

    /*
     * Keep the line connected to its actual destination.
     * Scale its thickness and arrowhead, not its geographical length.
     */
    drawLine(
        origin,
        destination,
        color,
        "0.9 0.6",
        true,
        group,
        RETREAT_SCALE);

    const label = svgElement("text", {
        x: origin.x + 2,
        y: origin.y - 2,
        fill: color,
        stroke: "#ffffff",
        "stroke-width": 0.3,
        "paint-order": "stroke",
        "font-size": 1.8,
        "font-weight": "bold"
    });

    label.textContent = "R";

    group.append(label);
    retreatLayer.append(group);

}


// Submitted adjustment markers \\

function drawBuild(point, order, color) {

    if (order.unitType !== "ARMY" && order.unitType !== "FLEET")
        throw new Error("Build order has no recognized unit type.");

    const group = svgElement("g", {
        transform: `translate(${point.x} ${point.y})`,
        role: "img",
        "aria-label":
            `Proposed ${order.nation} ${order.unitType} build at ${order.origin}`
    });

    const title = svgElement("title");

    title.textContent =
        `Submitted build: ${order.nation} ${order.unitType} at ${order.origin}`;

    group.append(title);

    group.append(svgElement("circle", {
        r: 1.7,
        fill: color,
        "fill-opacity": 0.2,
        stroke: "none"
    }));

    group.append(svgElement("circle", {
        r: 2.5,
        fill: "none",
        stroke: "#ffffff",
        "stroke-width": 1.1
    }));

    group.append(svgElement("circle", {
        r: 2.5,
        fill: "none",
        stroke: color,
        "stroke-width": 0.65,
        "stroke-dasharray": "0.05 1.15",
        "stroke-linecap": "round"
    }));

    const label = svgElement("text", {
        x: 0,
        y: 0,
        "text-anchor": "middle",
        "dominant-baseline": "central",
        "font-size": 1.9,
        "font-weight": "bold",
        fill: color,
        stroke: "#ffffff",
        "stroke-width": 0.25,
        "paint-order": "stroke"
    });

    label.textContent = order.unitType === "ARMY" ? "A" : "F";

    group.append(label);
    adjustmentLayer.append(group);

}

function drawDisband(point, order) {

    const group = svgElement("g", {
        transform: `translate(${point.x} ${point.y})`,
        role: "img",
        "aria-label":
            `Proposed removal of ${order.nation} unit at ${order.origin}`
    });

    const title = svgElement("title");
    title.textContent = `Submitted disband: ${order.text}`;

    group.append(title);

    const diagonals = [
        { x1: -2.1, y1: -2.1, x2: 2.1, y2: 2.1 },
        { x1: -2.1, y1: 2.1, x2: 2.1, y2: -2.1 }
    ];

    for (const diagonal of diagonals)
        group.append(svgElement("line", {
            ...diagonal,
            stroke: "#ffffff",
            "stroke-width": 1.35,
            "stroke-linecap": "round"
        }));

    for (const diagonal of diagonals)
        group.append(svgElement("line", {
            ...diagonal,
            stroke: "#dc2626",
            "stroke-width": 0.8,
            "stroke-linecap": "round"
        }));

    adjustmentLayer.append(group);

}


// Order drawing helpers \\

function drawLabel(point, text, color) {

    const label = svgElement("text", {
        x: point.x + 2,
        y: point.y - 2,
        fill: color,
        stroke: "#ffffff",
        "stroke-width": 0.45,
        "paint-order": "stroke",
        "font-size": 3,
        "font-weight": "bold"
    });

    label.textContent = text;
    orderLayer.append(label);

}

function drawLine(
    from,
    to,
    color,
    dash,
    arrow,
    layer = orderLayer,
    scale = 1
) {

    const dx = to.x - from.x;
    const dy = to.y - from.y;
    const distance = Math.hypot(dx, dy);

    if (distance < 0.01)
        return;

    const ux = dx / distance;
    const uy = dy / distance;
    const trim = Math.min(2, distance / 4);

    const start = {
        x: from.x + ux * trim,
        y: from.y + uy * trim
    };

    const end = {
        x: to.x - ux * trim,
        y: to.y - uy * trim
    };

    const coordinates = {
        x1: start.x,
        y1: start.y,
        x2: end.x,
        y2: end.y,
        "stroke-linecap": "round"
    };

    layer.append(svgElement("line", {
        ...coordinates,
        stroke: "#ffffff",
        "stroke-width": 1.25 * scale
    }));

    layer.append(svgElement("line", {
        ...coordinates,
        stroke: color,
        "stroke-width": 0.7 * scale,
        "stroke-dasharray": dash
    }));

    if (arrow) {

        const length = Math.min(2 * scale, distance / 3);
        const width = length / 2;
        const x = end.x - ux * length;
        const y = end.y - uy * length;

        layer.append(svgElement("polygon", {
            points: `${end.x},${end.y} `
                + `${x - uy * width},${y + ux * width} `
                + `${x + uy * width},${y - ux * width}`,
            fill: color,
            stroke: "#ffffff",
            "stroke-width": 0.2 * scale
        }));

    }

}


// Map coordinates \\

function svgElement(name, attributes = {}) {

    const element = document.createElementNS(SVG_NS, name);

    for (const [key, value] of Object.entries(attributes))
        element.setAttribute(key, String(value));

    return element;

}

function canonical(province) {
    return COAST_PARENTS[province] || province;
}

function territoryKey(province) {
    return canonical(province).toLowerCase();
}

function rootPoint(element, x, y) {

    const local = element.getScreenCTM();
    const root = svgRoot.getScreenCTM();

    if (!local || !root)
        throw new Error("Map coordinates are unavailable.");

    const point = svgRoot.createSVGPoint();

    point.x = x;
    point.y = y;

    return point.matrixTransform(root.inverse().multiply(local));

}

function anchor(province) {

    if (!province)
        throw new Error("No origin or destination supplied.");

    if (COAST_ANCHORS[province])
        return COAST_ANCHORS[province];

    const indicator = SUPPLY_CENTER_INDICATORS[province];

    if (indicator) {

        const circle = svgRoot.querySelector(
            `#${CSS.escape(indicator)} circle`);

        if (circle)
            return rootPoint(
                circle,
                circle.cx.baseVal.value,
                circle.cy.baseVal.value);

    }

    const path = svgRoot.querySelector(
        `#provinces > path#${CSS.escape(territoryKey(province))}`);

    if (!path)
        throw new Error(`No map position for ${province}.`);

    const bounds = path.getBBox();

    return rootPoint(
        path,
        bounds.x + bounds.width / 2,
        bounds.y + bounds.height / 2);

}


// Province inspection \\

function installProvinceInteractions() {

    for (const path of svgRoot.querySelectorAll("#provinces > path[id]")) {

        if (path.id === "path4603")
            continue;

        path.classList.add("province");
        path.setAttribute("tabindex", "0");
        path.setAttribute("role", "button");
        path.setAttribute("aria-label", path.id.toUpperCase());

        path.addEventListener(
            "click",
            () => inspectProvince(path.id));

        path.addEventListener("keydown", event => {

            if (event.key === "Enter" || event.key === " ") {
                event.preventDefault();
                inspectProvince(path.id);
            }

        });

    }

}

function inspectProvince(province) {

    const phase = currentPhase();

    if (!phase)
        return;

    selectedProvince = province;

    const key = territoryKey(province);

    for (const path of svgRoot.querySelectorAll(".province"))
        path.classList.toggle("selected", path.id === key);

    const owner = Object.entries(phase.board.supplyCenterOwners)
        .find(([location]) => territoryKey(location) === key)?.[1];

    const units = phase.board.units.filter(
        unit => territoryKey(unit.province) === key);

    const orders = phase.orders.filter(order =>
        [order.origin, order.target, order.auxiliaryTarget]
            .filter(Boolean)
            .some(location => territoryKey(location) === key));

    $("#province-details").textContent = [
        key.toUpperCase(),
        owner ? `Owner: ${owner}` : "No recorded supply-center owner",
        ...units.map(unit =>
            `${unit.nation} ${unit.type} ${unit.province}`),
        ...orders.map(order => order.text),
        "Inspector includes all countries."
    ].join(" · ");

}


// Start browser \\

start().catch(error => {

    console.error(error);

    $("#summary").textContent =
        "Unable to start game browser: " + error.message;

});