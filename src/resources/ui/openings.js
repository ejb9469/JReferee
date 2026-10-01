"use strict";

const SVG_NS = "http://www.w3.org/2000/svg";

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

let catalog;
let svgRoot;
let unitLayer;
let orderLayer;
let filtered = [];
let selectedIndex = -1;

const nationSelect = document.querySelector("#nation");
const searchInput = document.querySelector("#search");
const openingList = document.querySelector("#opening-list");


async function startBrowser() {

    const response = await fetch("/api/openings").then(requireOk);
    catalog = await response.json();

    if (!catalog || !Array.isArray(catalog.openings)
            || !catalog.board || !catalog.colors) {
        throw new Error("The server returned an invalid opening catalog.");
    }

    const svgText = await fetch("/ui/maps/standard.svg")
        .then(requireOk)
        .then(result => result.text());

    const documentSvg = new DOMParser().parseFromString(svgText, "image/svg+xml");

    if (documentSvg.querySelector("parsererror")
            || documentSvg.documentElement.localName !== "svg") {
        throw new Error("Unable to read standard.svg.");
    }

    // This SVG is the local application asset, not catalog-supplied markup.
    svgRoot = document.importNode(documentSvg.documentElement, true);
    document.querySelector("#map-host").replaceChildren(svgRoot);

    svgRoot.querySelector("#units")?.replaceChildren();

    orderLayer = svgElement("g", { id: "opening-orders" });
    unitLayer = svgElement("g", { id: "opening-units" });
    svgRoot.append(orderLayer, unitLayer);

    installProvinceInteractions();
    renderSupplyCenters();

    for (const nation of Object.keys(catalog.colors)) {
        const option = document.createElement("option");
        option.value = nation;
        option.textContent = nation;
        nationSelect.append(option);
    }

    if (catalog.openings.length > 0)
        nationSelect.value = catalog.openings[0].nation;

    nationSelect.disabled = false;
    searchInput.disabled = false;

    nationSelect.addEventListener("change", filterOpenings);
    searchInput.addEventListener("input", filterOpenings);

    document.querySelector("#previous").addEventListener("click", () => navigate(-1));
    document.querySelector("#next").addEventListener("click", () => navigate(1));

    document.addEventListener("keydown", event => {
        if (event.target instanceof Element
                && event.target.closest("input, select, textarea, button, [contenteditable]")) {
            return;
        }

        if (event.key === "ArrowLeft" || event.key === "ArrowRight") {
            event.preventDefault();
            navigate(event.key === "ArrowLeft" ? -1 : 1);
        }
    });

    document.querySelector("#summary").textContent =
        `${catalog.scanned} games scanned · ${catalog.represented} represented · `
        + `${catalog.skipped} skipped · ${catalog.nationalOpenings} complete national openings`;

    document.querySelector("#diagnostic-summary").textContent =
        `Import diagnostics (${catalog.diagnostics.length})`;

    const diagnostics = document.querySelector("#diagnostic-list");

    for (const message of catalog.diagnostics) {
        const item = document.createElement("li");
        item.textContent = message;
        diagnostics.append(item);
    }

    filterOpenings();

}

function requireOk(response) {
    if (!response.ok)
        throw new Error(`Request failed: ${response.status} ${response.statusText}`);
    return response;
}


// Opening selection \\

function filterOpenings() {

    const nation = nationSelect.value;
    const query = searchInput.value.trim().toLowerCase();

    const national = catalog.openings.filter(opening => opening.nation === nation);
    const total = national.reduce((sum, opening) => sum + opening.count, 0);

    filtered = national.filter(opening =>
        opening.orders.some(order => order.text.toLowerCase().includes(query)));

    filtered.sort((first, second) =>
        second.count - first.count
        || (first.signature < second.signature ? -1
            : first.signature > second.signature ? 1 : 0));

    const title = document.querySelector("#list-title");
    title.textContent = nation;
    applyNationColor(title, nation);
    title.classList.add("country-name");

    document.querySelector("#list-summary").textContent =
        `${filtered.length} of ${national.length} openings · ${total} complete submissions`;

    openingList.replaceChildren();

    for (const [index, opening] of filtered.entries()) {

        const button = document.createElement("button");
        button.type = "button";
        button.className = "opening";
        button.setAttribute("aria-pressed", "false");

        const count = document.createElement("span");
        count.className = "opening-count";
        count.textContent = `${opening.count} game(s) · `
            + `${(100 * opening.count / total).toFixed(1)}%`;

        button.append(count);

        for (const order of opening.orders) {
            const line = document.createElement("span");
            line.className = "order";
            line.textContent = order.text;
            applyNationColor(line, nation);
            button.append(line);
        }

        button.addEventListener("click", () => selectOpening(index));
        openingList.append(button);

    }

    if (filtered.length === 0) {
        const empty = document.createElement("p");
        empty.textContent = "No matching openings.";
        openingList.append(empty);
    }

    selectOpening(filtered.length === 0 ? -1 : 0);

}

function selectOpening(index) {

    selectedIndex = index;

    for (const [position, button] of openingList.querySelectorAll("button").entries())
        button.setAttribute("aria-pressed", String(position === index));

    document.querySelector("#previous").disabled = index <= 0;
    document.querySelector("#next").disabled =
        index < 0 || index >= filtered.length - 1;

    document.querySelector("#position").textContent =
        `${index < 0 ? 0 : index + 1} / ${filtered.length}`;

    document.querySelector("#selection-title").textContent =
        index < 0 ? "No opening selected"
            : `${filtered[index].nation} — Submitted orders — Spring 1901`;

    document.querySelector("#province-details").textContent =
        "Select a province to inspect it.";

    for (const path of svgRoot.querySelectorAll(".province.selected"))
        path.classList.remove("selected");

    renderUnits(nationSelect.value);
    renderOrders(index < 0 ? null : filtered[index]);

}

function navigate(offset) {

    const next = selectedIndex + offset;

    if (next < 0 || next >= filtered.length)
        return;

    selectOpening(next);

    openingList.querySelectorAll("button")[next].scrollIntoView({
        block: "nearest",
        behavior: "smooth"
    });

}

function applyNationColor(element, nation) {
    element.style.setProperty("--nation-color", catalog.colors[nation]);
    element.classList.toggle("germany", nation === "GERMANY");
}


// SVG coordinates \\

function svgElement(name, attributes = {}) {

    const element = document.createElementNS(SVG_NS, name);

    for (const [key, value] of Object.entries(attributes))
        element.setAttribute(key, String(value));

    return element;

}

function canonical(province) {
    return COAST_PARENTS[province] ?? province;
}

function provincePath(province) {
    return svgRoot.querySelector(
        `#provinces > path#${CSS.escape(canonical(province).toLowerCase())}`);
}

function rootPoint(element, x, y) {

    const point = svgRoot.createSVGPoint();
    point.x = x;
    point.y = y;

    const localMatrix = element.getScreenCTM();
    const rootMatrix = svgRoot.getScreenCTM();

    if (!localMatrix || !rootMatrix)
        throw new Error("Unable to determine map coordinates.");

    return point.matrixTransform(rootMatrix.inverse().multiply(localMatrix));

}

function anchor(province) {

    if (COAST_ANCHORS[province])
        return COAST_ANCHORS[province];

    const indicatorId = SUPPLY_CENTER_INDICATORS[province];

    if (indicatorId) {
        const circle = svgRoot.querySelector(
            `#${CSS.escape(indicatorId)} circle`);

        if (circle)
            return rootPoint(circle, circle.cx.baseVal.value, circle.cy.baseVal.value);
    }

    const path = provincePath(province);

    if (!path)
        throw new Error(`No map position for ${province}.`);

    const box = path.getBBox();
    return rootPoint(path, box.x + box.width / 2, box.y + box.height / 2);

}


// Starting board \\

function renderSupplyCenters() {

    for (const [province, indicatorId] of Object.entries(SUPPLY_CENTER_INDICATORS)) {

        const indicator = svgRoot.querySelector(`#${CSS.escape(indicatorId)}`);
        const circles = indicator?.querySelectorAll("circle");

        if (!circles || circles.length < 2)
            continue;

        const owner = catalog.board.supplyCenterOwners[province];

        circles[0].style.fill = owner ? catalog.colors[owner] : "#ffffff";
        circles[0].style.stroke = "#111827";
        circles[1].style.fill = "#ffffff";
        circles[1].style.stroke = "none";

    }

}

function renderUnits(selectedNation) {

    unitLayer.replaceChildren();

    for (const unit of catalog.board.units) {

        const position = anchor(unit.province);
        const group = svgElement("g", {
            class: "unit-token",
            transform: `translate(${position.x} ${position.y})`,
            opacity: unit.nation === selectedNation ? 1 : 0.3
        });

        const title = svgElement("title");
        title.textContent = `${unit.nation} ${unit.type} in ${unit.province}`;

        const circle = svgElement("circle", {
            r: 1.7,
            fill: catalog.colors[unit.nation],
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
        group.addEventListener("click", () => inspectProvince(unit.province));
        unitLayer.append(group);

    }

}

function installProvinceInteractions() {

    for (const path of svgRoot.querySelectorAll("#provinces > path[id]")) {

        if (path.id === "path4603")
            continue;

        path.classList.add("province");
        path.setAttribute("tabindex", "0");
        path.setAttribute("role", "button");
        path.setAttribute("aria-label", path.id.toUpperCase());

        const title = svgElement("title");
        title.textContent = path.id.toUpperCase();
        path.append(title);

        path.addEventListener("click", () => inspectProvince(path.id));
        path.addEventListener("keydown", event => {
            if (event.key === "Enter" || event.key === " ") {
                event.preventDefault();
                inspectProvince(path.id);
            }
        });

    }

}

function inspectProvince(province) {

    const key = canonical(province).toLowerCase();

    for (const path of svgRoot.querySelectorAll(".province"))
        path.classList.toggle("selected", path.id === key);

    const units = catalog.board.units.filter(unit =>
        canonical(unit.province).toLowerCase() === key);

    const owner = Object.entries(catalog.board.supplyCenterOwners)
        .find(([location]) => location.toLowerCase() === key)?.[1];

    const orders = selectedIndex < 0 ? [] : filtered[selectedIndex].orders.filter(order =>
        [order.origin, order.target, order.auxiliaryTarget]
            .filter(Boolean)
            .some(location => canonical(location).toLowerCase() === key));

    const details = [
        key.toUpperCase(),
        owner ? `Supply-center owner: ${owner}` : "No starting supply-center owner",
        ...units.map(unit => `${unit.nation} ${unit.type} (${unit.province})`),
        ...orders.map(order => order.text)
    ];

    document.querySelector("#province-details").textContent = details.join(" · ");

}


// Submitted-order overlay \\

function renderOrders(opening) {

    orderLayer.replaceChildren();
    document.querySelector("#map-warning").textContent = "";

    if (!opening)
        return;

    const color = catalog.colors[opening.nation];
    const errors = [];

    for (const order of opening.orders) {

        const group = svgElement("g");

        try {

            const origin = anchor(order.origin);

            if (order.type === "HOLD") {

                group.append(svgElement("circle", {
                    cx: origin.x,
                    cy: origin.y,
                    r: 2.5,
                    fill: "none",
                    stroke: color,
                    "stroke-width": 0.7
                }));

            } else if (order.type === "MOVE") {

                drawLine(group, origin, anchor(order.target), color, "", true);

            } else if (order.type === "SUPPORT" || order.type === "CONVOY") {

                const target = anchor(order.target);
                const convoy = order.type === "CONVOY";
                const dash = convoy ? "0.4 1.2" : "2 1.2";

                drawLine(group, origin, target, color, dash, false);

                if (order.auxiliaryTarget)
                    drawLine(group, target, anchor(order.auxiliaryTarget), color, dash, true);

                const label = svgElement("text", {
                    x: origin.x + 2,
                    y: origin.y - 2,
                    fill: color,
                    stroke: "#ffffff",
                    "stroke-width": 0.45,
                    "paint-order": "stroke",
                    "font-size": 2.5,
                    "font-weight": "bold"
                });

                label.textContent = convoy ? "C" : "S";
                group.append(label);

            }

            orderLayer.append(group);

        } catch (error) {
            errors.push(`${order.text}: ${error.message}`);
        }

    }

    if (errors.length > 0)
        document.querySelector("#map-warning").textContent =
            "Some orders could not be drawn: " + errors.join(" ");

}

function drawLine(group, from, to, color, dash, arrow) {

    const dx = to.x - from.x;
    const dy = to.y - from.y;
    const distance = Math.hypot(dx, dy);

    if (distance < 0.01) {
        group.append(svgElement("circle", {
            cx: from.x,
            cy: from.y,
            r: 2.5,
            fill: "none",
            stroke: color,
            "stroke-width": 0.7,
            "stroke-dasharray": dash
        }));
        return;
    }

    const ux = dx / distance;
    const uy = dy / distance;
    const trim = Math.min(2, distance / 4);

    const start = { x: from.x + ux * trim, y: from.y + uy * trim };
    const end = { x: to.x - ux * trim, y: to.y - uy * trim };

    const attributes = {
        x1: start.x,
        y1: start.y,
        x2: end.x,
        y2: end.y,
        "stroke-linecap": "round"
    };

    group.append(svgElement("line", {
        ...attributes,
        stroke: "#ffffff",
        "stroke-width": 1.25
    }));

    group.append(svgElement("line", {
        ...attributes,
        stroke: color,
        "stroke-width": 0.7,
        "stroke-dasharray": dash
    }));

    if (arrow) {

        const length = Math.min(2, distance / 3);
        const width = length / 2;
        const baseX = end.x - ux * length;
        const baseY = end.y - uy * length;

        group.append(svgElement("polygon", {
            points: `${end.x},${end.y} `
                + `${baseX - uy * width},${baseY + ux * width} `
                + `${baseX + uy * width},${baseY - ux * width}`,
            fill: color,
            stroke: "#ffffff",
            "stroke-width": 0.2
        }));

    }

}


startBrowser().catch(error => {
    console.error(error);
    document.querySelector("#summary").textContent =
        "Unable to load openings: " + error.message;
});