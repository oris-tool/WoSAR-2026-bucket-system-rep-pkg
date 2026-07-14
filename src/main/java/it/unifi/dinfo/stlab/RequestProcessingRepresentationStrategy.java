package it.unifi.dinfo.stlab;

import org.oristool.petrinet.PetriNet;

import java.util.Map;

// XXX we assume that some place and transition name are known a-priori
public abstract class RequestProcessingRepresentationStrategy {

    public abstract String getInProcessPlaces();

    public abstract String emptyProcessPlaces();

    public abstract String getThroughputReward();

    public abstract Map<Integer, double[]> getCompletionDistributionForEachAgingLvl(double step);

    public abstract void build(PetriNet net);



}
