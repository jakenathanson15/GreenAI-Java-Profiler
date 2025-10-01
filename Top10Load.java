package demo;

public class Top10Load {
    public static void main(String[] args) {
        System.out.println("Starting Top10Load with DRAMATIC energy progression and higher baseline...");
        
        // Run each method multiple times to ensure sampling
        for (int i = 0; i < 3; i++) {
            System.out.println("Iteration " + (i+1) + "/3");
            
            // Run all methods with clear energy progression
            work1(); System.out.println("- work1 completed (least energy)");
            work2(); System.out.println("- work2 completed");
            work3(); System.out.println("- work3 completed");
            work4(); System.out.println("- work4 completed");
            work5(); System.out.println("- work5 completed");
            work6(); System.out.println("- work6 completed");
            work7(); System.out.println("- work7 completed");
            work8(); System.out.println("- work8 completed");
            work9(); System.out.println("- work9 completed");
            work10(); System.out.println("- work10 completed (most energy)");
            
            // Short pause between iterations
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        
        System.out.println("Top10Load completed");
    }
    
    // Methods with dramatically increased baseline but still maintaining progression
    public static void work1() { computeIntensive(500_000); }     // 5x higher baseline
    public static void work2() { computeIntensive(1_000_000); }   // 2x work1
    public static void work3() { computeIntensive(2_000_000); }   // 2x work2
    public static void work4() { computeIntensive(3_500_000); }   // ~1.75x work3
    public static void work5() { computeIntensive(5_500_000); }   // ~1.6x work4
    public static void work6() { computeIntensive(8_000_000); }   // ~1.45x work5
    public static void work7() { computeIntensive(11_000_000); }  // ~1.4x work6
    public static void work8() { computeIntensive(15_000_000); }  // ~1.35x work7
    public static void work9() { computeIntensive(20_000_000); }  // ~1.33x work8
    public static void work10() { computeIntensive(25_000_000); } // 1.25x work9
    
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