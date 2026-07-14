package it.unifi.dinfo.stlab;

import org.oristool.models.stpn.RewardRate;
import org.oristool.models.stpn.TransientSolution;
import org.oristool.petrinet.Marking;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class Utils {

    public static void exportNamedRewardsCsv(TransientSolution<Marking, RewardRate> rew, double timestep,
                                             Map<String, String> namedRewards,
                                             String csvPath, String mappingPath) throws IOException {
        double[][][] sol = rew.getSolution();          // [tempo][statoIniziale][reward]
        List<String> labels = List.copyOf(namedRewards.keySet());
        if (sol[0][0].length != labels.size())
            throw new IllegalArgumentException("Columns (" + sol[0][0].length
                    + ") different from the rewards (" + labels.size() + ")");

        try (BufferedWriter w = Files.newBufferedWriter(Paths.get(csvPath))) {
            w.write("time," + String.join(",", labels));
            w.newLine();
            for (int t = 0; t < sol.length; t++) {
                StringBuilder sb = new StringBuilder();
                sb.append(String.format(Locale.US, "%.6f", t * timestep));
                for (int j = 0; j < labels.size(); j++)
                    sb.append(',').append(Double.toString(sol[t][0][j]));
                w.write(sb.toString());
                w.newLine();
            }
        }

        try (BufferedWriter w = Files.newBufferedWriter(Paths.get(mappingPath))) {
            w.write("name,expression");
            w.newLine();
            for (Map.Entry<String, String> e : namedRewards.entrySet()) {
                w.write(e.getKey() + ",\"" + e.getValue().replace("\"", "\"\"") + "\"");
                w.newLine();
            }
        }
    }
}
