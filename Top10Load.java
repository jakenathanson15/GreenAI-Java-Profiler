package demo;

public class Top10Load {
    public static void main(String[] args) {
        System.out.println("Starting Top10Load with MAXIMIZED energy progression...");
        
        // Warm up JVM first to stabilize measurements
        System.out.println("Warming up JVM...");
        warmup();
        
        System.out.println("\n=== BEGINNING ENERGY MEASUREMENTS ===\n");
        
        // Add clear time gaps between methods for easier attribution
        runWithGap(1);  // Least energy
        runWithGap(2);  // 2x work1
        runWithGap(3);  // 2x work2
        runWithGap(4);  // 2x work3
        runWithGap(5);  // 2x work4
        runWithGap(6);  // 2x work5
        runWithGap(7);  // 1.5x work6
        runWithGap(8);  // 1.5x work7
        runWithGap(9);  // 1.5x work8
        runWithGap(10); // 1.5x work9 - Most energy
        
        System.out.println("\n=== ENERGY MEASUREMENTS COMPLETE ===");
    }
    
    private static void warmup() {
        // Run lightweight computation to get JVM warmed up
        for (int i = 0; i < 3; i++) {
            computeIntensive(500_000);
        }
    }
    
    private static void runWithGap(int methodNum) {
        // Create clear boundary before method
        sleep(500);
        
        // Print obvious marker for power measurement correlation
        System.out.println("╔════════════════════════════════════╗");
        System.out.println("║ STARTING WORK" + methodNum + 
                           (methodNum < 10 ? " " : "") + "                     ║");
        System.out.println("╚════════════════════════════════════╝");
        
        // Run the method
        switch (methodNum) {
            case 1: work1(); break;
            case 2: work2(); break;
            case 3: work3(); break;
            case 4: work4(); break;
            case 5: work5(); break;
            case 6: work6(); break;
            case 7: work7(); break;
            case 8: work8(); break;
            case 9: work9(); break;
            case 10: work10(); break;
        }
        
        // Print completion marker
        System.out.println("✓ COMPLETED WORK" + methodNum);
        
        // Create clear boundary after method
        sleep(500);
    }
    
    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            // Ignore
        }
    }
    
    // EXPONENTIAL progression - each method approximately doubles the previous
    public static void work1()  { computeIntensive(1_000_000); }    // Base level
    public static void work2()  { computeIntensive(2_000_000); }    // 2× work1
    public static void work3()  { computeIntensive(4_000_000); }    // 2× work2
    public static void work4()  { computeIntensive(8_000_000); }    // 2× work3
    public static void work5()  { computeIntensive(16_000_000); }   // 2× work4
    public static void work6()  { computeIntensive(32_000_000); }   // 2× work5
    public static void work7()  { computeIntensive(48_000_000); }   // 1.5× work6
    public static void work8()  { computeIntensive(72_000_000); }   // 1.5× work7
    public static void work9()  { computeIntensive(108_000_000); }  // 1.5× work8
    public static void work10() { computeIntensive(162_000_000); }  // 1.5× work9
    
    private static void computeIntensive(int iterations) {
        double result = 0;
        for (int i = 0; i < iterations; i++) {
            result += Math.sin(i) * Math.cos(i);
        }
        // Prevent optimization
        if (result == Double.POSITIVE_INFINITY) {
            System.out.println("This won't happen");
        }
    }
}