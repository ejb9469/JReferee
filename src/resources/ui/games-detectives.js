"use strict";

/*
 * Structural findings and local assessments are separate views.
 * Display filtering never reruns detectives or adjudication.
 */
const DetectivePanel = (() => {

    const kindsGroup = document.querySelector("#detectives-kinds");
    const kindsHost = document.querySelector("#detectives-kind-options");
    const selectAll = document.querySelector("#detectives-select-all");
    const clearSelection = document.querySelector("#detectives-clear-selection");
    const selectCompleteness =
        document.querySelector("#detectives-completeness");

    const status = document.querySelector("#detectives-status");
    const evidence = document.querySelector("#detectives-evidence");
    const findingsHost = document.querySelector("#detectives-findings");
    const coverageBody = document.querySelector("#detectives-coverage-body");
    const coverageSummary =
        document.querySelector("#detectives-coverage-summary");

    /*
     * Add presentation without requiring changes to the existing HTML,
     * stylesheet, server asset list, or games.js public interface.
     */
    const assessmentSummary = document.createElement("section");
    assessmentSummary.id = "detectives-local-assessments";
    assessmentSummary.setAttribute(
        "aria-labelledby", "detectives-local-assessments-title");

    findingsHost.parentNode.insertBefore(assessmentSummary, findingsHost);

    let report = null;
    let country = "";
    let inspect = null;

    const selectedKinds = new Set();
    const assessmentsByFinding = new Map();

    const coverageLabels = {
        EVALUATED_WITH_FINDINGS: "Evaluated — findings",
        EVALUATED_WITH_NO_FINDINGS: "Evaluated — no findings",
        SKIPPED_MISSING_EVIDENCE: "Skipped — missing evidence",
        UNSUPPORTED_UNIMPLEMENTED: "Not implemented / not registered"
    };

    const assessmentLabels = {
        EVALUATED: "Evaluated locally",
        PARTIAL_ERROR: "Some local assessments failed",
        ERROR: "Local assessment failed",
        DISABLED: "Disabled",
        INCOMPLETE_SCENARIO: "Not run — incomplete scenario",
        NO_SUPPORTED_FINDINGS: "No supported findings to assess"
    };

    const claimLabels = {
        SUPPORT_EFFECTIVE: "Support accepted by the adjudicator",
        SUPPORTED_MOVE_SUCCEEDED: "Supported move succeeded",
        SUBJECT_SURVIVED: "Recipient remained on the board after movement"
    };

    const supportKinds = new Set([
        "SUPPORT_TO_MOVE",
        "SUPPORT_TO_HOLD"
    ]);


    // Control events \\

    kindsHost.addEventListener("change", event => {

        const checkbox = event.target;

        if (!checkbox || checkbox.type !== "checkbox")
            return;

        if (checkbox.checked)
            selectedKinds.add(checkbox.value);
        else
            selectedKinds.delete(checkbox.value);

        renderFindings();

    });

    selectAll.addEventListener("click", () => selectKinds(true));
    clearSelection.addEventListener("click", () => selectKinds(false));
    selectCompleteness.addEventListener("change", renderFindings);


    // Public interface \\

    function reset(message = "Select a game.") {

        report = null;
        country = "";
        inspect = null;

        selectedKinds.clear();
        assessmentsByFinding.clear();

        kindsHost.replaceChildren();
        kindsGroup.disabled = true;

        selectCompleteness.value = "";
        selectCompleteness.disabled = true;

        status.textContent = message;
        evidence.textContent = "";

        findingsHost.replaceChildren();
        assessmentSummary.replaceChildren();
        coverageBody.replaceChildren();

        coverageSummary.textContent = "No investigation coverage available.";
        document.querySelector("#detectives-coverage").open = false;

    }

    function show(investigation, selectedCountry, inspectProvince) {

        const changed = investigation !== report;

        if (changed)
            reset();

        report = investigation || null;
        country = selectedCountry;
        inspect = inspectProvince;

        if (!report) {

            status.textContent = "No investigation report was supplied.";
            return;

        }

        if (report.status !== "EVALUATED") {

            status.textContent =
                `${report.status}: `
                + `${report.message || "Snapshot not investigated."}`;

            return;

        }

        if (changed) {

            for (const entry of report.coverage) {

                if (!entry.implemented)
                    continue;

                const label = document.createElement("label");
                const checkbox = document.createElement("input");

                checkbox.type = "checkbox";
                checkbox.value = entry.kind;
                checkbox.checked = true;

                selectedKinds.add(entry.kind);

                label.append(
                    checkbox,
                    element("span", entry.kind.replaceAll("_", " ")));

                kindsHost.append(label);

            }

            const batch = report.localSupportAssessments;

            for (const entry of batch?.entries || [])
                assessmentsByFinding.set(entry.findingIndex, entry);

            renderCoverage();
            renderAssessmentSummary();

        }

        kindsGroup.disabled = false;
        selectCompleteness.disabled = false;

        const unknown = report.unknownUnits;

        evidence.textContent =
            `${report.knownOrders}/${report.activeUnits} unit orders known. `
            + (unknown.length
                ? `Unknown: ${unknown.join("; ")}. `
                : "No unknown unit orders in this snapshot. ")
            + `Structural context: ${report.contextId}.`;

        renderFindings();

    }


    // Finding selection \\

    function selectKinds(checked) {

        selectedKinds.clear();

        for (const checkbox of kindsHost.querySelectorAll("input")) {

            checkbox.checked = checked;

            if (checked)
                selectedKinds.add(checkbox.value);

        }

        renderFindings();

    }

    function renderFindings() {

        findingsHost.replaceChildren();

        if (!report || report.status !== "EVALUATED")
            return;

        const completeness = selectCompleteness.value;

        const selected = report.findings.filter(finding =>
            selectedKinds.has(finding.kind)
            && (!country || finding.participants.some(
                participant => participant.nation === country))
            && (!completeness
                || finding.complete === (completeness === "complete")));

        status.textContent =
            `${selected.length} of ${report.findings.length} finding(s) displayed`
            + (country ? ` involving ${country}` : "")
            + ". Structural coverage and local assessment totals "
            + "remain whole-snapshot.";

        if (selected.length === 0) {

            const message = selectedKinds.size === 0
                ? "No kinds selected. Select one or more registered kinds "
                    + "to display findings."
                : report.findings.length === 0
                    ? "Evaluated detectives found no patterns in the known "
                        + "submissions. Unimplemented kinds were not tested."
                    : "No findings match the display filters.";

            findingsHost.append(element("p", message));
            return;

        }

        for (const finding of selected)
            findingsHost.append(findingCard(finding));

    }

    function findingCard(finding) {

        const card = document.createElement("article");

        card.className = "detective-finding";
        card.classList.toggle("incomplete", !finding.complete);
        card.classList.toggle(
            "diagnostic", finding.interpretation === "DIAGNOSTIC");

        card.append(element(
            "h3",
            `${finding.kind.replaceAll("_", " ")} — ${finding.focus}`));

        card.append(element(
            "p",
            `Structural finding: `
                + `${finding.complete ? "complete" : "incomplete"} local pattern · `
                + finding.interpretation.replaceAll("_", " ")));

        const focus = element(
            "button", `Inspect ${finding.focus} on map`);

        focus.type = "button";

        focus.addEventListener("click", () => {

            if (!inspect)
                return;

            inspect(finding.focus);

            document.querySelector("#map-host")
                .scrollIntoView({ block: "nearest" });

        });

        card.append(focus);

        const participants = document.createElement("ul");

        for (const participant of finding.participants) {

            const submission = participant.order
                ? `${participant.order} [${participant.provenance}]`
                : `${participant.unit} — order unknown`;

            participants.append(element(
                "li",
                `${participant.role.replaceAll("_", " ")}: ${submission}`));

        }

        card.append(participants);

        if (finding.missingOrders.length) {

            const missing = element(
                "p",
                "Missing structural prerequisites: "
                    + finding.missingOrders.join("; "));

            missing.className = "detective-missing";
            card.append(missing);

        }

        const definition = document.createElement("details");

        definition.append(element("summary", "Structural definition and versions"));
        definition.append(element("p", finding.semantics));

        definition.append(element(
            "p",
            `Detective: ${finding.detectiveVersion} · `
                + `Definition: ${finding.definitionVersion}`));

        card.append(definition);
        appendAssessment(card, finding);

        return card;

    }


    // Local assessment summary \\

    function renderAssessmentSummary() {

        assessmentSummary.replaceChildren();

        const heading = element("h3", "Local support assessments");
        heading.id = "detectives-local-assessments-title";
        assessmentSummary.append(heading);

        const batch = report.localSupportAssessments;

        if (!batch) {

            assessmentSummary.append(element(
                "p",
                "No local assessment data was supplied by the server. "
                    + "Structural findings are still available."));

            return;

        }

        assessmentSummary.append(element(
            "p",
            `${assessmentLabels[batch.status] || batch.status}. `
                + `${batch.message || ""}`));

        assessmentSummary.append(element(
            "p",
            `${batch.evaluatedCount} of ${batch.eligibleCount} support finding(s) `
                + `assessed; ${batch.failedCount} failed. `
                + "Totals are before display filtering."));

        assessmentSummary.append(element(
            "p",
            "Only support-to-move and support-to-hold are assessed. "
                + "A complete local pattern is not sufficient: "
                + "all active units must have known orders. "
                + "No unknown order is replaced with HOLD."));

        const configuration = batch.configuration;

        if (configuration) {

            const details = document.createElement("details");
            details.append(element("summary", "Local resolution configuration"));

            details.append(element(
                "p",
                `Policy: ${configuration.policy} · `
                    + `Trials: ${configuration.trialCount} · `
                    + `Seed: ${configuration.shuffleSeed}`));

            details.append(element(
                "p", `Engine revision: ${configuration.engineRevision}`));

            details.append(element(
                "p", `Resolver identity: ${configuration.rulesetId}`));

            details.append(element(
                "p", `Baseline scenario: ${configuration.scenarioId}`));

            assessmentSummary.append(details);

        }

        assessmentSummary.append(element(
            "p",
            "These are local simulated outcomes under the displayed configuration. "
                + "They are not DBN annotations, proof of agreement, "
                + "or evidence that the support was necessary."));

    }


    // Per-finding local claims \\

    function appendAssessment(card, finding) {

        if (!supportKinds.has(finding.kind)) {

            const message = element(
                "p",
                "Local assessment: no assessor is enabled for this kind.");

            message.className = "muted";
            card.append(message);

            return;

        }

        const section = document.createElement("section");
        section.append(element("h4", "Local support assessment"));

        const entry = assessmentsByFinding.get(finding.index);

        if (!entry) {

            section.append(element(
                "p",
                "Not assessed: no local result was supplied for this finding."));

            card.append(section);
            return;

        }

        section.append(element(
            "p",
            assessmentLabels[entry.status] || entry.status));

        if (entry.message)
            section.append(element("p", entry.message));

        if (entry.status !== "EVALUATED") {

            /*
             * Unavailable/failed assessments have no truth values.
             * Do not display them as FALSE.
             */
            card.append(section);
            return;

        }

        section.append(element(
            "p",
            `Assessment version: ${entry.assessmentVersion}. `
                + "Baseline only; no counterfactual comparison."));

        const claims = document.createElement("ul");

        for (const claim of entry.claims) {

            const item = document.createElement("li");

            item.append(element(
                "strong",
                `${claimLabels[claim.claim] || claim.claim}: ${claim.truth}`));

            const details = document.createElement("details");
            details.append(element("summary", "Local evidence"));

            for (const observation of claim.evidence) {

                details.append(element(
                    "p",
                    `${observation.code} · Scenario ${observation.scenarioId}`));

                details.append(element("p", observation.explanation));

            }

            item.append(details);
            claims.append(item);

        }

        section.append(claims);

        section.append(element(
            "p",
            "Accepted support does not mean necessary support. "
                + "Rejected support does not by itself mean the support was cut. "
                + "A dislodged recipient may still retreat."));

        card.append(section);

    }


    // Unfiltered structural coverage \\

    function renderCoverage() {

        coverageBody.replaceChildren();

        const registered = report.coverage.filter(
            entry => entry.implemented);

        const evaluated = registered.filter(entry =>
            entry.status === "EVALUATED_WITH_FINDINGS"
            || entry.status === "EVALUATED_WITH_NO_FINDINGS");

        const unsupported = report.coverage.filter(entry =>
            entry.status === "UNSUPPORTED_UNIMPLEMENTED").length;

        const skipped = report.coverage.filter(entry =>
            entry.status === "SKIPPED_MISSING_EVIDENCE").length;

        coverageSummary.textContent =
            `${registered.length} registered · ${evaluated.length} evaluated · `
            + `${skipped} skipped · ${unsupported} unsupported. `
            + `Scope: ${report.coverageScope}. `
            + "Structural counts below are before all display filters; "
            + "they are not assessment coverage.";

        for (const entry of report.coverage) {

            const row = document.createElement("tr");

            row.append(element(
                "td", entry.kind.replaceAll("_", " ")));

            row.append(element(
                "td", coverageLabels[entry.status] || entry.status));

            const wasEvaluated =
                entry.status === "EVALUATED_WITH_FINDINGS"
                || entry.status === "EVALUATED_WITH_NO_FINDINGS";

            row.append(element(
                "td",
                wasEvaluated
                    ? String(entry.findingCount)
                    : "Not evaluated"));

            row.append(element(
                "td",
                entry.missingEvidence.length
                    ? entry.missingEvidence.join(", ")
                    : "—"));

            coverageBody.append(row);

        }

    }


    // Safe DOM construction \\

    function element(tag, text) {

        const node = document.createElement(tag);
        node.textContent = text;

        return node;

    }

    return Object.freeze({ reset, show });

})();