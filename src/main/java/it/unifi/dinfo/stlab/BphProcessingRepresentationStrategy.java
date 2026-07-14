package it.unifi.dinfo.stlab;

import org.oristool.models.gspn.GSPNTransient;
import org.oristool.models.pn.Priority;
import org.oristool.models.stpn.MarkingExpr;
import org.oristool.models.stpn.trees.StochasticTransitionFeature;
import org.oristool.petrinet.*;
import org.oristool.util.Pair;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;

import static java.util.stream.Collectors.toList;

public class BphProcessingRepresentationStrategy extends RequestProcessingRepresentationStrategy {

    private final int phases;
    private final int maxAgingLevel;
    private List<Double> phaseRates;                               // coeff. per fase (es. 1..n)
    private final Map<Integer, List<Double>> agingWeights = new LinkedHashMap<>();

    private static final double COMPLETION_TOLERANCE = 1e-6;

    // esposti dopo build(), per agganciare i pezzi successivi
    private Place toExecute;
    private Transition finalTransition;
    private final Map<Integer, Place> phasePlaces = new LinkedHashMap<>();

    public BphProcessingRepresentationStrategy(int phases, int maxAgingLevel) {
        if (phases < 1)        throw new IllegalArgumentException("phases >= 1");
        if (maxAgingLevel < 0) throw new IllegalArgumentException("maxAgingLevel >= 0");
        this.phases = phases;
        this.maxAgingLevel = maxAgingLevel;
    }

    /** Coefficienti del numeratore per fase: (Ph_i * rate_i)/(somma). */
    public BphProcessingRepresentationStrategy phaseRates(double... rates) {
        if (rates.length != phases)
            throw new IllegalArgumentException(phases + " rates needed");
        this.phaseRates = DoubleStream.of(rates)
                .boxed()
                .collect(Collectors.toList());
        return this;
    }

    /** Pesi a priori dello smistamento per un aging level: uno per fase. */
    public BphProcessingRepresentationStrategy weights(int agingLevel, double... w) {
        if (agingLevel < 0 || agingLevel > maxAgingLevel)
            throw new IllegalArgumentException("Out of Range Aging Level: " + agingLevel);
        if (w.length != phases)
            throw new IllegalArgumentException(phases + " weights required for the aging level " + agingLevel);
        agingWeights.put(agingLevel, DoubleStream.of(w)
                .boxed()
                .collect(Collectors.toList()));
        return this;
    }

    @Override
    public void build(PetriNet net) {
        Objects.requireNonNull(net);
        if (phaseRates == null) throw new IllegalStateException("phaseRates not set");
        for (int k = 0; k <= maxAgingLevel; k++)
            if (!agingWeights.containsKey(k))
                throw new IllegalStateException("Missing weights for aging level " + k);

        // 1) posti fase (Map 1-indexed: get(i) / get(i+1), indici naturali)
        for (int i = 1; i <= phases; i++)
            phasePlaces.put(i, net.addPlace("Ph" + i));

        // denominatore comune: Ph1+Ph2+...+Phn
        // todo questo poi da usare anche nell'altro metodo per dare in process token
//        StringBuilder denom = new StringBuilder();
//        for (int i = 1; i <= phases; i++)
//            denom.append(i > 1 ? "+" : "").append("Ph").append(i);
        String denom = getInProcessPlaces();

        // 2) catena t1..tn marking-dependent; t_n è sink (solo precondizione)
        for (int i = 1; i <= phases; i++) {
            Transition t = net.addTransition("t" + i);
            net.addPrecondition(phasePlaces.get(i), t);
            if (i < phases) net.addPostcondition(t, phasePlaces.get(i + 1));
            String rate = "(Ph" + i + "*" + num(phaseRates.get(i - 1)) + ")/(" + denom + ")";
            t.addFeature(StochasticTransitionFeature.newExponentialInstance(
                    BigDecimal.ONE, MarkingExpr.from(rate, net)));
            if(i == phases) finalTransition = t;
        }

        // 3) sorgente
        toExecute = net.addPlace("ToExecute");

        // 4) per ogni aging level: switch livello-1 + smistamento pesato livello-2
        for (int k = 0; k <= maxAgingLevel; k++) {
            Place agingK = net.addPlace("Aging" + k);

            Transition toAgingK = net.addTransition("toAging" + k);
            net.addPrecondition(toExecute, toAgingK);
            net.addPostcondition(toAgingK, agingK);
            toAgingK.addFeature(new EnablingFunction("AgingLevel==" + k));
            toAgingK.addFeature(StochasticTransitionFeature.newDeterministicInstance(
                    BigDecimal.ZERO, MarkingExpr.from("1", net)));
            toAgingK.addFeature(new Priority(0));

            List<Double> w = agingWeights.get(k);
            for (int j = 1; j <= phases; j++) {
                Transition d = net.addTransition("aging" + k + "ToPh" + j);
                net.addPrecondition(agingK, d);
                net.addPostcondition(d, phasePlaces.get(j));
                d.addFeature(StochasticTransitionFeature.newDeterministicInstance(
                        BigDecimal.ZERO, MarkingExpr.from(num(w.get(j - 1)), net)));  // peso a priori
                d.addFeature(new Priority(0));
            }
        }

        // linking to the rest of the net
        Transition arrive = net.getTransition("arrive");
        net.addPostcondition(arrive, toExecute);
        net.addPostcondition(finalTransition, net.getPlace("AgingEffect"));
        net.addPostcondition(finalTransition, net.getPlace("Executed"));
    }

    public Place toExecute()  { return toExecute; }
    public Place phase(int i) { return phasePlaces.get(i); }

    private static List<Double> toList(double[] a) {
        List<Double> l = new ArrayList<>(a.length);
        for (double d : a) l.add(d);
        return l;
    }

    private static String num(double d) {
        if (d == 0.0) return "0";
        return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
    }



    @Override
    public String getInProcessPlaces() {
        StringBuilder inProcessPlaces = new StringBuilder();
        for (int i = 1; i <= phases; i++)
            inProcessPlaces.append(i > 1 ? "+" : "").append("Ph").append(i);
        return inProcessPlaces.toString();
    }

    @Override
    public String emptyProcessPlaces() {
        StringBuilder inProcessPlaces = new StringBuilder();
        for (int i = 1; i <= phases; i++) {
            inProcessPlaces.append("Ph").append(i).append("=0;");
        }
        return inProcessPlaces.toString();
    }

    @Override
    public String getThroughputReward(){
        return "Ph" + phases + "*" + phaseRates.get(phaseRates.size() - 1);
    }

    @Override
    public Map<Integer, double[]> getCompletionDistributionForEachAgingLvl(double step) {
        if (step <= 0) throw new IllegalArgumentException("step > 0");
        if (phaseRates == null) throw new IllegalStateException("phaseRates not set");
        for (int k = 0; k <= maxAgingLevel; k++)
            if (!agingWeights.containsKey(k))
                throw new IllegalStateException("Missing weights for aging level " + k);

        Map<Integer, double[]> distributions = new LinkedHashMap<>();
        for (int k = 0; k <= maxAgingLevel; k++)
            distributions.put(k, completionCdf(k, step));
        return distributions;
    }

    /** CDF del tempo di completamento di una singola richiesta al livello di aging dato. */
    private double[] completionCdf(int agingLevel, double step) {
        PetriNet net = new PetriNet();
        Marking marking = new Marking();

        Place start = net.addPlace("Start");
        Place completed = net.addPlace("Completed");
        marking.setTokens(start, 1);

        Map<Integer, Place> ph = new LinkedHashMap<>();
        for (int i = 1; i <= phases; i++)
            ph.put(i, net.addPlace("Ph" + i));

        // smistamento pesato iniziale, come in build()
        List<Double> w = agingWeights.get(agingLevel);
        for (int j = 1; j <= phases; j++) {
            Transition d = net.addTransition("toPh" + j);
            net.addPrecondition(start, d);
            net.addPostcondition(d, ph.get(j));
            d.addFeature(StochasticTransitionFeature.newDeterministicInstance(
                    BigDecimal.ZERO, MarkingExpr.from(num(w.get(j - 1)), net)));
            d.addFeature(new Priority(0));
        }

        // catena delle fasi: con un solo token il rate marking-dependent si riduce a rate_i
        for (int i = 1; i <= phases; i++) {
            Transition t = net.addTransition("t" + i);
            net.addPrecondition(ph.get(i), t);
            net.addPostcondition(t, i < phases ? ph.get(i + 1) : completed);
            t.addFeature(StochasticTransitionFeature.newExponentialInstance(
                    BigDecimal.valueOf(phaseRates.get(i - 1))));
        }

        // limite iniziale: 10x il tempo medio di completamento, poi raddoppia se non basta
        double meanCompletion = phaseRates.stream().mapToDouble(r -> 1.0 / r).sum();
        double limit = Math.max(step, 10 * meanCompletion);

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

    public void buildOLD(PetriNet net) {
        Place Healthy = net.addPlace("Healthy");
        Place Aging1 = net.addPlace("Aging1");
        Place Aging2 = net.addPlace("Aging2");
        Place Aging3 = net.addPlace("Aging3");
        Place ToExecute = net.addPlace("ToExecute");

        Transition toAging1 = net.addTransition("toAging1");
        Transition toAging2 = net.addTransition("toAging2");
        Transition toAging3 = net.addTransition("toAging3");
        Transition toHealthy = net.addTransition("toHealthy");


        toAging1.addFeature(new EnablingFunction("AgingLevel==1"));
        toAging1.addFeature(StochasticTransitionFeature.newDeterministicInstance(new BigDecimal("0"), MarkingExpr.from("1", net)));
        toAging1.addFeature(new Priority(0));
        toAging2.addFeature(new EnablingFunction("AgingLevel==2"));
        toAging2.addFeature(StochasticTransitionFeature.newDeterministicInstance(new BigDecimal("0"), MarkingExpr.from("1", net)));
        toAging2.addFeature(new Priority(0));
        toAging3.addFeature(new EnablingFunction("AgingLevel==3"));
        toAging3.addFeature(StochasticTransitionFeature.newDeterministicInstance(new BigDecimal("0"), MarkingExpr.from("1", net)));
        toAging3.addFeature(new Priority(0));
        toHealthy.addFeature(new EnablingFunction("AgingLevel==0"));
        toHealthy.addFeature(StochasticTransitionFeature.newDeterministicInstance(new BigDecimal("0"), MarkingExpr.from("1", net)));
        toHealthy.addFeature(new Priority(0));

        Place Ph1 = net.addPlace("Ph1");
        Place Ph2 = net.addPlace("Ph2");
        Place Ph3 = net.addPlace("Ph3");
        Place Ph4 = net.addPlace("Ph4");
        Place Ph5 = net.addPlace("Ph5");

        Transition t1 = net.addTransition("t1");
        Transition t2 = net.addTransition("t2");
        Transition t3 = net.addTransition("t3");
        Transition t4 = net.addTransition("t4");
        Transition t5 = net.addTransition("t5");

        net.addPostcondition(t1, Ph2);
        net.addPrecondition(Ph1, t1);

        net.addPostcondition(t2, Ph3);
        net.addPrecondition(Ph2, t2);


        net.addPostcondition(t4, Ph5);
        net.addPrecondition(Ph4, t4);

        net.addPostcondition(t3, Ph4);
        net.addPrecondition(Ph3, t3);

        net.addPrecondition(Ph5, t5);

        t1.addFeature(StochasticTransitionFeature.newExponentialInstance(new BigDecimal("1"), MarkingExpr.from("(Ph1*1)/(Ph1+Ph2+Ph3+Ph4+Ph5)", net)));
        t2.addFeature(StochasticTransitionFeature.newExponentialInstance(new BigDecimal("1"), MarkingExpr.from("(Ph2*2)/(Ph1+Ph2+Ph3+Ph4+Ph5)", net)));
        t3.addFeature(StochasticTransitionFeature.newExponentialInstance(new BigDecimal("1"), MarkingExpr.from("(Ph3*3)/(Ph1+Ph2+Ph3+Ph4+Ph5)", net)));
        t4.addFeature(StochasticTransitionFeature.newExponentialInstance(new BigDecimal("1"), MarkingExpr.from("(Ph4*4)/(Ph1+Ph2+Ph3+Ph4+Ph5)", net)));
        t5.addFeature(StochasticTransitionFeature.newExponentialInstance(new BigDecimal("1"), MarkingExpr.from("(Ph5*5)/(Ph1+Ph2+Ph3+Ph4+Ph5)", net)));

        Transition t25 = net.addTransition("t25");
        Transition t26 = net.addTransition("t26");
        Transition t27 = net.addTransition("t27");
        Transition t28 = net.addTransition("t28");
        Transition t29 = net.addTransition("t29");
        Transition t30 = net.addTransition("t30");
        Transition t31 = net.addTransition("t31");
        Transition t32 = net.addTransition("t32");
        Transition t33 = net.addTransition("t33");
        Transition t34 = net.addTransition("t34");
        Transition t35 = net.addTransition("t35");
        Transition t36 = net.addTransition("t36");
        Transition t37 = net.addTransition("t37");
        Transition t38 = net.addTransition("t38");
        Transition t39 = net.addTransition("t39");
        Transition t40 = net.addTransition("t40");
        Transition t41 = net.addTransition("t41");
        Transition t42 = net.addTransition("t42");
        Transition t43 = net.addTransition("t43");
        Transition t44 = net.addTransition("t44");

        net.addPrecondition(ToExecute, toAging1);
        net.addPostcondition(toAging1, Aging1);
        toAging1.addFeature(new EnablingFunction("AgingLevel==1"));
        toAging1.addFeature(StochasticTransitionFeature.newDeterministicInstance(new BigDecimal("0"), MarkingExpr.from("1", net)));
        toAging1.addFeature(new Priority(0));

        net.addPrecondition(Aging1, t38);
        net.addPrecondition(Aging1, t35);
        net.addPrecondition(Aging1, t36);
        net.addPrecondition(Aging1, t37);
        net.addPrecondition(Aging1, t39);

        net.addPostcondition(t38, Ph3);
        net.addPostcondition(t35, Ph5);
        net.addPostcondition(t36, Ph1);
        net.addPostcondition(t37, Ph4);

        net.addPostcondition(t39, Ph2);

        t39.addFeature(StochasticTransitionFeature.newDeterministicInstance(new BigDecimal("0"), MarkingExpr.from("W22", net)));
        t39.addFeature(new Priority(0));
        t35.addFeature(StochasticTransitionFeature.newDeterministicInstance(new BigDecimal("0"), MarkingExpr.from("W25", net)));
        t35.addFeature(new Priority(0));
        t36.addFeature(StochasticTransitionFeature.newDeterministicInstance(new BigDecimal("0"), MarkingExpr.from("W21", net)));
        t36.addFeature(new Priority(0));
        t37.addFeature(StochasticTransitionFeature.newDeterministicInstance(new BigDecimal("0"), MarkingExpr.from("W24", net)));
        t37.addFeature(new Priority(0));
        t38.addFeature(StochasticTransitionFeature.newDeterministicInstance(new BigDecimal("0"), MarkingExpr.from("W23", net)));
        t38.addFeature(new Priority(0));
    }
}
