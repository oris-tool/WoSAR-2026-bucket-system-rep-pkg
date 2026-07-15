package it.unifi.dinfo.stlab;

import org.oristool.models.stpn.RewardRate;
import org.oristool.models.stpn.TransientSolution;
import org.oristool.petrinet.Marking;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class WosarExperiment {

    private static final int MAX_AGING_LEVEL = 3;
    private static final int BPH_PHASES = 5;
    private static final int MAX_CONCURRENCY = 4;

    // Bucket system configurations to sweep
    private static final int[] BUCKETS_NUMBERS = {1, 2, 3, 4};
    private static final int[] BUCKET_DEPTHS = {1, 2, 3, 4};

    // Transient analysis parameters: the horizon is adaptive, doubling from
    // INITIAL_TIME_LIMIT until P(Rejuvenation==1) >= 1-EPSILON at the last
    // time point (or MAX_TIME_LIMIT is reached, to avoid rare-event blowups)
    private static final double INITIAL_TIME_LIMIT = 50;
    private static final double MAX_TIME_LIMIT = 2000;
    private static final double EPSILON = 0.01;
    private static final double TIME_STEP = 0.1;
    private static final String STOP_CONDITION = "Rejuvenation==1";

    private static final Path OUTPUT_DIR = Paths.get("results");

    private static final List<WorkloadProfile> WORKLOADS = List.of(
            standard(),
            spiky(),
            mostlyMid()
    );


    private record BucketConfig(int bucketsNumber, int bucketDepth) {
        int capacity() { return bucketsNumber * bucketDepth; }
    }

    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT_DIR);

        // capacity-ascending order, so each config can warm-start its time
        // limit from the previous (smaller) one
        List<BucketConfig> configs = new ArrayList<>();
        for (int n : BUCKETS_NUMBERS)
            for (int d : BUCKET_DEPTHS)
                configs.add(new BucketConfig(n, d));
        configs.sort(Comparator.comparingInt(BucketConfig::capacity));

        int total = WORKLOADS.size() * configs.size();
        int current = 0;

        try (BufferedWriter index = Files.newBufferedWriter(OUTPUT_DIR.resolve("experiments_index.csv"))) {
            index.write("profile,bucketsNumber,bucketDepth,timeLimit,rejuvenationMass,rewardsCsv,mappingCsv,analysisSeconds");
            index.newLine();

            for (WorkloadProfile profile : WORKLOADS) {
                double limit = INITIAL_TIME_LIMIT;

                for (BucketConfig cfg : configs) {
                    current++;
                    String label = profile.getName() + "_N" + cfg.bucketsNumber() + "_D" + cfg.bucketDepth();
                    System.out.printf("[%d/%d] %s%n", current, total, label);

                    long start = System.nanoTime();

                    // fresh BPH strategy per model: build(net) is stateful
                    MicroserviceModel model = model(bph(), profile, cfg.bucketDepth(), cfg.bucketsNumber());

                    TransientSolution<Marking, RewardRate> rew;
                    double rejuvenationMass;
                    while (true) {
                        model.transientAnalysis(limit, TIME_STEP, STOP_CONDITION);
                        rew = model.computeRewardSolution(model.getTotalReward());
                        double[][][] sol = rew.getSolution();
                        rejuvenationMass = sol[sol.length - 1][0][0]; // TimeToRejuvenation column
                        if (rejuvenationMass >= 1 - EPSILON || limit >= MAX_TIME_LIMIT)
                            break;
                        limit *= 2;
                        System.out.printf(Locale.US,
                                "    mass=%.4f < %.4f, doubling time limit to %.0f%n",
                                rejuvenationMass, 1 - EPSILON, limit);
                    }
                    if (rejuvenationMass < 1 - EPSILON)
                        System.out.printf(Locale.US,
                                "    WARNING: mass=%.4f at MAX_TIME_LIMIT=%.0f, truncated horizon%n",
                                rejuvenationMass, MAX_TIME_LIMIT);

                    String rewardsCsv = OUTPUT_DIR.resolve(label + "_rewards.csv").toString();
                    String mappingCsv = OUTPUT_DIR.resolve(label + "_rewards_mapping.csv").toString();
                    Utils.exportNamedRewardsCsv(rew, TIME_STEP, model.getNamedRewards(),
                            rewardsCsv, mappingCsv);

                    double seconds = (System.nanoTime() - start) / 1e9;
                    index.write(String.format(Locale.US, "%s,%d,%d,%.0f,%.6f,%s,%s,%.2f",
                            profile.getName(), cfg.bucketsNumber(), cfg.bucketDepth(),
                            limit, rejuvenationMass, rewardsCsv, mappingCsv, seconds));
                    index.newLine();
                    index.flush();

                    System.out.printf(Locale.US, "    saved %s (limit=%.0f, %.1fs)%n",
                            rewardsCsv, limit, seconds);
                }
            }
        }

        System.out.println("All experiments completed. Index: "
                + OUTPUT_DIR.resolve("experiments_index.csv"));
    }


    public static RequestProcessingRepresentationStrategy bph() {
        BphProcessingRepresentationStrategy bph = new BphProcessingRepresentationStrategy(BPH_PHASES, MAX_AGING_LEVEL);
        bph.phaseRates(1,2,3,4,5)
                .weights(0, 1,2,3,4,5)
                .weights(1,1,2,4,4,4)
                .weights(2, 3,4,3,3,2)
                .weights(3,5,4,3,2,1);
        return bph;
    }

    public static MicroserviceModel model(RequestProcessingRepresentationStrategy requestProcessing, WorkloadProfile profile, int bucketDepth, int bucketsNumber ) {
        MicroserviceModel.Builder builder = baseModelBuilder();
        builder.workLoadProfile(profile).initialWorkloadLevel(profile.getInitialWorkloadLevel().name());
        builder.processingStrategy(requestProcessing);
        builder.bucketDepth(bucketDepth).bucketsNumber(bucketsNumber);
        return builder.build();
    }

    // Metodo che imposta solo le costanti e le basi
    public static MicroserviceModel.Builder baseModelBuilder() {
        return MicroserviceModel.builder()
                .maxConcurrency(MAX_CONCURRENCY)
                .maxAgingLevel(MAX_AGING_LEVEL);
    }

    public static WorkloadProfile standard() {
        WorkloadProfile.Builder pb = new WorkloadProfile.Builder();
        WorkloadLevel low  = pb.level("Low",  1.0);
        WorkloadLevel mid  = pb.level("Mid",  3.0);
        WorkloadLevel high = pb.level("High", 6.0);
        WorkloadProfile p = pb
                .transition(low,  mid,  0.01)
                .transition(mid,  high, 0.1)
                .transition(high, mid,  0.01)
                .transition(mid,  low,  0.1)
                .initialWorkloadLevel(mid)
                .build("Standard");
        return p;
    }

    // 2) Solo Mid e High, con picchi improvvisi e brevi verso High
    public static WorkloadProfile spiky() {
        WorkloadProfile.Builder pb = new WorkloadProfile.Builder();
        WorkloadLevel mid  = pb.level("Mid",  3.0);
        WorkloadLevel high = pb.level("High", 6.0);
        WorkloadProfile p = pb
                .transition(mid,  high, 0.03)  // il picco parte di rado -> "improvviso"
                .transition(high, mid,  0.6)   // ...e rientra subito -> picco breve
                .initialWorkloadLevel(mid)
                .build("Spiky");
        return p;
    }

    // 3) Di norma Mid, con brevi escursioni verso High o Low
    public static WorkloadProfile mostlyMid() {
        WorkloadProfile.Builder pb = new WorkloadProfile.Builder();
        WorkloadLevel low  = pb.level("Low",  1.0);
        WorkloadLevel mid  = pb.level("Mid",  3.0);
        WorkloadLevel high = pb.level("High", 6.0);
        WorkloadProfile p = pb
                .transition(mid,  high, 0.05)  // ogni tanto sale
                .transition(mid,  low,  0.05)  // ogni tanto scende
                .transition(high, mid,  0.5)   // escursione breve, rientra in fretta
                .transition(low,  mid,  0.5)   // escursione breve, rientra in fretta
                .initialWorkloadLevel(mid)
                .build("MostlyMid");
        return p;
    }

}
