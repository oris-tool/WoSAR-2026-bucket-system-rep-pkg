package it.unifi.dinfo.stlab.simulation;

import it.unifi.dinfo.stlab.BphProcessingRepresentationStrategy;
import it.unifi.dinfo.stlab.MicroserviceModel;
import it.unifi.dinfo.stlab.MicroserviceModel.ResponseTimeConfiguration;
import it.unifi.dinfo.stlab.WorkloadProfile;

import org.oristool.analyzer.log.NoOpLogger;
import org.oristool.models.pn.Priority;
import org.oristool.models.stpn.MarkingExpr;
import org.oristool.models.stpn.trees.StochasticTransitionFeature;
import org.oristool.petrinet.Marking;
import org.oristool.petrinet.PetriNet;
import org.oristool.petrinet.Place;
import org.oristool.petrinet.Transition;
import org.oristool.simulator.Sequencer;
import org.oristool.simulator.stpn.STPNSimulatorComponentsFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Validates via simulation the violation probabilities installed in the
 * thresholdExceeded/thresholdNotExceeded probability switch of the
 * microservice model.
 *
 * <p>For each aging level and each concurrency degree c, the BPH of a single
 * request is simulated in isolation (one request at a time, so each completion
 * is unambiguous). The effect of c concurrent requests under processor sharing
 * is reproduced by scaling the phase rates by hand: each phase i runs at
 * rate_i / c instead of the marking-dependent (Ph_i*rate_i)/(Ph1+...+Phn) of
 * the full model. Completion times are collected with a
 * {@link CompletionTimeCollector} attached to the Sirio {@link Sequencer}
 * (FIRING_EXECUTED events of the last BPH transition).
 *
 * <p>The empirical violation probabilities P(T &gt; thresholdMean +
 * bucketLevel*thresholdStdDev) are then compared with the analytic values of
 * the thresholdViolationProbabilityMap computed by the model builder for the
 * same (agingLevel, bucketLevel, concurrency) configuration.
 *
 * <p>Note: constant exponential rates (rate_i/c hard-coded in the rate) are
 * used on purpose instead of a marking-dependent clockRate: the Sequencer
 * accumulates currentRunElapsedTime with the raw time-to-fire, without
 * dividing it by the clock rate, so elapsed times are correct only when the
 * clock rate is 1.
 */
public class BphThresholdValidationExperiment {

    // Same configuration as WosarExperiment
    private static final int MAX_AGING_LEVEL = 3;
    private static final int BPH_PHASES = 5;
    private static final int MAX_CONCURRENCY = 4;
    private static final int BUCKETS_NUMBER = 4;
    private static final int BUCKET_DEPTH = 1;  // map values do not depend on the depth

    private static final double[] PHASE_RATES = {1, 2, 3, 4, 5};
    private static final double[][] AGING_WEIGHTS = {
            {1, 2, 3, 4, 5},   // aging level 0
            {1, 2, 4, 4, 4},   // aging level 1
            {3, 4, 3, 3, 2},   // aging level 2
            {5, 4, 3, 2, 1},   // aging level 3
    };

    private static final int RUNS = 100_000;
    private static final double CONFIDENCE_Z = 1.96;  // 95% CI on the empirical probability

    private static final Path OUTPUT_DIR = Paths.get("results", "simulation");

    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT_DIR);

        System.out.println("Building reference model (analytic thresholdViolationProbabilityMap)...");
        MicroserviceModel model = referenceModel();
        Map<ResponseTimeConfiguration, Double> analyticMap = model.getThresholdViolationProbabilityMap();
        double thresholdMean = model.getThresholdMean();
        double thresholdStdDev = model.getThresholdStdDev();
        System.out.printf(Locale.US, "thresholdMean=%.6f thresholdStdDev=%.6f%n%n",
                thresholdMean, thresholdStdDev);

        Path validationCsv = OUTPUT_DIR.resolve("threshold_validation.csv");
        try (BufferedWriter out = Files.newBufferedWriter(validationCsv)) {
            out.write("agingLevel,concurrency,bucketLevel,threshold,samples,"
                    + "empiricalViolationProb,analyticViolationProb,absError,ciHalfWidth,withinCI,"
                    + "relError,relCiHalfWidth");
            out.newLine();

            System.out.printf("%-6s %-5s %-7s %-10s %-10s %-10s %-9s %-9s %-9s %-9s %s%n",
                    "aging", "conc", "bucket", "threshold", "empirical", "analytic",
                    "absErr", "ci95", "relErr", "relCi95", "ok");

            for (int agingLvl = 0; agingLvl <= MAX_AGING_LEVEL; agingLvl++) {
                for (int concurrency = 1; concurrency <= MAX_CONCURRENCY; concurrency++) {

                    List<Double> times = simulateCompletionTimes(agingLvl, concurrency);
                    exportRawTimes(times, agingLvl, concurrency);

                    for (int bucketLvl = 0; bucketLvl <= BUCKETS_NUMBER; bucketLvl++) {
                        double threshold = thresholdMean + bucketLvl * thresholdStdDev;
                        double empirical = violationFraction(times, threshold);
                        double analytic = analyticMap.get(
                                new ResponseTimeConfiguration(agingLvl, bucketLvl, concurrency));

                        double absError = Math.abs(empirical - analytic);
                        double ciHalfWidth = CONFIDENCE_Z
                                * Math.sqrt(empirical * (1 - empirical) / times.size());
                        boolean withinCI = absError <= ciHalfWidth;

                        double relError = analytic > 0 ? absError / analytic : Double.NaN;
                        double relCiHalfWidth = analytic > 0 ? ciHalfWidth / analytic : Double.NaN;

                        out.write(String.format(Locale.US,
                                "%d,%d,%d,%.6f,%d,%.6f,%.6f,%.6f,%.6f,%b,%.6f,%.6f",
                                agingLvl, concurrency, bucketLvl, threshold, times.size(),
                                empirical, analytic, absError, ciHalfWidth, withinCI,
                                relError, relCiHalfWidth));
                        out.newLine();

                        System.out.printf(Locale.US,
                                "%-6d %-5d %-7d %-10.4f %-10.6f %-10.6f %-9.6f %-9.6f %-9.6f %-9.6f %s%n",
                                agingLvl, concurrency, bucketLvl, threshold, empirical, analytic,
                                absError, ciHalfWidth, relError, relCiHalfWidth,
                                withinCI ? "OK" : "**MISMATCH**");
                    }
                    out.flush();
                }
            }
        }

        System.out.println("\nValidation results: " + validationCsv);
    }

    /**
     * Full model built only to obtain the analytic map and the thresholds
     * actually installed in the probability switch. The workload profile is
     * irrelevant to the map, so a flat single-level profile is used.
     */
    private static MicroserviceModel referenceModel() {
        BphProcessingRepresentationStrategy bph =
                new BphProcessingRepresentationStrategy(BPH_PHASES, MAX_AGING_LEVEL);
        bph.phaseRates(PHASE_RATES);
        for (int agingLvl = 0; agingLvl <= MAX_AGING_LEVEL; agingLvl++)
            bph.weights(agingLvl, AGING_WEIGHTS[agingLvl]);

        WorkloadProfile.Builder pb = new WorkloadProfile.Builder();
        pb.initialWorkloadLevel(pb.level("Flat", 1.0));
        WorkloadProfile profile = pb.build("Flat");

        return MicroserviceModel.builder()
                .maxConcurrency(MAX_CONCURRENCY)
                .maxAgingLevel(MAX_AGING_LEVEL)
                .processingStrategy(bph)
                .bucketDepth(BUCKET_DEPTH)
                .bucketsNumber(BUCKETS_NUMBER)
                .workLoadProfile(profile)
                .initialWorkloadLevel("Flat")
                .build();
    }

    /**
     * Simulates {@link #RUNS} independent single-request completions at the
     * given aging level, with phase rates slowed down by the given concurrency
     * degree, and returns the completion times.
     */
    private static List<Double> simulateCompletionTimes(int agingLevel, int concurrency) {
        PetriNet net = new PetriNet();
        Marking marking = new Marking();

        Place start = net.addPlace("Start");
        Place completed = net.addPlace("Completed");
        marking.setTokens(start, 1);

        Place[] phases = new Place[BPH_PHASES + 1];  // 1-indexed
        for (int i = 1; i <= BPH_PHASES; i++)
            phases[i] = net.addPlace("Ph" + i);

        // weighted immediate dispatch to the initial phase, as in the full model
        double[] weights = AGING_WEIGHTS[agingLevel];
        for (int j = 1; j <= BPH_PHASES; j++) {
            Transition dispatch = net.addTransition("toPh" + j);
            net.addPrecondition(start, dispatch);
            net.addPostcondition(dispatch, phases[j]);
            dispatch.addFeature(StochasticTransitionFeature.newDeterministicInstance(
                    BigDecimal.ZERO,
                    MarkingExpr.from(BigDecimal.valueOf(weights[j - 1]).toPlainString(), net)));
            dispatch.addFeature(new Priority(0));
        }

        // phase chain at rate_i / concurrency; the last transition completes the request
        for (int i = 1; i <= BPH_PHASES; i++) {
            Transition t = net.addTransition("t" + i);
            net.addPrecondition(phases[i], t);
            net.addPostcondition(t, i < BPH_PHASES ? phases[i + 1] : completed);
            t.addFeature(StochasticTransitionFeature.newExponentialInstance(
                    BigDecimal.valueOf(PHASE_RATES[i - 1] / concurrency)));
        }

        Sequencer sequencer = new Sequencer(net, marking,
                new STPNSimulatorComponentsFactory(), NoOpLogger.INSTANCE);
        CompletionTimeCollector collector =
                new CompletionTimeCollector(sequencer, "t" + BPH_PHASES, RUNS);
        sequencer.simulate();

        return collector.getCompletionTimes();
    }

    private static double violationFraction(List<Double> times, double threshold) {
        long violations = times.stream().filter(t -> t > threshold).count();
        return (double) violations / times.size();
    }

    private static void exportRawTimes(List<Double> times, int agingLevel, int concurrency)
            throws IOException {
        Path csv = OUTPUT_DIR.resolve(String.format(
                "completion_times_aging%d_conc%d.csv", agingLevel, concurrency));
        try (BufferedWriter out = Files.newBufferedWriter(csv)) {
            out.write("completionTime");
            out.newLine();
            for (double t : times) {
                out.write(Double.toString(t));
                out.newLine();
            }
        }
    }
}
