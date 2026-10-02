"use strict";

const SVG_NS = "http://www.w3.org/2000/svg";

const CENTERS = {
    Bel: "g5246", Hol: "g5246-3", Kie: "g5246-6", Ber: "g5246-9",
    Mun: "g5246-0", Vie: "g5246-8", Bud: "g5246-02", Tri: "g5246-92",
    Ser: "g5246-36", Bul: "g5246-93", Rum: "g5246-7", Con: "g5246-03",
    Ank: "g5246-63", Smy: "g5246-5", Sev: "g5246-56", Mos: "g5246-74",
    Stp: "g5246-84", War: "g5246-2", Ven: "g5246-926", Rom: "g5246-04",
    Nap: "g5246-72", Tun: "g5246-1", Mar: "g5246-94", Spa: "g5246-77",
    Por: "g5246-776", Par: "g5246-639", Bre: "g5246-939",
    Lon: "g5246-50", Lvp: "g5246-561", Edi: "g5246-4",
    Den: "g5246-044", Swe: "g5246-17", Nwy: "g5246-178", Gre: "g5246-85"
};

const PARENTS = {
    StpNC: "Stp", StpSC: "Stp",
    SpaNC: "Spa", SpaSC: "Spa",
    BulEC: "Bul", BulSC: "Bul"
};

const COASTS = {
    StpNC: { x: 113, y: 10 }, StpSC: { x: 108, y: 34 },
    SpaNC: { x: 25, y: 78 }, SpaSC: { x: 36, y: 88 },
    BulEC: { x: 109, y: 84 }, BulSC: { x: 103, y: 88 }
};

const canonical = province => PARENTS[province] ?? province;

function svgElement(name, attributes = {}) {
    const element = document.createElementNS(SVG_NS, name);
    for (const [key, value] of Object.entries(attributes))
        element.setAttribute(key, String(value));
    return element;
}

export class BoardMap {
    static async create(host, details, warning) {
        const response = await fetch("/ui/maps/standard.svg");
        if (!response.ok)
            throw new Error(`Map request failed: ${response.status}`);

        const parsed = new DOMParser().parseFromString(
            await response.text(), "image/svg+xml");

        if (parsed.querySelector("parsererror") ||
            parsed.documentElement.localName !== "svg")
            throw new Error("Invalid map SVG.");

        const root = document.importNode(parsed.documentElement, true);
        host.replaceChildren(root);
        return new BoardMap(root, details, warning);
    }

    constructor(root, details, warning) {
        this.root = root;
        this.details = details;
        this.warning = warning;
        this.board = null;
        this.plan = null;

        root.querySelector("#units")?.replaceChildren();

        this.orders = svgElement("g", {
            id: "opening-orders", "pointer-events": "none"
        });
        this.units = svgElement("g", { id: "opening-units" });
        root.append(this.orders, this.units);

        for (const path of root.querySelectorAll("#provinces > path[id]")) {
            if (path.id === "path4603")
                continue;

            path.classList.add("province");
            path.setAttribute("tabindex", "0");
            path.setAttribute("role", "button");
            path.setAttribute("aria-label", path.id.toUpperCase());

            path.addEventListener("click", () => this.inspect(path.id));
            path.addEventListener("keydown", event => {
                if (event.key === "Enter" || event.key === " ") {
                    event.preventDefault();
                    this.inspect(path.id);
                }
            });
        }
    }

    clear() {
        this.board = null;
        this.plan = null;
        this.units.replaceChildren();
        this.orders.replaceChildren();
        this.warning.textContent = "";
        this.details.textContent = "Generate or load a position to inspect it.";
        this.clearSelection();
        this.paintCenters({});
    }

    clearSelection() {
        for (const path of this.root.querySelectorAll(".province.selected"))
            path.classList.remove("selected");
    }

    show(board, colors, nation, plan = null) {
        this.board = board;
        this.plan = plan;
        this.units.replaceChildren();
        this.orders.replaceChildren();
        this.clearSelection();
        this.details.textContent = "Select a province to inspect it.";
        this.warning.textContent = "";
        this.paintCenters(board.supplyCenterOwners, colors);

        const errors = [];

        for (const unit of board.units) {
            try {
                const point = this.anchor(unit.province);
                const token = svgElement("g", {
                    class: "unit-token",
                    transform: `translate(${point.x} ${point.y})`,
                    opacity: unit.nation === nation ? 1 : 0.3
                });
                const title = svgElement("title");
                title.textContent =
                    `${unit.nation} ${unit.type} in ${unit.province}`;

                const circle = svgElement("circle", {
                    r: 1.7, fill: colors[unit.nation],
                    stroke: "#ffffff", "stroke-width": 0.35
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

                token.append(title, circle, label);
                token.addEventListener("click", () => this.inspect(unit.province));
                this.units.append(token);
            } catch (error) {
                errors.push(`${unit.province}: ${error.message}`);
            }
        }

        for (const order of plan?.orders ?? []) {
            try {
                this.drawOrder(order, colors[plan.nation]);
            } catch (error) {
                errors.push(`${order.text}: ${error.message}`);
            }
        }

        if (errors.length)
            this.warning.textContent =
                "Some map elements could not be drawn: " + errors.join(" ");
    }

    paintCenters(owners, colors = {}) {
        for (const [province, id] of Object.entries(CENTERS)) {
            const circles = this.root.querySelector(
                `#${CSS.escape(id)}`)?.querySelectorAll("circle");

            if (!circles || circles.length < 2)
                continue;

            circles[0].style.fill = colors[owners[province]] ?? "#ffffff";
            circles[0].style.stroke = "#111827";
            circles[1].style.fill = "#ffffff";
            circles[1].style.stroke = "none";
        }
    }

    inspect(province) {
        if (!this.board)
            return;

        const key = canonical(province).toLowerCase();

        for (const path of this.root.querySelectorAll(".province"))
            path.classList.toggle("selected", path.id === key);

        const owner = Object.entries(this.board.supplyCenterOwners)
            .find(([location]) => location.toLowerCase() === key)?.[1];

        const units = this.board.units.filter(unit =>
            canonical(unit.province).toLowerCase() === key);

        const orders = (this.plan?.orders ?? []).filter(order =>
            [order.origin, order.target, order.auxiliaryTarget]
                .filter(Boolean)
                .some(location => canonical(location).toLowerCase() === key));

        this.details.textContent = [
            key.toUpperCase(),
            owner ? `Supply-center owner: ${owner}` : "No recorded supply-center owner",
            ...units.map(unit => `${unit.nation} ${unit.type} (${unit.province})`),
            ...orders.map(order => order.text)
        ].join(" · ");
    }

    rootPoint(element, x, y) {
        const local = element.getScreenCTM();
        const root = this.root.getScreenCTM();

        if (!local || !root)
            throw new Error("Map coordinates unavailable.");

        const point = this.root.createSVGPoint();
        point.x = x;
        point.y = y;
        return point.matrixTransform(root.inverse().multiply(local));
    }

    anchor(province) {
        if (COASTS[province])
            return COASTS[province];

        const id = CENTERS[province];
        const circle = id
            ? this.root.querySelector(`#${CSS.escape(id)} circle`)
            : null;

        if (circle)
            return this.rootPoint(
                circle, circle.cx.baseVal.value, circle.cy.baseVal.value);

        const path = this.root.querySelector(
            `#provinces > path#${CSS.escape(canonical(province).toLowerCase())}`);

        if (!path)
            throw new Error(`No map anchor for ${province}.`);

        const box = path.getBBox();
        return this.rootPoint(path, box.x + box.width / 2, box.y + box.height / 2);
    }

    drawOrder(order, color) {
        const group = svgElement("g");
        const origin = this.anchor(order.origin);

        if (order.type === "HOLD") {
            group.append(svgElement("circle", {
                cx: origin.x, cy: origin.y, r: 2.5,
                fill: "none", stroke: color, "stroke-width": 0.7
            }));
        } else if (order.type === "MOVE") {
            this.line(group, origin, this.anchor(order.target), color, "", true);
        } else if (order.type === "SUPPORT" || order.type === "CONVOY") {
            const target = this.anchor(order.target);
            const convoy = order.type === "CONVOY";
            const dash = convoy ? "0.4 1.2" : "2 1.2";

            this.line(group, origin, target, color, dash, false);
            if (order.auxiliaryTarget)
                this.line(group, target, this.anchor(order.auxiliaryTarget),
                    color, dash, true);

            const label = svgElement("text", {
                x: origin.x + 2, y: origin.y - 2,
                fill: color, stroke: "#ffffff", "stroke-width": 0.45,
                "paint-order": "stroke", "font-size": 2.5, "font-weight": "bold"
            });
            label.textContent = convoy ? "C" : "S";
            group.append(label);
        }

        this.orders.append(group);
    }

    line(group, from, to, color, dash, arrow) {
        const dx = to.x - from.x;
        const dy = to.y - from.y;
        const distance = Math.hypot(dx, dy);

        if (distance < 0.01) {
            group.append(svgElement("circle", {
                cx: from.x, cy: from.y, r: 2.5,
                fill: "none", stroke: color,
                "stroke-width": 0.7, "stroke-dasharray": dash
            }));
            return;
        }

        const ux = dx / distance;
        const uy = dy / distance;
        const trim = Math.min(2, distance / 4);
        const start = { x: from.x + ux * trim, y: from.y + uy * trim };
        const end = { x: to.x - ux * trim, y: to.y - uy * trim };
        const attributes = {
            x1: start.x, y1: start.y, x2: end.x, y2: end.y,
            "stroke-linecap": "round"
        };

        group.append(svgElement("line", {
            ...attributes, stroke: "#ffffff", "stroke-width": 1.25
        }));
        group.append(svgElement("line", {
            ...attributes, stroke: color,
            "stroke-width": 0.7, "stroke-dasharray": dash
        }));

        if (arrow) {
            const length = Math.min(2, distance / 3);
            const width = length / 2;
            const bx = end.x - ux * length;
            const by = end.y - uy * length;

            group.append(svgElement("polygon", {
                points: `${end.x},${end.y} `
                    + `${bx - uy * width},${by + ux * width} `
                    + `${bx + uy * width},${by - ux * width}`,
                fill: color, stroke: "#ffffff", "stroke-width": 0.2
            }));
        }
    }
}