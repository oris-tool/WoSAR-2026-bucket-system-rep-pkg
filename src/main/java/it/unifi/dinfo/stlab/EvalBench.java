package it.unifi.dinfo.stlab;


import org.oristool.models.stpn.RewardRate;
import org.oristool.petrinet.Marking;
import org.oristool.petrinet.PetriNet;

public class EvalBench {
    public static void main(String[] args) {
        // same structure as thresholdViolationExpr(): 4 aging x 5 bucket x 4 conc = 80 nested Ifs
        StringBuilder sb = new StringBuilder("Ph5*5.0*");
        int closes = 0;
        for (int aging = 0; aging <= 3; aging++)
            for (int bucket = 0; bucket <= 4; bucket++)
                for (int conc = 0; conc < 4; conc++) {
                    sb.append("If(AgingLevel==").append(aging)
                            .append("&&BucketSystem==").append(bucket)
                            .append("&&Ph1+Ph2+Ph3+Ph4+Ph5==").append(conc)
                            .append(", 0.5, ");
                    closes++;
                }
        sb.append("1").append(")".repeat(closes));

        PetriNet net = new PetriNet();
        Marking m = new Marking();
        for (String p : new String[]{"AgingLevel","BucketSystem","Ph1","Ph2","Ph3","Ph4","Ph5","Rejuvenation"})
            m.setTokens(net.addPlace(p), 1);

        System.out.println("Reward Big:" + sb.toString());
        RewardRate big = RewardRate.fromString(sb.toString());
        RewardRate simple = RewardRate.fromString("AgingLevel");

        // warmup (JIT)
        for (int i = 0; i < 2000; i++) { big.evaluate(0, m); simple.evaluate(0, m); }

        int n = 20000;
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) simple.evaluate(0, m);
        long t1 = System.nanoTime();
        for (int i = 0; i < n; i++) big.evaluate(0, m);
        long t2 = System.nanoTime();

        double usSimple = (t1 - t0) / 1e3 / n;
        double usBig = (t2 - t1) / 1e3 / n;
        System.out.printf("evaluate() semplice ('AgingLevel'): %8.2f us/chiamata%n", usSimple);
        System.out.printf("evaluate() catena 80 If:            %8.2f us/chiamata (%.0fx)%n", usBig, usBig / usSimple);

        int T = 501, M = 2000; // ~punti temporali e ordine di grandezza marking
        System.out.printf("stima nel triplo ciclo (T=%d, M=%d, solo reward grande): %.1f s%n",
                T, M, T * (double) M * usBig / 1e6);
        System.out.printf("stessa cosa col reward semplice: %.2f s%n",
                T * (double) M * usSimple / 1e6);
    }
}