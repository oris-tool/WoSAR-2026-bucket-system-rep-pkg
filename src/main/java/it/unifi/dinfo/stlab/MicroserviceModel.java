package it.unifi.dinfo.stlab;

import java.math.BigDecimal;
import java.util.*;

import org.oristool.models.gspn.GSPNTransient;
import org.oristool.models.pn.PostUpdater;
import org.oristool.models.pn.Priority;
import org.oristool.models.stpn.MarkingExpr;
import org.oristool.models.stpn.RewardRate;
import org.oristool.models.stpn.TransientSolution;
import org.oristool.models.stpn.trees.StochasticTransitionFeature;
import org.oristool.petrinet.*;
import org.oristool.util.Pair;

public class MicroserviceModel {

    public record ResponseTimeConfiguration(int agingLevel, int bucketLevel, int concurrency){};

    private PetriNet net;
    private Marking marking;

    private WorkloadProfile profile;
    private int maxAgingLevel;

    private int bucketDepth;
    private int bucketsNumber;


    private double thresholdMean;
    private double thresholdStdDev;

    private String performanceViolationProbabilityExpr;

    private static final double DISCRETIZATION_STEP = 0.1;



    private double[][] transientProbs;
    private Map<Marking, Integer> transientMarkingMapping;
    private double timeStep;
    private Marking initialMarking;


    private RequestProcessingRepresentationStrategy processingRepresentation;

    private MicroserviceModel(Builder builder) {
        this.net = builder.net;
        this.marking = builder.marking;
        this.profile = builder.profile;
        this.maxAgingLevel = builder.maxAgingLevel;
        this.bucketDepth = builder.bucketDepth;
        this.bucketsNumber = builder.bucketsNumber;
        this.processingRepresentation = builder.processingRepresentation;
        this.thresholdMean = builder.thresholdMean;
        this.thresholdStdDev = builder.thresholdStdDev;
        this.performanceViolationProbabilityExpr = builder.performanceViolationLaw;
    }

    public static Builder  builder() {
        return new Builder();
    }


    public void transientAnalysis(double limit, double step) {
        transientAnalysis(limit, step, null);
    }

    public void transientAnalysis(double limit, double step, String stopOnCondition) {
        GSPNTransient.Builder builder = GSPNTransient.builder().timePoints(0.0, limit, step);
        if (stopOnCondition != null) {
            builder.stopOn(MarkingCondition.fromString(stopOnCondition));
        }

        Pair<Map<Marking, Integer>, double[][]> result =builder.build().compute(net, marking);

        this.transientMarkingMapping = result.first();
        this.transientProbs          = result.second();
        this.timeStep                = step;
        this.initialMarking          = marking;
    }

    public TransientSolution<Marking, RewardRate> computeRewardSolution(String rewardsAsString){
        return computeRewardSolution(rewardsAsString, false);
    }

    public TransientSolution<Marking, RewardRate> computeRewardSolution(
            String rewardsAsString, boolean cumulative) {

        TransientSolution<Marking, Marking> markingSolution =
                TransientSolution.fromArray(this.transientProbs, this.timeStep,
                        this.transientMarkingMapping, this.initialMarking);

        return TransientSolution.computeRewards(cumulative, markingSolution,
                parseRewards(rewardsAsString));
    }

    private static RewardRate[] parseRewards(String rewardsAsString) {
        String[] exprs = rewardsAsString.split(";");
        RewardRate[] rewards = new RewardRate[exprs.length];
        for (int r = 0; r < exprs.length; r++)
            rewards[r] = cached(exprs[r].trim());
        return rewards;
    }

    /**
     * Optimization for complex reward (single reward, i.e., the one between ";")
     * Wraps a reward expression caching its value per marking: Lello evaluates
     * the whole If-chain eagerly (plus reflective dispatch and a variables()
     * scan) on every call, so evaluating it T times per marking dominates the
     * reward computation. Time-dependent expressions (variable "t") are not
     * cached.
     */
    private static RewardRate cached(String expression) {
        RewardRate inner = RewardRate.fromString(expression);
        if (expression.matches(".*(?<![A-Za-z0-9_])t(?![A-Za-z0-9_]).*")) // time dependent expression
            return inner;

        Map<Marking, Double> cache = new HashMap<>();
        return new RewardRate() {
            @Override
            public double evaluate(double time, Marking m) {
                Double v = cache.get(m);
                if (v == null) {
                    v = inner.evaluate(0.0, m);
                    cache.put(m, v);
                }
                return v;
            }

            @Override
            public String toString() {
                return expression;
            }
        };
    }

    public String getTimeToRejuvenationReward(){
        return "Rejuvenation";
    }

    public String getThroughputReward(){
        return processingRepresentation.getThroughputReward();
    }

    public String getRejuvenationDueToLoadReward(){
        StringBuilder sb = new StringBuilder();
        sb.append("Rejuvenation==1&&AgingLevel!=").append(maxAgingLevel);
        for (int lvl = 0; lvl < maxAgingLevel; lvl++)
            sb.append(";Rejuvenation==1&&AgingLevel==").append(lvl);
        return sb.toString();
    }

    public String getRejuvenationDueToAging(){
        return "Rejuvenation==1&&AgingLevel==" + maxAgingLevel;
    }

    public String getViolationRateReward(){
        return getThroughputReward() + "*" + performanceViolationProbabilityExpr;
    }


    /**
     * Human-readable name -> reward expression, one entry per column
     * (getRejuvenationDueToLoadReward expands to maxAgingLevel+1 columns).
     * Iteration order matches the column order of getTotalReward.
     */
    public LinkedHashMap<String, String> getNamedRewards(){
        LinkedHashMap<String, String> named = new LinkedHashMap<>();
        named.put("TimeToRejuvenation", getTimeToRejuvenationReward());
        named.put("Throughput", getThroughputReward());
        String[] loadRewards = getRejuvenationDueToLoadReward().split(";");
        named.put("RejuvenationDueToLoad", loadRewards[0]);
        for (int lvl = 0; lvl < maxAgingLevel; lvl++)
            named.put("RejuvenationDueToLoad_AgingLevel" + lvl, loadRewards[lvl + 1]);
        named.put("RejuvenationDueToAging", getRejuvenationDueToAging());
        named.put("ViolationRate", getViolationRateReward());
        return named;
    }

    public String getTotalReward(){
        return String.join(";", getNamedRewards().values());
    }


    public Map<String, double[]> getTransientFromRewards(String rewardsAsString) {
        String[] exprs = rewardsAsString.split(";");
        RewardRate[] rewards = parseRewards(rewardsAsString);

        int numTimePoints = this.transientProbs.length;
        double[][] values = new double[exprs.length][numTimePoints];

        for (int t = 0; t < numTimePoints; t++) {
            double currentTime = t * this.timeStep;
            double[] row = this.transientProbs[t];

            for (Map.Entry<Marking, Integer> entry : this.transientMarkingMapping.entrySet()) {
                Marking m = entry.getKey();
                double prob = row[entry.getValue()];
                if (prob == 0.0) continue;

                for (int r = 0; r < rewards.length; r++) {
                    values[r][t] += prob * rewards[r].evaluate(currentTime, m);
                }
            }
        }

        Map<String, double[]> result = new LinkedHashMap<>();
        for (int r = 0; r < exprs.length; r++) {
            result.put(exprs[r].trim(), values[r]);
        }
        return result;
    }



    /*
    *
    * MicroserviceModelBuilder
    *
     */

    public static final class Builder {

        private final PetriNet net = new PetriNet();
        private final Marking marking = new Marking();


        private RequestProcessingRepresentationStrategy processingRepresentation;

        private WorkloadProfile profile;
        private String initialWorkloadLevel;

        private int maxConcurrency;

        private int maxAgingLevel;
        private String agingLaw;

        private int bucketDepth;
        private int bucketsNumber;

        private String performanceViolationLaw;

        private double thresholdMean;
        private double thresholdStdDev;

        private Map<ResponseTimeConfiguration, Double> thresholdViolationProbabilityMap = new HashMap<>();


        public Builder processingStrategy(RequestProcessingRepresentationStrategy strategy) {
            this.processingRepresentation = strategy;
            return this;
        }

        public Builder maxConcurrency(int maxConcurrency) {
            this.maxConcurrency = maxConcurrency;
            return this;
        }

        public Builder bucketDepth(int bucketDepth) {
            this.bucketDepth = bucketDepth;
            return this;
        }

        public Builder bucketsNumber(int bucketsNumber) {
            this.bucketsNumber = bucketsNumber;
            return this;
        }

        public Builder agingLaw(String law) {
            this.agingLaw = law;
            return this;
        }

        public Builder performanceViolationLaw(String law) {
            this.performanceViolationLaw = law;
            return this;
        }

        public Builder maxAgingLevel(int maxAgingLevel) {
            this.maxAgingLevel = maxAgingLevel;
            return this;
        }

        public Builder thresholdMean(double thresholdMean) {
            this.thresholdMean = thresholdMean;
            return this;
        }

        public Builder thresholdStdDev(double thresholdStdDev) {
            this.thresholdStdDev = thresholdStdDev;
            return this;
        }

        public Builder workLoadProfile(WorkloadProfile profile) {
            this.profile = profile;
            return this;
        }

        public Builder initialWorkloadLevel(String name) {
            this.initialWorkloadLevel = name;
            return this;
        }


        public MicroserviceModel build() {
            buildWorkLoadProfile();
            buildArrivalRate();
            buildAgingModule();
            buildBucketSystemModule();
            processingRepresentation.build(net);
            addPerformanceSwitchWeights();
            buildRejuvenationModule();
            return new MicroserviceModel(this);
        }

        private void buildWorkLoadProfile() {
            // One place for each level
            Map<String, Place> places = new LinkedHashMap<>();
            for (WorkloadLevel lvl : profile.levels())
                places.put(lvl.name(), net.addPlace(lvl.name()));

            // Initial Token
            String start = (this.initialWorkloadLevel != null)
                    ? initialWorkloadLevel
                    : profile.levels().iterator().next().name();
            marking.setTokens(places.get(start), 1);


            // One transition for each level swith declared
            for (WorkloadProfile.Step s : profile.steps()) {
                Transition t = net.addTransition(s.from().name() + "To" + s.to().name());
                net.addPrecondition(places.get(s.from().name()), t);
                net.addPostcondition(t, places.get(s.to().name()));
                t.addFeature(StochasticTransitionFeature.newExponentialInstance(
                        BigDecimal.valueOf(s.rate())));
            }
        }

        private void buildArrivalRate() {
            Transition arrive = net.addTransition("arrive");
            arrive.addFeature(StochasticTransitionFeature.newExponentialInstance(
                    BigDecimal.ONE, MarkingExpr.from(arrivalRateExpr(), net)));
            arrive.addFeature(new EnablingFunction(processingRepresentation.getInProcessPlaces() + "<"+ maxConcurrency+"&&Ready==1"));
        }

        private String arrivalRateExpr() {
            StringBuilder sb = new StringBuilder();
            int closes = 0;
            for (WorkloadLevel lvl : profile.levels()) {
                sb.append("If(").append(lvl.name()).append("==1, ")
                        .append(BigDecimal.valueOf(lvl.rate()).toPlainString()).append(", ");
                closes++;
            }
            sb.append("0").append(")".repeat(closes));
            return sb.toString();
        }

        private void buildAgingModule() {
            Place AgingEffect = net.addPlace("AgingEffect");
            Place AgingLevel = net.addPlace("AgingLevel");
            Transition agingIncrease = net.addTransition("agingIncrease");
            Transition noAgingIncrease = net.addTransition("noAgingIncrease");
            net.addPostcondition(agingIncrease, AgingLevel);
            net.addPrecondition(AgingEffect, agingIncrease);
            net.addPrecondition(AgingEffect, noAgingIncrease);

            if (maxAgingLevel == 0)
                maxAgingLevel = 2; // Default aging degree
            agingIncrease.addFeature(new EnablingFunction("AgingLevel<" + maxAgingLevel));

            if (agingLaw == null) {
                agingLaw = "(AgingLevel+1)/" + (maxAgingLevel + 2) + ".0";
            }
            agingIncrease.addFeature(StochasticTransitionFeature.newDeterministicInstance(new BigDecimal("0"), MarkingExpr.from(agingLaw, net)));
            agingIncrease.addFeature(new Priority(1));

            String complementaryAgingLaw = "1-(" + agingLaw + ")";
//            String complementaryAgingLaw = "1";
            noAgingIncrease.addFeature(StochasticTransitionFeature.newDeterministicInstance(new BigDecimal("0"), MarkingExpr.from(complementaryAgingLaw, net)));
            noAgingIncrease.addFeature(new Priority(1));
        }

        private void buildBucketSystemModule() {
            Place Executed = net.addPlace("Executed");
            Place OnTime = net.addPlace("OnTime");
            Place Late = net.addPlace("Late");


            Place BucketSystem = net.addPlace("BucketSystem");

            Transition thresholdExceeded = net.addTransition("thresholdExceeded");
            Transition thresholdNotExceeded = net.addTransition("thresholdNotExceeded");

            net.addPrecondition(Executed, thresholdNotExceeded);
            net.addPostcondition(thresholdNotExceeded, OnTime);

            net.addPrecondition(Executed, thresholdExceeded);
            net.addPostcondition(thresholdExceeded, Late);
            // The weights of the two thresholds are assigned in addPerformanceSwitchWeights(),
            // after the strategy build: the law refers to places Ph1..Phn
            // and MarkingExpr.from validates the place names during parsing

            Transition decreaseBucketSystem = net.addTransition("decreaseBucketSystem");

            net.addPrecondition(BucketSystem, decreaseBucketSystem);
            net.addPrecondition(OnTime, decreaseBucketSystem);

            decreaseBucketSystem.addFeature(StochasticTransitionFeature.newDeterministicInstance(new BigDecimal("0"), MarkingExpr.from("1", net)));
            decreaseBucketSystem.addFeature(new Priority(0));

            Transition BucketSystemEmpty = net.addTransition("BucketSystemEmpty");
            net.addInhibitorArc(BucketSystem, BucketSystemEmpty);
            net.addPrecondition(OnTime, BucketSystemEmpty);
            BucketSystemEmpty.addFeature(StochasticTransitionFeature.newDeterministicInstance(new BigDecimal("0"), MarkingExpr.from("1", net)));
            BucketSystemEmpty.addFeature(new Priority(0));

            Transition increaseBucketSystem = net.addTransition("increaseBucketSystem");
            net.addPrecondition(Late, increaseBucketSystem);
            net.addPostcondition(increaseBucketSystem, BucketSystem);
            increaseBucketSystem.addFeature(new EnablingFunction("BucketSystem<(" + bucketDepth * bucketsNumber + ")"));
            increaseBucketSystem.addFeature(StochasticTransitionFeature.newDeterministicInstance(new BigDecimal("0"), MarkingExpr.from("1", net)));
            increaseBucketSystem.addFeature(new Priority(0));

            Transition BucketSystemFull = net.addTransition("BucketSystemFull");
            net.addPrecondition(Late, BucketSystemFull);
            BucketSystemFull.addFeature(new EnablingFunction("BucketSystem==" + bucketDepth * bucketsNumber));
            BucketSystemFull.addFeature(StochasticTransitionFeature.newDeterministicInstance(new BigDecimal("0"), MarkingExpr.from("1", net)));
            BucketSystemFull.addFeature(new Priority(0));
        }

        private void addPerformanceSwitchWeights() {
            Transition thresholdNotExceeded = net.getTransition("thresholdNotExceeded");
            Transition thresholdExceeded = net.getTransition("thresholdExceeded");

            if (performanceViolationLaw == null) {
                calculateThresholdViolationProbabilityMap();
                performanceViolationLaw = thresholdViolationExpr();
                System.out.println(performanceViolationLaw);
            }
            String conformanceLaw = "1-(" + performanceViolationLaw + ")";

            thresholdNotExceeded.addFeature(StochasticTransitionFeature.newDeterministicInstance(new BigDecimal("0"), MarkingExpr.from(conformanceLaw, net)));
            thresholdNotExceeded.addFeature(new Priority(2));

            thresholdExceeded.addFeature(StochasticTransitionFeature.newDeterministicInstance(new BigDecimal("0"), MarkingExpr.from(performanceViolationLaw, net)));
            thresholdExceeded.addFeature(new Priority(2));
        }

        private void calculateThresholdViolationProbabilityMap(){
            Map<Integer, double[]> completionDistributionForEachAgingLvl = processingRepresentation.getCompletionDistributionForEachAgingLvl(DISCRETIZATION_STEP);
            if(thresholdMean == 0 || thresholdStdDev == 0){
                updateThresholdValuesFromHealthyCDF(completionDistributionForEachAgingLvl.get(0), DISCRETIZATION_STEP);
            }
            for(int agingLvl = 0; agingLvl <= maxAgingLevel; agingLvl++){
                for(int bucketLvl = 0; bucketLvl <= bucketsNumber; bucketLvl++){
                    for(int concurrency = 1; concurrency <= maxConcurrency; concurrency++){
                        double[] completionTimeCDF = completionDistributionForEachAgingLvl.get(agingLvl);
                        double violationProbability = getViolationProbability(completionTimeCDF, DISCRETIZATION_STEP * concurrency, this.thresholdMean + bucketLvl * this.thresholdStdDev);
                        this.thresholdViolationProbabilityMap.put(new ResponseTimeConfiguration(agingLvl,bucketLvl,concurrency), violationProbability);
                    }
                }
            }
        }

        private void updateThresholdValuesFromHealthyCDF(double[] cdf, double step){
            if (cdf == null || cdf.length == 0)
                throw new IllegalArgumentException("Invalid CDF");
            if (step <= 0)
                throw new IllegalArgumentException("invalid timestep");
            double mean = 0.0;
            double meanOfSquares = 0.0;
            double prev = 0.0;

            for (int i = 0; i < cdf.length; i++) {
                double pmf = cdf[i] - prev;
                prev = cdf[i];
                double actualTime = i * step;
                mean += actualTime * pmf;
                meanOfSquares += actualTime * actualTime * pmf;
            }

            double variance = meanOfSquares - mean * mean;
            if (variance < 0) variance = 0;
            this.thresholdMean = mean;
            this.thresholdStdDev = Math.sqrt(variance);
        }

        private double getViolationProbability(double[] cdf, double timestep, double threshold){
            if (cdf == null || cdf.length == 0)
                throw new IllegalArgumentException("invalid CDF");
            if (timestep <= 0)
                throw new IllegalArgumentException("invalid timestep");

            if (threshold <= 0)
                return 1.0;

            double pos = threshold / timestep;
            int idx = (int) Math.floor(pos);

            // Just a corner case
            if (idx >= cdf.length - 1) {
                return 1.0 - cdf[cdf.length - 1];
            }

            // Linear interpolation between position idx and idx+1
            double frac = pos - idx;
            double cdfInterp = cdf[idx] + frac * (cdf[idx + 1] - cdf[idx]);

            return 1.0 - cdfInterp;
        }

        /**
         * P(violation) as a chain of Ifs over the operational conditions observable
         * in the marking: aging = AgingLevel, bucketLevel = floor(BucketSystem/bucketDepth)
         * enumerated over raw token counts, concurrency = in-process tokens + 1
         * (the completed request has already left the phase places when the switch fires).
         * Unmapped combinations fall through to 1 (certain violation).
         */
        private String thresholdViolationExpr() {
            String inProcess = processingRepresentation.getInProcessPlaces();
            int maxBucketTokens = bucketDepth * bucketsNumber;

            StringBuilder sb = new StringBuilder();
            int closes = 0;
            for (int agingLvl = 0; agingLvl <= maxAgingLevel; agingLvl++) {
                for (int bucketTokens = 0; bucketTokens <= maxBucketTokens; bucketTokens++) {
                    for (int inProc = 0; inProc < maxConcurrency; inProc++) {
                        ResponseTimeConfiguration key = new ResponseTimeConfiguration(
                                agingLvl, bucketTokens / bucketDepth, inProc + 1);
                        Double p = thresholdViolationProbabilityMap.get(key);
                        if (p == null)
                            throw new IllegalStateException("Missing violation probability for " + key);
                        sb.append("If(AgingLevel==").append(agingLvl)
                                .append("&&BucketSystem==").append(bucketTokens)
                                .append("&&").append(inProcess).append("==").append(inProc)
                                .append(", ").append(BigDecimal.valueOf(p).toPlainString())
                                .append(", ");
                        closes++;
                    }
                }
            }
            sb.append("1").append(")".repeat(closes));   // default
            return sb.toString();
        }


        private void buildRejuvenationModule() {
            Place Ready = net.addPlace("Ready");
            Place Rejuvenation = net.addPlace("Rejuvenation");
            marking.setTokens(Ready, 1);

            Transition rejTrigger = net.addTransition("rejTrigger");
            net.addPostcondition(rejTrigger, Rejuvenation);
            net.addPrecondition(Ready, rejTrigger);

            rejTrigger.addFeature(new EnablingFunction("BucketSystem==(" + bucketDepth * bucketsNumber + ")"));
            rejTrigger.addFeature(new PostUpdater("BucketSystem=0;" + this.processingRepresentation.emptyProcessPlaces(), net));
            rejTrigger.addFeature(StochasticTransitionFeature.newDeterministicInstance(new BigDecimal("0"), MarkingExpr.from("1", net)));
            rejTrigger.addFeature(new Priority(0));


            Transition rejTime = net.addTransition("rejTime");
            net.addPostcondition(rejTime, Ready);
            net.addPrecondition(Rejuvenation, rejTime);
            rejTime.addFeature(StochasticTransitionFeature.newExponentialInstance(new BigDecimal("1"), MarkingExpr.from("1", net)));
            rejTime.addFeature(new PostUpdater("AgingLevel=0", net));
        }


        @Deprecated
        public MicroserviceModel buildOLD() {

            //Generating Nodes
            Place Aging1 = net.addPlace("Aging1");
            Place Aging2 = net.addPlace("Aging2");
            Place Aging3 = net.addPlace("Aging3");
            Place AgingEffect = net.addPlace("AgingEffect");
            Place AgingLevel = net.addPlace("AgingLevel");
            Place BucketDepth = net.addPlace("BucketDepth");
            Place BucketSystem = net.addPlace("BucketSystem");
            Place BucketsNumber = net.addPlace("BucketsNumber");
            Place Executed = net.addPlace("Executed");
            Place Healthy = net.addPlace("Healthy");
            Place HighWorkload = net.addPlace("HighWorkload");
            Place Late = net.addPlace("Late");
            Place LowWorkload = net.addPlace("LowWorkload");
            Place MaxAgingLevel = net.addPlace("MaxAgingLevel");
            Place MaxConcurrency = net.addPlace("MaxConcurrency");
            Place MidWorkload = net.addPlace("MidWorkload");
            Place OnTime = net.addPlace("OnTime");
            Place Ph1 = net.addPlace("Ph1");
            Place Ph2 = net.addPlace("Ph2");
            Place Ph3 = net.addPlace("Ph3");
            Place Ph4 = net.addPlace("Ph4");
            Place Ph5 = net.addPlace("Ph5");
            Place Ready = net.addPlace("Ready");
            Place Rejuvenation = net.addPlace("Rejuvenation");
            Place RequestsBeingServed = net.addPlace("RequestsBeingServed");
            Place ToExecute = net.addPlace("ToExecute");
            Place W01 = net.addPlace("W01");
            Place W02 = net.addPlace("W02");
            Place W03 = net.addPlace("W03");
            Place W04 = net.addPlace("W04");
            Place W05 = net.addPlace("W05");
            Place W11 = net.addPlace("W11");
            Place W12 = net.addPlace("W12");
            Place W13 = net.addPlace("W13");
            Place W14 = net.addPlace("W14");
            Place W15 = net.addPlace("W15");
            Place W21 = net.addPlace("W21");
            Place W22 = net.addPlace("W22");
            Place W23 = net.addPlace("W23");
            Place W24 = net.addPlace("W24");
            Place W25 = net.addPlace("W25");
            Place W31 = net.addPlace("W31");
            Place W32 = net.addPlace("W32");
            Place W33 = net.addPlace("W33");
            Place W34 = net.addPlace("W34");
            Place W35 = net.addPlace("W35");
            Transition BucketSystemEmpty = net.addTransition("BucketSystemEmpty");
            Transition BucketSystemFull = net.addTransition("BucketSystemFull");
            Transition agingIncrease = net.addTransition("agingIncrease");
            Transition arrive = net.addTransition("arrive");
            Transition decreaseBucketSystem = net.addTransition("decreaseBucketSystem");
            Transition highToMid = net.addTransition("highToMid");
            Transition increaseBucketSystem = net.addTransition("increaseBucketSystem");
            Transition lowToMid = net.addTransition("lowToMid");
            Transition midToHigh = net.addTransition("midToHigh");
            Transition midToLow = net.addTransition("midToLow");
            Transition noAgingIncrease = net.addTransition("noAgingIncrease");
            Transition rejTime = net.addTransition("rejTime");
            Transition rejTrigger = net.addTransition("rejTrigger");
            Transition t1 = net.addTransition("t1");
            Transition t2 = net.addTransition("t2");
            Transition t25 = net.addTransition("t25");
            Transition t26 = net.addTransition("t26");
            Transition t27 = net.addTransition("t27");
            Transition t28 = net.addTransition("t28");
            Transition t29 = net.addTransition("t29");
            Transition t3 = net.addTransition("t3");
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
            Transition t4 = net.addTransition("t4");
            Transition t40 = net.addTransition("t40");
            Transition t41 = net.addTransition("t41");
            Transition t42 = net.addTransition("t42");
            Transition t43 = net.addTransition("t43");
            Transition t44 = net.addTransition("t44");
            Transition t5 = net.addTransition("t5");
            Transition thresholdExceeded = net.addTransition("thresholdExceeded");
            Transition thresholdNotExceeded = net.addTransition("thresholdNotExceeded");
            Transition toAging1 = net.addTransition("toAging1");
            Transition toAging2 = net.addTransition("toAging2");
            Transition toAging3 = net.addTransition("toAging3");
            Transition toHealthy = net.addTransition("toHealthy");


            //Generating Connectors
            net.addInhibitorArc(BucketSystem, BucketSystemEmpty);
            net.addPostcondition(t4, Ph5);
            net.addPostcondition(t44, Ph1);
            net.addPostcondition(t43, Ph2);
            net.addPrecondition(Aging2, t43);
            net.addPrecondition(Executed, thresholdNotExceeded);
            net.addPostcondition(t34, Ph1);
            net.addPostcondition(t2, Ph3);
            net.addPostcondition(rejTime, Ready);
            net.addPrecondition(Healthy, t30);
            net.addPrecondition(Ph2, t2);
            net.addPrecondition(ToExecute, toHealthy);
            net.addPrecondition(OnTime, BucketSystemEmpty);
            net.addPostcondition(t37, Ph4);
            net.addPrecondition(Aging1, t38);
            net.addPrecondition(Ph5, t5);
            net.addPostcondition(t26, Ph3);
            net.addPrecondition(Aging3, t26);
            net.addPostcondition(rejTrigger, Rejuvenation);
            net.addPostcondition(t42, Ph3);
            net.addPostcondition(toAging1, Aging2);
            net.addPostcondition(arrive, RequestsBeingServed);
            net.addPrecondition(Aging2, t42);
            net.addPrecondition(Aging2, t41);
            net.addPostcondition(thresholdNotExceeded, OnTime);
            net.addPrecondition(Aging3, t25);
            net.addPostcondition(t1, Ph2);
            net.addPostcondition(t28, Ph5);
            net.addPrecondition(Aging2, t40);
            net.addPostcondition(t5, AgingEffect);
            net.addPrecondition(BucketSystem, decreaseBucketSystem);
            net.addPostcondition(thresholdExceeded, Late);
            net.addPrecondition(Healthy, t31);
            net.addPrecondition(Late, increaseBucketSystem);
            net.addPrecondition(ToExecute, toAging1);
            net.addPrecondition(Aging1, t35);
            net.addPrecondition(Aging3, t29);
            net.addPrecondition(Aging1, t36);
            net.addPrecondition(LowWorkload, midToLow);
            net.addPrecondition(Aging1, t37);
            net.addPrecondition(Healthy, t32);
            net.addPostcondition(t5, Executed);
            net.addPostcondition(toAging2, Aging1);
            net.addPostcondition(highToMid, HighWorkload);
            net.addPrecondition(Executed, thresholdExceeded);
            net.addPostcondition(toAging3, Aging3);
            net.addPostcondition(t32, Ph3);
            net.addPrecondition(Rejuvenation, rejTime);
            net.addPostcondition(t30, Ph5);
            net.addPrecondition(Late, BucketSystemFull);
            net.addPostcondition(t3, Ph4);
            net.addPrecondition(OnTime, decreaseBucketSystem);
            net.addPrecondition(Aging2, t44);
            net.addPrecondition(RequestsBeingServed, t5);
            net.addPostcondition(t36, Ph1);
            net.addPostcondition(t33, Ph2);
            net.addPostcondition(t41, Ph4);
            net.addPrecondition(Healthy, t34);
            net.addPrecondition(Ph1, t1);
            net.addPrecondition(Ph4, t4);
            net.addPrecondition(Aging3, t28);
            net.addPostcondition(increaseBucketSystem, BucketSystem);
            net.addPrecondition(Healthy, t33);
            net.addPostcondition(lowToMid, LowWorkload);
            net.addPrecondition(AgingEffect, noAgingIncrease);
            net.addPostcondition(t38, Ph3);
            net.addPostcondition(arrive, ToExecute);
            net.addPrecondition(ToExecute, toAging3);
            net.addPrecondition(Ready, rejTrigger);
            net.addPostcondition(t40, Ph5);
            net.addPrecondition(Ph3, t3);
            net.addPostcondition(agingIncrease, AgingLevel);
            net.addPrecondition(MidWorkload, highToMid);
            net.addPrecondition(Aging3, t27);
            net.addPostcondition(t27, Ph4);
            net.addPrecondition(HighWorkload, midToHigh);
            net.addPrecondition(ToExecute, toAging2);
            net.addPrecondition(AgingEffect, agingIncrease);
            net.addPostcondition(t25, Ph2);
            net.addPostcondition(toHealthy, Healthy);
            net.addPostcondition(midToLow, MidWorkload);
            net.addPrecondition(Aging1, t39);
            net.addPrecondition(MidWorkload, lowToMid);
            net.addPostcondition(t35, Ph5);
            net.addPostcondition(t39, Ph2);
            net.addPostcondition(midToHigh, MidWorkload);
            net.addPostcondition(t29, Ph1);
            net.addPostcondition(t31, Ph4);

            //Generating Properties
            marking.setTokens(Aging1, 0);
            marking.setTokens(Aging2, 0);
            marking.setTokens(Aging3, 0);
            marking.setTokens(AgingEffect, 0);
            marking.setTokens(AgingLevel, 0);
            marking.setTokens(BucketDepth, 2);
            marking.setTokens(BucketSystem, 0);
            marking.setTokens(BucketsNumber, 2);
            marking.setTokens(Executed, 0);
            marking.setTokens(Healthy, 0);
            marking.setTokens(HighWorkload, 0);
            marking.setTokens(Late, 0);
            marking.setTokens(LowWorkload, 1);
            marking.setTokens(MaxAgingLevel, 3);
            marking.setTokens(MaxConcurrency, 4);
            marking.setTokens(MidWorkload, 0);
            marking.setTokens(OnTime, 0);
            marking.setTokens(Ph1, 0);
            marking.setTokens(Ph2, 0);
            marking.setTokens(Ph3, 0);
            marking.setTokens(Ph4, 0);
            marking.setTokens(Ph5, 0);
            marking.setTokens(Ready, 1);
            marking.setTokens(Rejuvenation, 0);
            marking.setTokens(RequestsBeingServed, 0);
            marking.setTokens(ToExecute, 0);
            marking.setTokens(W01, 1);
            marking.setTokens(W02, 2);
            marking.setTokens(W03, 3);
            marking.setTokens(W04, 4);
            marking.setTokens(W05, 5);
            marking.setTokens(W11, 1);
            marking.setTokens(W12, 2);
            marking.setTokens(W13, 4);
            marking.setTokens(W14, 4);
            marking.setTokens(W15, 4);
            marking.setTokens(W21, 3);
            marking.setTokens(W22, 4);
            marking.setTokens(W23, 3);
            marking.setTokens(W24, 3);
            marking.setTokens(W25, 2);
            marking.setTokens(W31, 5);
            marking.setTokens(W32, 4);
            marking.setTokens(W33, 3);
            marking.setTokens(W34, 2);
            marking.setTokens(W35, 1);
            BucketSystemEmpty.addFeature(StochasticTransitionFeature.newDeterministicInstance(new BigDecimal("0"), MarkingExpr.from("1", net)));
            BucketSystemEmpty.addFeature(new Priority(0));
            BucketSystemFull.addFeature(new EnablingFunction("BucketSystem==BucketDepth*BucketsNumber"));
            BucketSystemFull.addFeature(StochasticTransitionFeature.newDeterministicInstance(new BigDecimal("0"), MarkingExpr.from("1", net)));
            BucketSystemFull.addFeature(new Priority(0));
            agingIncrease.addFeature(new EnablingFunction("AgingLevel<MaxAgingLevel"));
            agingIncrease.addFeature(StochasticTransitionFeature.newDeterministicInstance(new BigDecimal("0"), MarkingExpr.from("AgingLevel+1", net)));
            agingIncrease.addFeature(new Priority(2));
            arrive.addFeature(new EnablingFunction("Ph1+Ph2+Ph3+Ph4+Ph5<MaxConcurrency&&Ready==1"));
            arrive.addFeature(StochasticTransitionFeature.newExponentialInstance(new BigDecimal("1"), MarkingExpr.from("If(LowWorkload==1, 1, If(MidWorkload==1, 3, If(HighWorkload==1, 6, 0 ))) ", net)));
            decreaseBucketSystem.addFeature(StochasticTransitionFeature.newDeterministicInstance(new BigDecimal("0"), MarkingExpr.from("1", net)));
            decreaseBucketSystem.addFeature(new Priority(0));
            highToMid.addFeature(StochasticTransitionFeature.newExponentialInstance(new BigDecimal("1"), MarkingExpr.from("0.01", net)));
            increaseBucketSystem.addFeature(new EnablingFunction("BucketSystem<(BucketDepth*BucketsNumber)"));
            increaseBucketSystem.addFeature(StochasticTransitionFeature.newDeterministicInstance(new BigDecimal("0"), MarkingExpr.from("1", net)));
            increaseBucketSystem.addFeature(new Priority(0));
            lowToMid.addFeature(StochasticTransitionFeature.newExponentialInstance(new BigDecimal("1"), MarkingExpr.from("0.01", net)));
            midToHigh.addFeature(StochasticTransitionFeature.newExponentialInstance(new BigDecimal("1"), MarkingExpr.from("0.01", net)));
            midToLow.addFeature(StochasticTransitionFeature.newExponentialInstance(new BigDecimal("1"), MarkingExpr.from("0.01", net)));
            noAgingIncrease.addFeature(StochasticTransitionFeature.newDeterministicInstance(new BigDecimal("0"), MarkingExpr.from("1", net)));
            noAgingIncrease.addFeature(new Priority(2));
            rejTime.addFeature(StochasticTransitionFeature.newExponentialInstance(new BigDecimal("1"), MarkingExpr.from("1", net)));
            rejTrigger.addFeature(new EnablingFunction("BucketSystem==(BucketDepth*BucketsNumber)"));
            rejTrigger.addFeature(new PostUpdater("BucketSystem=0;AgingLevel=0;Ph1=0;Ph2=0;Ph3=0;Ph4=0;Ph5=0;", net));
            rejTrigger.addFeature(StochasticTransitionFeature.newDeterministicInstance(new BigDecimal("0"), MarkingExpr.from("1", net)));
            rejTrigger.addFeature(new Priority(0));
            t1.addFeature(StochasticTransitionFeature.newExponentialInstance(new BigDecimal("1"), MarkingExpr.from("(Ph1*1)/(Ph1+Ph2+Ph3+Ph4+Ph5)", net)));
            t2.addFeature(StochasticTransitionFeature.newExponentialInstance(new BigDecimal("1"), MarkingExpr.from("(Ph2*2)/(Ph1+Ph2+Ph3+Ph4+Ph5)", net)));
            t25.addFeature(StochasticTransitionFeature.newDeterministicInstance(new BigDecimal("0"), MarkingExpr.from("W32", net)));
            t25.addFeature(new Priority(0));
            t26.addFeature(StochasticTransitionFeature.newDeterministicInstance(new BigDecimal("0"), MarkingExpr.from("W33", net)));
            t26.addFeature(new Priority(0));
            t27.addFeature(StochasticTransitionFeature.newDeterministicInstance(new BigDecimal("0"), MarkingExpr.from("W34", net)));
            t27.addFeature(new Priority(0));
            t28.addFeature(StochasticTransitionFeature.newDeterministicInstance(new BigDecimal("0"), MarkingExpr.from("W35", net)));
            t28.addFeature(new Priority(0));
            t29.addFeature(StochasticTransitionFeature.newDeterministicInstance(new BigDecimal("0"), MarkingExpr.from("W31", net)));
            t29.addFeature(new Priority(0));
            t3.addFeature(StochasticTransitionFeature.newExponentialInstance(new BigDecimal("1"), MarkingExpr.from("(Ph3*3)/(Ph1+Ph2+Ph3+Ph4+Ph5)", net)));
            t30.addFeature(StochasticTransitionFeature.newDeterministicInstance(new BigDecimal("0"), MarkingExpr.from("W05", net)));
            t30.addFeature(new Priority(0));
            t31.addFeature(StochasticTransitionFeature.newDeterministicInstance(new BigDecimal("0"), MarkingExpr.from("W04", net)));
            t31.addFeature(new Priority(0));
            t32.addFeature(StochasticTransitionFeature.newDeterministicInstance(new BigDecimal("0"), MarkingExpr.from("W03", net)));
            t32.addFeature(new Priority(0));
            t33.addFeature(StochasticTransitionFeature.newDeterministicInstance(new BigDecimal("0"), MarkingExpr.from("W02", net)));
            t33.addFeature(new Priority(0));
            t34.addFeature(StochasticTransitionFeature.newDeterministicInstance(new BigDecimal("0"), MarkingExpr.from("W01", net)));
            t34.addFeature(new Priority(0));
            t35.addFeature(StochasticTransitionFeature.newDeterministicInstance(new BigDecimal("0"), MarkingExpr.from("W25", net)));
            t35.addFeature(new Priority(0));
            t36.addFeature(StochasticTransitionFeature.newDeterministicInstance(new BigDecimal("0"), MarkingExpr.from("W21", net)));
            t36.addFeature(new Priority(0));
            t37.addFeature(StochasticTransitionFeature.newDeterministicInstance(new BigDecimal("0"), MarkingExpr.from("W24", net)));
            t37.addFeature(new Priority(0));
            t38.addFeature(StochasticTransitionFeature.newDeterministicInstance(new BigDecimal("0"), MarkingExpr.from("W23", net)));
            t38.addFeature(new Priority(0));
            t39.addFeature(StochasticTransitionFeature.newDeterministicInstance(new BigDecimal("0"), MarkingExpr.from("W22", net)));
            t39.addFeature(new Priority(0));
            t4.addFeature(StochasticTransitionFeature.newExponentialInstance(new BigDecimal("1"), MarkingExpr.from("(Ph4*4)/(Ph1+Ph2+Ph3+Ph4+Ph5)", net)));
            t40.addFeature(StochasticTransitionFeature.newDeterministicInstance(new BigDecimal("0"), MarkingExpr.from("W15", net)));
            t40.addFeature(new Priority(0));
            t41.addFeature(StochasticTransitionFeature.newDeterministicInstance(new BigDecimal("0"), MarkingExpr.from("W14", net)));
            t41.addFeature(new Priority(0));
            t42.addFeature(StochasticTransitionFeature.newDeterministicInstance(new BigDecimal("0"), MarkingExpr.from("W13", net)));
            t42.addFeature(new Priority(0));
            t43.addFeature(StochasticTransitionFeature.newDeterministicInstance(new BigDecimal("0"), MarkingExpr.from("W12", net)));
            t43.addFeature(new Priority(0));
            t44.addFeature(StochasticTransitionFeature.newDeterministicInstance(new BigDecimal("0"), MarkingExpr.from("W11", net)));
            t44.addFeature(new Priority(0));
            t5.addFeature(StochasticTransitionFeature.newExponentialInstance(new BigDecimal("1"), MarkingExpr.from("(Ph5*5)/(Ph1+Ph2+Ph3+Ph4+Ph5)", net)));
            thresholdExceeded.addFeature(StochasticTransitionFeature.newDeterministicInstance(new BigDecimal("0"), MarkingExpr.from("1-((MaxAgingLevel-AgingLevel)*0.1) ", net)));
            thresholdExceeded.addFeature(new Priority(1));
            thresholdNotExceeded.addFeature(StochasticTransitionFeature.newDeterministicInstance(new BigDecimal("0"), MarkingExpr.from("(MaxAgingLevel-AgingLevel)*0.1 ", net)));
            thresholdNotExceeded.addFeature(new Priority(1));
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

            return new MicroserviceModel(this);



        }

    }
}
