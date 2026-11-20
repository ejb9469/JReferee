const SVG_NS = "http://www.w3.org/2000/svg";

const CENTERS = {
    Bel: "g5246", Hol: "g5246-3", Kie: "g5246-6", Ber: "g5246-9",
    Mun: "g5246-0", Vie: "g5246-8", Bud: "g5246-02", Tri: "g5246-92",
    Ser: "g5246-36", Bul: "g5246-93", Rum: "g5246-7", Con: "g5246-03",
    Ank: "g5246-63", Smy: "g5246-5", Sev: "g5246-56", Mos: "g5246-74",
    Stp: "g5246-84", War: "g5246-2", Ven: "g5246-926", Rom: "g5246-04",
    Nap: "g5246-72", Tun: "g5246-1", Mar: "g5246-94", Spa: "g5246-77",
    Por: "g5246-776", Par: "g5246-639", Bre: "g5246-939", Lon: "g5246-50",
    Lvp: "g5246-561", Edi: "g5246-4", Den: "g5246-044", Swe: "g5246-17",
    Nwy: "g5246-178", Gre: "g5246-85"
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

const MAP_COLOR_SAMPLES = {
    AUSTRIA: "Vie", ENGLAND: "Lon", FRANCE: "Par",
    GERMANY: "Ber", ITALY: "Rom", RUSSIA: "Mos", TURKEY: "Ank"
};

// Map decoration, not ownership of occupied non-center provinces.
const NON_CENTER_HOME_TERRITORIES = {
    AUSTRIA: ["Boh", "Gal", "Tyr"],
    ENGLAND: ["Cly", "Wal", "Yor"],
    FRANCE: ["Bur", "Gas", "Pic"],
    GERMANY: ["Pru", "Ruh", "Sil"],
    ITALY: ["Apu", "Pie", "Tus"],
    RUSSIA: ["Lvn", "Ukr"],
    TURKEY: ["Arm", "Syr"]
};

const canonical = province => PARENTS[province] ?? province;
const territoryKey = province => canonical(province).toLowerCase();

function svg(name, attributes = {}) {
    const element = document.createElementNS(SVG_NS, name);
    for (const [key, value] of Object.entries(attributes))
        element.setAttribute(key, String(value));
    return element;
}

export class MovementMap {

    static async create(host, details, warning, colors, paintTerritories = false) {
        const response = await fetch("/ui/maps/standard.svg");
        if (!response.ok)
            throw new Error("Unable to load standard.svg.");

        const parsed = new DOMParser().parseFromString(
            await response.text(), "image/svg+xml");

        if (parsed.querySelector("parsererror")
                || parsed.documentElement.localName !== "svg")
            throw new Error("Unable to read standard.svg.");

        const root = document.importNode(parsed.documentElement, true);
        host.replaceChildren(root);
        return new MovementMap(root, details, warning, colors, paintTerritories);
    }

    constructor(root, details, warning, colors, paintTerritories) {
        this.root = root;
        this.details = details;
        this.warning = warning;
        this.colors = colors;
        this.paintTerritories = paintTerritories;
        this.mapColors = Object.fromEntries(Object.entries(MAP_COLOR_SAMPLES)
            .map(([nation, province]) => [nation, getComputedStyle(this.path(province)).fill]));
        this.neutralMapColor = getComputedStyle(this.path("Bel")).fill;
        this.homeStyles = Object.entries(NON_CENTER_HOME_TERRITORIES)
            .flatMap(([nation, provinces]) => provinces.map(province => {
                const path = this.path(province);
                return {
                    nation, path,
                    styles: ["fill", "fill-opacity"].map(name => ({
                        name, value: path.style.getPropertyValue(name),
                        priority: path.style.getPropertyPriority(name)
                    }))
                };
            }));

        root.querySelector("#units")?.replaceChildren();

        this.orderLayer = svg("g", {
            id: "movement-orders",
            "pointer-events": "none"
        });
        this.unitLayer = svg("g", { id: "movement-units" });
        this.retreatLayer = svg("g", { id: "movement-retreats", "pointer-events": "none" });
        this.adjustmentLayer = svg("g", { id: "movement-adjustments", "pointer-events": "none" });
        root.append(this.orderLayer, this.unitLayer, this.retreatLayer, this.adjustmentLayer);

        for (const path of root.querySelectorAll("#provinces > path[id]")) {
            if (path.id === "path4603") continue;

            path.classList.add("province");
            path.setAttribute("tabindex", "0");
            path.setAttribute("role", "button");
            path.setAttribute("aria-label", path.id.toUpperCase());

            const title = svg("title");
            title.textContent = path.id.toUpperCase();
            path.append(title);

            path.addEventListener("click", () => this.inspect(path.id));
            path.addEventListener("keydown", event => {
                if (event.key === "Enter" || event.key === " ") {
                    event.preventDefault();
                    this.inspect(path.id);
                }
            });
        }
    }

    show(board, nation, orders = [], dislodged = []) {
        this.board = board;
        this.nation = nation;
        this.orders = orders.map(order => ({ ...order, nation: order.nation ?? nation }));
        this.units = [...board.units, ...dislodged.map(unit => ({ ...unit, dislodged: true }))];
        this.orderLayer.replaceChildren();
        this.unitLayer.replaceChildren();
        this.retreatLayer.replaceChildren();
        this.adjustmentLayer.replaceChildren();
        this.warning.textContent = "";
        this.details.textContent = "Select a province to inspect it.";

        for (const path of this.root.querySelectorAll(".province.selected"))
            path.classList.remove("selected");

        this.paintBoard();
        const errors = [];
        for (const unit of this.units) {
            try {
                this.drawUnit(unit);
            } catch (error) {
                errors.push(`${unit.province}: ${error.message}`);
            }
        }
        for (const order of this.orders) {
            if (nation && order.nation !== nation) continue;
            try {
                this.drawOrder(order, this.colors[order.nation]);
            } catch (error) {
                errors.push(`${order.text}: ${error.message}`);
            }
        }
        if (errors.length)
            this.warning.textContent =
                "Some map elements could not be drawn: " + errors.join("; ");
    }

    paintBoard() {
        const owners = new Map(Object.entries(this.board.supplyCenterOwners)
            .map(([province, owner]) => [territoryKey(province), owner]));

        for (const [province, id] of Object.entries(CENTERS)) {
            const owner = owners.get(territoryKey(province));
            const color = owner ? this.mapColors[owner] : this.neutralMapColor;
            const circles = this.root.querySelector(
                `#${CSS.escape(id)}`)?.querySelectorAll("circle");

            if (!circles || circles.length < 2)
                throw new Error(`Missing supply-center marker: ${province}`);

            circles[0].style.fill = color;
            circles[0].style.stroke = "#111827";
            circles[1].style.fill = owner ? this.colors[owner] : "#ffffff";
            circles[1].style.stroke = "none";

            if (this.paintTerritories) {
                this.path(province).style.fill = color;
                this.path(province).style.fillOpacity = "1";
            }
        }

        if (!this.paintTerritories) return;
        const active = new Set([
            ...this.units.map(unit => unit.nation),
            ...owners.values(),
            ...this.orders.filter(order => order.type === "RETREAT").map(order => order.nation)
        ]);
        for (const { nation, path, styles } of this.homeStyles) {
            for (const { name, value, priority } of styles) {
                if (value) path.style.setProperty(name, value, priority);
                else path.style.removeProperty(name);
            }
            if (!active.has(nation)) {
                path.style.setProperty("fill", this.neutralMapColor);
                path.style.setProperty("fill-opacity", "1");
            }
        }
    }

    drawUnit(unit) {
        const point = this.anchor(unit.province);
        const matching = this.orders.filter(order =>
            !["BUILD", "WAIVE"].includes(order.type)
            && order.nation === unit.nation
            && (!order.unitType || order.unitType === unit.type)
            && order.origin && territoryKey(order.origin) === territoryKey(unit.province));
        const tooltip = `${unit.dislodged ? "Dislodged: " : ""}${unit.nation} ${unit.type} ${unit.province}\n`
            + (matching.length ? matching.map(order => order.text).join("\n") : "No displayed order for this unit.");
        const offset = unit.dislodged ? 2.6 : 0;
        const group = svg("g", {
            class: unit.dislodged ? "unit-token dislodged" : "unit-token",
            transform: `translate(${point.x + offset} ${point.y - offset})`
                + ` scale(${unit.dislodged ? 0.6 : 1})`,
            opacity: !this.nation || unit.nation === this.nation ? 1 : 0.25,
            tabindex: 0,
            role: "button",
            "aria-label": tooltip.replaceAll("\n", "; ")
        });

        const title = svg("title");
        title.textContent = tooltip;
        const circle = svg("circle", {
            r: 1.7,
            fill: this.colors[unit.nation],
            stroke: "#ffffff",
            "stroke-width": 0.35
        });
        const label = svg("text", {
            "text-anchor": "middle",
            "dominant-baseline": "central",
            "font-size": 1.9,
            "font-weight": "bold",
            fill: "#ffffff",
            "pointer-events": "none"
        });

        label.textContent = unit.type === "ARMY" ? "A" : "F";
        group.append(title, circle, label);
        group.addEventListener("click", () => this.inspect(unit.province));
        group.addEventListener("keydown", event => {
            if (event.key === "Enter" || event.key === " ") {
                event.preventDefault();
                event.stopPropagation();
                this.inspect(unit.province);
            }
        });
        this.unitLayer.append(group);
    }

    path(province) {
        const path = this.root.querySelector(
            `#provinces > path#${CSS.escape(territoryKey(province))}`);

        if (!path)
            throw new Error(`No map position for ${province}.`);

        return path;
    }

    rootPoint(element, x, y) {
        const point = this.root.createSVGPoint();
        point.x = x;
        point.y = y;

        const local = element.getScreenCTM();
        const root = this.root.getScreenCTM();

        if (!local || !root)
            throw new Error("Unable to determine map coordinates.");

        return point.matrixTransform(root.inverse().multiply(local));
    }

    anchor(province) {
        if (COASTS[province])
            return COASTS[province];

        const circle = CENTERS[province]
            && this.root.querySelector(`#${CSS.escape(CENTERS[province])} circle`);

        if (circle)
            return this.rootPoint(
                circle, circle.cx.baseVal.value, circle.cy.baseVal.value);

        const path = this.path(province);
        const box = path.getBBox();
        return this.rootPoint(
            path, box.x + box.width / 2, box.y + box.height / 2);
    }

    inspect(province) {
        if (!this.board) return;

        const key = territoryKey(province);
        for (const path of this.root.querySelectorAll(".province"))
            path.classList.toggle("selected", path.id === key);

        const owner = Object.entries(this.board.supplyCenterOwners)
            .find(([location]) => territoryKey(location) === key)?.[1];

        this.details.textContent = [
            key.toUpperCase(),
            owner ? `Supply-center owner: ${owner}` : "No supply-center owner",
            ...this.units
                .filter(unit => territoryKey(unit.province) === key)
                .map(unit => `${unit.dislodged ? "Dislodged: " : ""}${unit.nation} ${unit.type} (${unit.province})`),
            ...this.orders
                .filter(order => [order.origin, order.target, order.auxiliaryTarget]
                    .filter(Boolean)
                    .some(location => territoryKey(location) === key))
                .map(order => order.text)
        ].join(" · ");
    }

    drawOrder(order, color) {
        if (order.type === "WAIVE") return;
        if (!color) throw new Error("Order has no recognized power color.");
        const group = svg("g");
        const origin = this.anchor(order.origin);
        const title = svg("title");
        title.textContent = order.text;
        group.append(title);

        if (order.type === "HOLD") {
            group.append(svg("circle", {
                cx: origin.x,
                cy: origin.y,
                r: 2.5,
                fill: "none",
                stroke: color,
                "stroke-width": 0.7
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

            this.label(group, origin, convoy ? "C" : "S", color);

        } else if (order.type === "RETREAT") {
            group.append(svg("circle", {
                cx: origin.x, cy: origin.y, r: 2.1, fill: "none",
                stroke: "#ffffff", "stroke-width": 0.7
            }), svg("circle", {
                cx: origin.x, cy: origin.y, r: 2.1, fill: "none",
                stroke: color, "stroke-width": 0.4, "stroke-dasharray": "0.9 0.6"
            }));
            this.line(group, origin, this.anchor(order.target), color, "0.9 0.6", true, 0.6);
            this.label(group, origin, "R", color, 1.8);
            this.retreatLayer.append(group);
            return;

        } else if (order.type === "BUILD") {
            if (!["ARMY", "FLEET"].includes(order.unitType))
                throw new Error("Build order has no recognized unit type.");
            group.setAttribute("transform", `translate(${origin.x} ${origin.y})`);
            group.append(svg("circle", {
                r: 1.7, fill: color, "fill-opacity": 0.2, stroke: "none"
            }), svg("circle", {
                r: 2.5, fill: "none", stroke: "#ffffff", "stroke-width": 1.1
            }), svg("circle", {
                r: 2.5, fill: "none", stroke: color, "stroke-width": 0.65,
                "stroke-dasharray": "0.05 1.15", "stroke-linecap": "round"
            }));
            const label = svg("text", {
                "text-anchor": "middle", "dominant-baseline": "central",
                "font-size": 1.9, "font-weight": "bold", fill: color,
                stroke: "#ffffff", "stroke-width": 0.25, "paint-order": "stroke"
            });
            label.textContent = order.unitType === "ARMY" ? "A" : "F";
            group.append(label);
            this.adjustmentLayer.append(group);
            return;

        } else if (order.type === "DESTROY") {
            group.setAttribute("transform", `translate(${origin.x} ${origin.y})`);
            for (const [stroke, width] of [["#ffffff", 1.35], ["#dc2626", 0.8]])
                for (const direction of [-1, 1])
                    group.append(svg("line", {
                        x1: -2.1, y1: direction * -2.1, x2: 2.1, y2: direction * 2.1,
                        stroke, "stroke-width": width, "stroke-linecap": "round"
                    }));
            this.adjustmentLayer.append(group);
            return;
        } else {
            throw new Error(`Unsupported order type: ${order.type}`);
        }

        this.orderLayer.append(group);
    }

    label(group, point, text, color, size = 3) {
        const label = svg("text", {
            x: point.x + 2, y: point.y - 2, fill: color,
            stroke: "#ffffff", "stroke-width": 0.15 * size,
            "paint-order": "stroke", "font-size": size, "font-weight": "bold"
        });
        label.textContent = text;
        group.append(label);
    }

    line(group, from, to, color, dash, arrow, scale = 1) {
        const dx = to.x - from.x;
        const dy = to.y - from.y;
        const distance = Math.hypot(dx, dy);

        if (distance < 0.01) {
            group.append(svg("circle", {
                cx: from.x,
                cy: from.y,
                r: 2.5,
                fill: "none",
                stroke: color,
                "stroke-width": 0.7 * scale,
                "stroke-dasharray": dash
            }));
            return;
        }

        const ux = dx / distance;
        const uy = dy / distance;
        const trim = Math.min(2, distance / 4);
        const end = { x: to.x - ux * trim, y: to.y - uy * trim };

        const attributes = {
            x1: from.x + ux * trim,
            y1: from.y + uy * trim,
            x2: end.x,
            y2: end.y,
            "stroke-linecap": "round"
        };

        group.append(svg("line", {
            ...attributes,
            stroke: "#ffffff",
            "stroke-width": 1.25 * scale
        }));

        group.append(svg("line", {
            ...attributes,
            stroke: color,
            "stroke-width": 0.7 * scale,
            "stroke-dasharray": dash
        }));

        if (arrow) {
            const length = Math.min(2 * scale, distance / 3);
            const width = length / 2;
            const baseX = end.x - ux * length;
            const baseY = end.y - uy * length;

            group.append(svg("polygon", {
                points: `${end.x},${end.y} `
                    + `${baseX - uy * width},${baseY + ux * width} `
                    + `${baseX + uy * width},${baseY - ux * width}`,
                fill: color,
                stroke: "#ffffff",
                "stroke-width": 0.2 * scale
            }));
        }
    }
}