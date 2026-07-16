package it.unifi.dinfo.stlab.simulation;

import org.oristool.petrinet.Transition;
import org.oristool.simulator.Sequencer;
import org.oristool.simulator.SequencerObserver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Observes a {@link Sequencer} and records, for each simulation run, the
 * elapsed time at which the completion transition fires. One run = one
 * request: the observed net must reach a dead marking after the completion
 * transition, so that the run ends and the Sequencer starts the next one.
 *
 * <p>FIRING_EXECUTED events are delivered to current-run observers only, so
 * this collector registers itself both as a simulation observer (RUN_START/
 * RUN_END) and as a current-run observer. After {@code targetRuns} collected
 * samples it detaches from the Sequencer, ending the simulation.
 */
public class CompletionTimeCollector implements SequencerObserver {

    private final Sequencer sequencer;
    private final String completionTransition;
    private final int targetRuns;

    private final List<Double> completionTimes;
    private double currentRunCompletion = Double.NaN;

    public CompletionTimeCollector(Sequencer sequencer, String completionTransition, int targetRuns) {
        if (targetRuns < 1)
            throw new IllegalArgumentException("targetRuns >= 1");
        this.sequencer = sequencer;
        this.completionTransition = completionTransition;
        this.targetRuns = targetRuns;
        this.completionTimes = new ArrayList<>(targetRuns);
        sequencer.addObserver(this);
        sequencer.addCurrentRunObserver(this);
    }

    @Override
    public void update(Sequencer.SequencerEvent event) {
        switch (event) {
            case RUN_START -> currentRunCompletion = Double.NaN;

            case FIRING_EXECUTED -> {
                Transition fired = (Transition) sequencer.getLastSuccession().getEvent();
                if (fired.getName().equals(completionTransition))
                    currentRunCompletion = sequencer.getCurrentRunElapsedTime().doubleValue();
            }

            case RUN_END -> {
                if (Double.isNaN(currentRunCompletion))
                    throw new IllegalStateException("Run ended without firing " + completionTransition);
                completionTimes.add(currentRunCompletion);
                if (completionTimes.size() >= targetRuns) {
                    sequencer.removeObserver(this);
                    sequencer.removeCurrentRunObserver(this);
                }
            }

            case SIMULATION_START, SIMULATION_END -> { }
        }
    }

    public List<Double> getCompletionTimes() {
        return Collections.unmodifiableList(completionTimes);
    }
}
