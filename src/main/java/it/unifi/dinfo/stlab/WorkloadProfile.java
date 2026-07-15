package it.unifi.dinfo.stlab;

import java.util.*;
import java.util.logging.Level;

public class WorkloadProfile {

    public WorkloadLevel getInitialWorkloadLevel() {
        return initialWorkloadLevel;
    }

    public String getName() {
        return name;
    }

    public record Step(WorkloadLevel from, WorkloadLevel to, double rate) {}

    private final Map<String, WorkloadLevel> levels;
    private final Map<String, Map<String, Double>> transitions; // from -> (to -> rate)
    private final String name;
    private final WorkloadLevel initialWorkloadLevel;

    private WorkloadProfile(Map<String, WorkloadLevel> levels,
                       Map<String, Map<String, Double>> transitions, WorkloadLevel intialLevel, String name) {
        this.levels = levels;
        this.transitions = transitions;
        this.initialWorkloadLevel = intialLevel;
        this.name = name;
    }


    public WorkloadLevel workloadLevel(String name) {
        WorkloadLevel l = levels.get(name);
        if (l == null) throw new NoSuchElementException("Unknown Workload Level: " + name);
        return l;
    }

    public double rateOf(String name) { return workloadLevel(name).rate(); }

    public Collection<WorkloadLevel> levels() {
        return Collections.unmodifiableCollection(levels.values());
    }

    public boolean hasLevel(String name) {
        return levels.containsKey(name);
    }


    /** true if direct switch from -> to levels is allowed. */
    public boolean canTransition(String from, String to) {
        Map<String, Double> out = transitions.get(from);
        return out != null && out.containsKey(to);
    }

    public double transitionRate(String from, String to) {
        Map<String, Double> out = transitions.get(from);
        if (out == null || !out.containsKey(to))
            throw new IllegalStateException(
                    "Transition not allowed: " + from + " -> " + to);
        return out.get(to);
    }

    public List<Step> steps() {
        List<Step> out = new ArrayList<>();
        transitions.forEach((from, m) ->
                m.forEach((to, rate) -> out.add(new Step(workloadLevel(from), workloadLevel(to), rate))));
        return out;
    }

    public static final class Builder {
        private final Map<String, WorkloadLevel> levels = new LinkedHashMap<>();
        private final Map<String, Map<String, Double>> transitions = new LinkedHashMap<>();
        private WorkloadLevel intialWorkloadLevel;

        public WorkloadLevel level(String name, double rate) {
            if (levels.containsKey(name))
                throw new IllegalArgumentException("Duplicated Workload Level: " + name);
            WorkloadLevel l = new WorkloadLevel(name, rate);
            levels.put(name, l);
            return l;
        }


        public Builder transition(WorkloadLevel from, WorkloadLevel to, double rate) {
            requireOwned(from);
            requireOwned(to);
            if (rate < 0)
                throw new IllegalArgumentException("Negative Transition Rate: " + rate);
            transitions
                    .computeIfAbsent(from.name(), k -> new LinkedHashMap<>())
                    .put(to.name(), rate);
            return this;
        }

        public Builder initialWorkloadLevel(WorkloadLevel level) {
            requireOwned(level);
            this.intialWorkloadLevel = level;
            return this;
        }

        private void requireOwned(WorkloadLevel l) {
            if (levels.get(l.name()) != l)
                throw new IllegalArgumentException(
                        "Workload level " + l.name() + " does not belong to this builder");
        }

        public WorkloadProfile build(String name) {
            Map<String, WorkloadLevel> lv = new LinkedHashMap<>(levels);
            Map<String, Map<String, Double>> tr = new LinkedHashMap<>();
            transitions.forEach((from, m) ->
                    tr.put(from, Collections.unmodifiableMap(new LinkedHashMap<>(m))));
            return new WorkloadProfile(
                    Collections.unmodifiableMap(lv),
                    Collections.unmodifiableMap(tr), this.intialWorkloadLevel, name);
        }
    }
}


