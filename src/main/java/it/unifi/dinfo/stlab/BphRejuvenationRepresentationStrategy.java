package it.unifi.dinfo.stlab;

import org.oristool.models.gspn.GSPNTransient;
import org.oristool.models.pn.PostUpdater;
import org.oristool.models.pn.Priority;
import org.oristool.models.stpn.MarkingExpr;
import org.oristool.models.stpn.trees.StochasticTransitionFeature;
import org.oristool.petrinet.Marking;
import org.oristool.petrinet.PetriNet;
import org.oristool.petrinet.Place;
import org.oristool.petrinet.Transition;
import org.oristool.util.Pair;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;

public class BphRejuvenationRepresentationStrategy extends RejuvenationRepresentationStrategy {

    private final int phases;
    private List<Double> weights;

    private static final double COMPLETION_TOLERANCE = 1e-6;

    private final Map<Integer, Place> phasePlaces = new LinkedHashMap<>();

    public BphRejuvenationRepresentationStrategy(int phases) {
        if (phases < 1) throw new IllegalArgumentException("phases >= 1");
        this.phases = phases;
    }

    /** Pesi a priori dello smistamento iniziale: uno per fase. */
    public BphRejuvenationRepresentationStrategy weights(double... w) {
        if (w.length != phases)
            throw new IllegalArgumentException(phases + " weights required");
        this.weights = DoubleStream.of(w)
                .boxed()
                .collect(Collectors.toList());
        return this;
    }

    @Override
    public void build(PetriNet net) {
        Objects.requireNonNull(net);
        if (weights == null) throw new IllegalStateException("weights not set");

        Place rejuvenation = net.getPlace("Rejuvenation");
        Place ready = net.getPlace("Ready");

        for (int i = 1; i <= phases; i++)
            phasePlaces.put(i, net.addPlace("RejPh" + i));

        for (int j = 1; j <= phases; j++) {
            Transition d = net.addTransition("rejToPh" + j);
            net.addPrecondition(rejuvenation, d);
            net.addPostcondition(d, phasePlaces.get(j));
            d.addFeature(StochasticTransitionFeature.newDeterministicInstance(
                    BigDecimal.ZERO, MarkingExpr.from(num(weights.get(j - 1)), net)));
            d.addFeature(new Priority(0));
        }

        for (int i = 1; i <= phases; i++) {
            Transition t = net.addTransition("rejT" + i);
            net.addPrecondition(phasePlaces.get(i), t);
            t.addFeature(StochasticTransitionFeature.newExponentialInstance(
                    BigDecimal.valueOf(i)));
            if (i < phases) {
                net.addPostcondition(t, phasePlaces.get(i + 1));
            } else {
                net.addPostcondition(t, ready);
                t.addFeature(new PostUpdater("AgingLevel=0", net));
            }
        }
    }

    @Override
    public String getInRejuvenationPlaces() {
        StringBuilder inRejuvenationPlaces = new StringBuilder();
        for (int i = 1; i <= phases; i++)
            inRejuvenationPlaces.append(i > 1 ? "+" : "").append("RejPh").append(i);
        return inRejuvenationPlaces.toString();
    }

    public Place phase(int i) { return phasePlaces.get(i); }

    @Override
    public double[] getRejuvenationTimeDistribution(double step) {
        if (step <= 0) throw new IllegalArgumentException("step > 0");
        if (weights == null) throw new IllegalStateException("weights not set");

        PetriNet net = new PetriNet();
        Marking marking = new Marking();

        Place start = net.addPlace("Start");
        Place completed = net.addPlace("Completed");
        marking.setTokens(start, 1);

        Map<Integer, Place> ph = new LinkedHashMap<>();
        for (int i = 1; i <= phases; i++)
            ph.put(i, net.addPlace("RejPh" + i));

        for (int j = 1; j <= phases; j++) {
            Transition d = net.addTransition("toPh" + j);
            net.addPrecondition(start, d);
            net.addPostcondition(d, ph.get(j));
            d.addFeature(StochasticTransitionFeature.newDeterministicInstance(
                    BigDecimal.ZERO, MarkingExpr.from(num(weights.get(j - 1)), net)));
            d.addFeature(new Priority(0));
        }

        for (int i = 1; i <= phases; i++) {
            Transition t = net.addTransition("t" + i);
            net.addPrecondition(ph.get(i), t);
            net.addPostcondition(t, i < phases ? ph.get(i + 1) : completed);
            t.addFeature(StochasticTransitionFeature.newExponentialInstance(
                    BigDecimal.valueOf(i)));
        }

        // limite iniziale: 10x il tempo medio di assorbimento partendo dalla prima fase
        double meanAbsorption = 0.0;
        for (int i = 1; i <= phases; i++) meanAbsorption += 1.0 / i;
        double limit = Math.max(step, 10 * meanAbsorption);

        while (true) {
            Pair<Map<Marking, Integer>, double[][]> result = GSPNTransient.builder()
                    .timePoints(0.0, limit, step)
                    .build()
                    .compute(net, marking);

            double[][] probs = result.second();
            double[] cdf = new double[probs.length];
            for (Map.Entry<Marking, Integer> e : result.first().entrySet())
                if (e.getKey().getTokens(completed) > 0)
                    for (int t = 0; t < probs.length; t++)
                        cdf[t] += probs[t][e.getValue()];

            if (cdf[cdf.length - 1] >= 1.0 - COMPLETION_TOLERANCE) {
                int end = 0;
                while (cdf[end] < 1.0 - COMPLETION_TOLERANCE) end++;
                double[] out = new double[end + 1];
                for (int t = 0; t < end; t++) out[t] = Math.min(cdf[t], 1.0);
                out[end] = 1.0;
                return out;
            }
            limit *= 2;
        }
    }

    private static String num(double d) {
        if (d == 0.0) return "0";
        return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
    }
}
