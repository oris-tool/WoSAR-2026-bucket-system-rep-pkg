package it.unifi.dinfo.stlab;

import org.oristool.petrinet.PetriNet;

public abstract class RejuvenationRepresentationStrategy {

    /** Espressione dei posti che contengono il token durante la rejuvenation. */
    public abstract String getInRejuvenationPlaces();

    /**
     * Costruisce la rappresentazione del tempo di rejuvenation.
     * Presuppone che i posti "Rejuvenation" e "Ready" esistano già nella rete;
     * la transizione finale deve riportare il token in "Ready" e azzerare AgingLevel.
     */
    public abstract void build(PetriNet net);

    public abstract double[] getRejuvenationTimeDistribution(double step);

}
