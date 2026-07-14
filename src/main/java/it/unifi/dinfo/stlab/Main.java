package it.unifi.dinfo.stlab;

import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;
import org.knowm.xchart.SwingWrapper;
import org.knowm.xchart.XChartPanel;
import org.knowm.xchart.XYChart;
import org.knowm.xchart.XYChartBuilder;
import org.oristool.lello.ast.Expression;
import org.oristool.lello.parse.LelloLexer;
import org.oristool.lello.parse.LelloParser;
import org.oristool.lello.parse.ListTerminalStream;
import org.oristool.lello.parse.Terminal;
import org.oristool.models.stpn.RewardRate;
import org.oristool.models.stpn.TransientSolution;
import org.oristool.models.stpn.TransientSolutionViewer;
import org.oristool.petrinet.Marking;
import org.oristool.petrinet.PetriNet;

import javax.swing.*;
import java.awt.*;
import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
public class Main {
    public static void main(String[] args) throws IOException {

        int maxAgingLevel = 3;

        WorkloadProfile.Builder pb = new WorkloadProfile.Builder();
        WorkloadLevel low = pb.level("Low", 1.0);
        WorkloadLevel mid  = pb.level("Mid",  3.0);
        WorkloadLevel high = pb.level("High", 6.0);
        WorkloadProfile profile = pb
                .transition(low, mid, 0.01).transition(mid, high, 0.1)
                .transition(high, mid, 0.01).transition(mid, low, 0.1)
                .build("aProfile");


        BphProcessingRepresentationStrategy bph = new BphProcessingRepresentationStrategy(5, maxAgingLevel);
        bph.phaseRates(1,2,3,4,5)
                .weights(0, 1,2,3,4,5)
                .weights(1,1,2,4,4,4)
                .weights(2, 3,4,3,3,2)
                .weights(3,5,4,3,2,1);

        MicroserviceModel.Builder builder = MicroserviceModel.builder();
        builder.workLoadProfile(profile).initialWorkloadLevel("Low");
        builder.maxConcurrency(4);
        builder.processingStrategy(bph);
        builder.bucketDepth(2).bucketsNumber(2);
        builder.maxAgingLevel(maxAgingLevel);


        MicroserviceModel model = builder.build();

        double step = 0.1;
        model.transientAnalysis(50, step, "Rejuvenation==1");
        TransientSolution<Marking, RewardRate> rew = model.computeRewardSolution(model.getTotalReward());
        System.out.println("Transient Solution calculated");

        Utils.exportNamedRewardsCsv(rew, step, model.getNamedRewards(),
                "rewards.csv", "rewards_mapping.csv");
        System.out.println("Rewards saved to rewards.csv (mapping in rewards_mapping.csv)");

        new TransientSolutionViewer(rew);   // una curva per reward, colonne = i ';'






    }
    public static void compareSolutions(TransientSolution<?, RewardRate> ref,
                                        TransientSolution<?, RewardRate> other,
                                        String rewardsAsString, double tol) {

        double[][][] a = ref.getSolution();     // [tempo][statoIniziale][reward]
        double[][][] b = other.getSolution();

        int nt = Math.min(a.length, b.length);
        if (a.length != b.length)
            System.out.printf("ATTENZIONE: punti temporali diversi (%d vs %d) -> confronto sui primi %d%n",
                    a.length, b.length, nt);

        int nr = Math.min(a[0][0].length, b[0][0].length);
        if (a[0][0].length != b[0][0].length)
            System.out.printf("ATTENZIONE: numero di reward diverso (%d vs %d)%n",
                    a[0][0].length, b[0][0].length);

        String[] labels = rewardsAsString.split(";");

        for (int j = 0; j < nr; j++) {
            double maxAbs = 0, sumAbs = 0, maxRefMag = 0;
            int argmax = 0, firstDiverge = -1;

            for (int t = 0; t < nt; t++) {
                double va = a[t][0][j], vb = b[t][0][j];
                double d = Math.abs(va - vb);

                if (d > maxAbs) { maxAbs = d; argmax = t; }
                sumAbs += d;
                maxRefMag = Math.max(maxRefMag, Math.abs(va));
                if (firstDiverge < 0 && d > tol) firstDiverge = t;
            }

            String label = j < labels.length ? labels[j].trim() : ("reward[" + j + "]");
            double relMax = maxRefMag > 0 ? maxAbs / maxRefMag : 0;   // errore normalizzato

            System.out.printf(Locale.US,
                    "%-14s maxAbs=%.3e (@t=%d: ref=%.6g vs other=%.6g)  meanAbs=%.3e  relMax=%.3e  primo>%.0e: %s%n",
                    label, maxAbs, argmax, a[argmax][0][j], b[argmax][0][j],
                    sumAbs / nt, relMax, tol,
                    firstDiverge < 0 ? "mai" : ("t-index " + firstDiverge));
        }
    }


    public static void exportNamedRewardsCsvOLD(TransientSolution<Marking, RewardRate> rew, double timestep,
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

    public static void visualizeCompletionTimePerAging(Map<Integer, Double[]> dati, double step) {

        // 1. Creiamo il dataset specifico per JFreeChart
        XYSeriesCollection dataset = new XYSeriesCollection();

        for (Map.Entry<Integer, Double[]> entry : dati.entrySet()) {
            Integer agingLevel = entry.getKey();
            Double[] yDataObjects = entry.getValue();

            if (yDataObjects == null || yDataObjects.length == 0) {
                continue; // Salta le curve vuote
            }

            // Creiamo una serie (curva) per questo aging level
            XYSeries series = new XYSeries("Aging Level " + agingLevel);

            for (int i = 0; i < yDataObjects.length; i++) {
                double x = i * step;
                double y = yDataObjects[i] != null ? yDataObjects[i] : 0.0;
                series.add(x, y); // JFreeChart mappa i punti (x, y) uno a uno
            }

            dataset.addSeries(series);
        }

        // 2. Creiamo il grafico XY a linee
        JFreeChart chart = ChartFactory.createXYLineChart(
                "Distribuzione Completamento per Aging Level", // Titolo grafico
                "X (Step: " + step + ")",                      // Etichetta asse X
                "Valore di Completamento",                     // Etichetta asse Y
                dataset                                         // Dati
        );

        // 3. Prepariamo l'interfaccia con il JFrame
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Controllo Curve (JFreeChart)");
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.setLayout(new BorderLayout());

            // JFreeChart usa il ChartPanel per visualizzare i grafici in Swing
            ChartPanel chartPanel = new ChartPanel(chart);
            chartPanel.setPreferredSize(new Dimension(800, 600)); // Dimensione della finestra grafica

            frame.add(chartPanel, BorderLayout.CENTER);
            frame.pack();
            frame.setLocationRelativeTo(null); // Centra lo schermo
            frame.setVisible(true);
        });
    }


}