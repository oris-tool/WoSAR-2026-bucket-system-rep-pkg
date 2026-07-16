package it.unifi.dinfo.stlab;

import org.oristool.models.pn.PostUpdater;
import org.oristool.models.stpn.MarkingExpr;
import org.oristool.models.stpn.trees.StochasticTransitionFeature;
import org.oristool.petrinet.PetriNet;
import org.oristool.petrinet.Transition;

import java.math.BigDecimal;
import java.util.Objects;

public class ExponentialRejuvenationRepresentationStrategy extends RejuvenationRepresentationStrategy {

    private final double rate;

    private static final double COMPLETION_TOLERANCE = 1e-6;

    public ExponentialRejuvenationRepresentationStrategy(double rate) {
        if (rate <= 0) throw new IllegalArgumentException("rate > 0");
        this.rate = rate;
    }

    @Override
    public void build(PetriNet net) {
        Objects.requireNonNull(net);

        Transition rejTime = net.addTransition("rejTime");
        net.addPrecondition(net.getPlace("Rejuvenation"), rejTime);
        net.addPostcondition(rejTime, net.getPlace("Ready"));
        rejTime.addFeature(StochasticTransitionFeature.newExponentialInstance(
                BigDecimal.ONE, MarkingExpr.from(num(rate), net)));
        rejTime.addFeature(new PostUpdater("AgingLevel=0", net));
    }

    @Override
    public String getInRejuvenationPlaces() {
        return "Rejuvenation";
    }

    @Override
    public double[] getRejuvenationTimeDistribution(double step) {
        if (step <= 0) throw new IllegalArgumentException("step > 0");
        // F(t) = 1 - e^{-rate*t} raggiunge 1 - tol a t = -ln(tol)/rate
        int end = (int) Math.ceil(-Math.log(COMPLETION_TOLERANCE) / (rate * step));
        double[] cdf = new double[end + 1];
        for (int i = 0; i < end; i++)
            cdf[i] = 1.0 - Math.exp(-rate * i * step);
        cdf[end] = 1.0;
        return cdf;
    }

    private static String num(double d) {
        if (d == 0.0) return "0";
        return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
    }
}
