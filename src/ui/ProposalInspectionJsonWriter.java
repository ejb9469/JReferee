package ui;

import agents.execution.ProposalInspector.*;
import agents.search.PaperSearchProfile;
import phase.Order;

import java.util.*;

import static io.json.JsonEscaper.appendString;

public final class ProposalInspectionJsonWriter {

    private ProposalInspectionJsonWriter() { }

    public static String toJson(Inspection inspection) {
        StringBuilder out = new StringBuilder("{\"schema\":\"jreferee-proposal-inspection-v2\",\"power\":");
        appendString(out, inspection.power().name());
        out.append(",\"method\":"); appendString(out, inspection.method().name());
        out.append(",\"models\":").append(strings(inspection.models()));
        out.append(",\"board\":").append(BoardSnapshotJsonWriter.toJson(
                BoardSnapshotMapper.from(inspection.root().moment(), inspection.root().board())));
        var options = inspection.options();
        out.append(",\"options\":{\"candidates\":").append(options.candidates())
                .append(",\"iterations\":").append(options.iterations()).append(",\"rollouts\":").append(options.rollouts())
                .append(",\"rolloutCandidates\":").append(options.rolloutCandidates()).append(",\"search\":").append(options.search());
        out.append(",\"poolSeed\":"); appendString(out, Long.toString(options.poolSeed()));
        out.append(",\"searchSeed\":"); appendString(out, Long.toString(options.searchSeed()));
        out.append(",\"rolloutSeed\":"); appendString(out, Long.toString(options.rolloutSeed()));
        out.append("},\"searchSettings\":");
        if (inspection.search().isEmpty()) out.append("null");
        else {
            var profile = PaperSearchProfile.forState(inspection.root());
            out.append("{\"profile\":"); appendString(out, profile.name());
            out.append(",\"agentLambda\":").append(profile.agentLambda())
                    .append(",\"populationLambdas\":").append(profile.population().values())
                    .append(",\"populationWeights\":").append(profile.population().probabilities())
                    .append(",\"temperature\":\"ADAPTIVE\",\"output\":\"AVERAGE\",\"rules\":\"JUDGE:1:0\"")
                    .append(",\"scoring\":\"SUM_OF_SQUARES\",\"continuation\":\"UNIFORM_TO_CURRENT_WINTER_BEFORE_ADJUSTMENTS\"}");
        }
        out.append(",\"work\":");
        if (inspection.work().isEmpty()) out.append("null");
        else {
            var work = inspection.work().orElseThrow();
            out.append("{\"requests\":").append(work.requests()).append(",\"hits\":").append(work.hits())
                    .append(",\"simulations\":").append(work.simulations()).append(",\"evaluations\":").append(work.evaluations())
                    .append(",\"failures\":").append(work.failures()).append('}');
        }
        out.append(",\"poolSizes\":{");
        boolean first = true;
        for (var entry : inspection.pools().entrySet()) {
            if (!first) out.append(',');
            appendString(out, entry.getKey().name()); out.append(':').append(entry.getValue().size());
            first = false;
        }
        out.append("},\"generation\":{");
        first = true;
        for (var entry : new TreeMap<>(inspection.generation()).entrySet()) {
            if (!first) out.append(',');
            first = false;
            appendString(out, entry.getKey().name());
            var learned = entry.getValue().learned();
            out.append(":{\"target\":").append(learned.target()).append(",\"attempts\":").append(learned.attempts())
                    .append(",\"deadEnds\":").append(learned.deadEnds()).append(",\"duplicates\":").append(learned.duplicates())
                    .append(",\"choices\":").append(learned.choices()).append(",\"learnedAvailable\":").append(learned.candidates().size());
            var hybrid = entry.getValue().hybrid();
            out.append(",\"learnedSelected\":").append(hybrid.isPresent() ? hybrid.orElseThrow().learnedSelected() : learned.candidates().size())
                    .append(",\"heuristicSelected\":").append(hybrid.isPresent() ? hybrid.orElseThrow().heuristicSelected() : 0)
                    .append(",\"heuristicAvailable\":").append(hybrid.isPresent() ? hybrid.orElseThrow().heuristicAvailable() : 0)
                    .append(",\"overlap\":").append(hybrid.isPresent() ? hybrid.orElseThrow().overlap() : 0).append('}');
        }
        out.append("},\"rankings\":{");
        first = true;
        for (Ranking ranking : Ranking.values()) {
            if (ranking == Ranking.SEARCH && inspection.search().isEmpty()) continue;
            if (!first) out.append(',');
            appendString(out, ranking.name());
            out.append(':').append(inspection.top(options.candidates(), ranking).stream().map(Proposal::index).toList());
            first = false;
        }
        out.append("},\"proposals\":[");
        for (Proposal proposal : inspection.proposals()) {
            if (proposal.index() > 0) out.append(',');
            out.append("{\"index\":").append(proposal.index()).append(",\"source\":");
            appendString(out, proposal.action().source().name());
            out.append(",\"logLinear\":").append(number(proposal.logLinear()))
                    .append(",\"logGru\":").append(number(proposal.logGru()))
                    .append(",\"logAnchor\":").append(number(proposal.action().logPrior()))
                    .append(",\"anchorPoolProbability\":").append(proposal.anchorPoolProbability()).append(",\"search\":");
            if (proposal.search().isEmpty()) out.append("null");
            else {
                var score = proposal.search().orElseThrow();
                out.append("{\"probability\":").append(score.probability()).append(",\"meanValue\":")
                        .append(score.meanValue()).append(",\"samples\":").append(score.samples()).append('}');
            }
            out.append(",\"findings\":[");
            for (int index = 0; index < proposal.findings().size(); index++) {
                if (index > 0) out.append(',');
                var finding = proposal.findings().get(index);
                out.append("{\"orderIndex\":").append(finding.orderIndex()).append(",\"kind\":");
                appendString(out, finding.kind().name());
                out.append(",\"code\":"); appendString(out, finding.code().name());
                out.append(",\"message\":"); appendString(out, finding.message());
                out.append('}');
            }
            out.append(']');
            var counts = proposal.coordination();
            out.append(",\"coordination\":{\"ownSupports\":").append(counts.ownSupports())
                    .append(",\"mismatchedSupports\":").append(counts.mismatchedSupports())
                    .append(",\"ownConvoys\":").append(counts.ownConvoys())
                    .append(",\"mismatchedConvoys\":").append(counts.mismatchedConvoys()).append("},\"orders\":[");
            for (int index = 0; index < proposal.action().orders().size(); index++) {
                if (index > 0) out.append(',');
                Order order = (Order) proposal.action().orders().get(index);
                out.append("{\"text\":"); appendString(out, proposal.orders().get(index));
                out.append(",\"origin\":"); appendString(out, inspection.root().board().locationOf(order.unit()).name());
                out.append(",\"type\":"); appendString(out, order.orderType().name());
                out.append(",\"target\":"); nullable(out, order.target() == null ? null : order.target().name());
                out.append(",\"auxiliaryTarget\":"); nullable(out, order.auxiliaryTarget() == null ? null : order.auxiliaryTarget().name());
                out.append('}');
            }
            out.append("]}");
        }
        return out.append("]}").toString();
    }

    static String strings(Map<String, String> values) {
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (var entry : new TreeMap<>(values).entrySet()) {
            if (!first) out.append(',');
            appendString(out, entry.getKey()); out.append(':'); appendString(out, entry.getValue());
            first = false;
        }

        return out.append('}').toString();
    }

    static void nullable(StringBuilder out, String value) {
        if (value == null) out.append("null");
        else appendString(out, value);
    }

    private static String number(double value) {
        if (value == Double.NEGATIVE_INFINITY) return "\"-Infinity\"";
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Invalid numeric score");
        return Double.toString(value);
    }
}