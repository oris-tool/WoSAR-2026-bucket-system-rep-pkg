package it.unifi.dinfo.stlab;

import java.io.IOException;

public class WosarExperiment {

    public static void main(String[] args) throws IOException {


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
                .build("MostlyMid");
        return p;
    }

}
