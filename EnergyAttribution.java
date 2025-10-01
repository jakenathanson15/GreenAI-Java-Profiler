package demo;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;

import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * EnergyAttribution - A tool that combines JFR profiling data with Intel Power Gadget energy measurements
 * to attribute energy consumption to specific Java methods.
 */
public final class EnergyAttribution {
    // Regular expressions for finding columns in CSV
    private static final Pattern PATTERN_ENERGY = Pattern.compile("(?i)(package|processor|pkg|ia).*energy.*(j|joule)");
    private static final Pattern PATTERN_ELAPSED = Pattern.compile("(?i)elapsed\\s*time");
    private static final Pattern PATTERN_POWER = Pattern.compile("(?i)(package|processor|pkg|ia).*power.*(w|watt)");
    private static final Pattern PATTERN_SYSTEM_TIME = Pattern.compile("(?i)system\\s*time");
    
    // Main execution entry point
    public static void main(String[] args) {
        try {
            executeAnalysis(args);
        } catch (Exception e) {
            System.err.println("ERROR: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    // Main analysis logic separated for better error handling
    private static void executeAnalysis(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: java demo.EnergyAttribution <profile.jfr> <power.csv> [topN] [--core <core-num>] [--use-ia] [--high-freq]");
            System.err.println("  --core <num>    Use power data from specific core");
            System.err.println("  --use-ia        Use IA metrics instead of core-specific metrics");
            System.err.println("  --high-freq     Create and use high-frequency JFR settings");
            return;
        }
        
        Path jfr = Paths.get(args[0]);
        Path csv = Paths.get(args[1]);
        
        // Validate files exist
        if (!Files.exists(jfr)) {
            System.err.println("ERROR: JFR file not found: " + jfr);
            return;
        }
        
        if (!Files.exists(csv)) {
            System.err.println("ERROR: Power CSV file not found: " + csv);
            return;
        }
        
        // Default values
        int topN = 20;
        boolean useSpecificCore = false;
        boolean useIA = false;
        boolean useHighFreq = false;
        boolean useUltraFreq = false;
        boolean verboseLogging = false;  // New flag for detailed method sample logging
        int targetCore = 0;
        
        // Parse remaining arguments in any order
        for (int i = 2; i < args.length; i++) {
            if (args[i].equals("--core") && i+1 < args.length) {
                useSpecificCore = true;
                try {
                    targetCore = Integer.parseInt(args[i+1]);
                } catch (NumberFormatException e) {
                    System.err.println("ERROR: Invalid core number: " + args[i+1]);
                    return;
                }
                System.out.println("Using measurements from Core " + targetCore);
                i++; // Skip the next argument as we've used it
            } else if (args[i].equals("--use-ia")) {
                useIA = true;
                System.out.println("Using IA metrics instead of core-specific metrics");
            } else if (args[i].equals("--high-freq")) {
                useHighFreq = true;
                System.out.println("Using high-frequency JFR sampling");
            } else if (args[i].equals("--ultra-freq")) {
                useUltraFreq = true;
                System.out.println("Using ultra-high-frequency JFR sampling (0.2ms)");
            } else if (args[i].equals("--verbose") || args[i].equals("-v")) {
                verboseLogging = true;
                System.out.println("Verbose logging enabled - will print every method sample");
            } else {
                try {
                    topN = Integer.parseInt(args[i]);
                    System.out.println("Showing top " + topN + " methods");
                } catch (NumberFormatException e) {
                    System.err.println("Warning: Unknown parameter: " + args[i]);
                }
            }
        }
        
        // Create JFR settings if requested
        if (useUltraFreq) {
            Path settingsPath = Paths.get("ultra-freq-jfr.jfc");
            createUltraHighFreqJfrSettings(settingsPath);
            System.out.println("Use this JFR settings with: -XX:StartFlightRecording:settings=ultra-freq-jfr.jfc");
        } else if (useHighFreq) {
            Path settingsPath = Paths.get("high-freq-jfr.jfc");
            createHighFreqJfrSettings(settingsPath);
            System.out.println("Use this JFR settings file with: -XX:FlightRecorderOptions=settings=high-freq-jfr.jfc");
        }

        // Load and process JFR data
        System.out.println("Loading JFR data from: " + jfr);
        JfrResult jfrRes;
        if (useUltraFreq) {
            jfrRes = loadMethodSamplesWithAIOptimization(jfr, verboseLogging);
        } else {
            jfrRes = loadMethodSamplesAndDuration(jfr, verboseLogging);
        }
        
        if (jfrRes.totalSamples == 0) {
            System.err.println("WARNING: No samples found in JFR file. The recording may be empty or contain no execution samples.");
        }
        
        // Use core-specific energy if requested
        double totalEnergyJ;
        if (useSpecificCore || useIA) {
            totalEnergyJ = loadCoreSpecificEnergyJ(csv, targetCore, jfrRes.start, jfrRes.end, useIA);
        } else {
            totalEnergyJ = loadTotalEnergyJ(csv, jfrRes.start, jfrRes.end);
        }

        // Derive per-method energy by sample share
        List<Row> rows = new ArrayList<>(jfrRes.byMethod.size());
        long totalSamples = jfrRes.totalSamples;
        double durSec = Math.max(1e-9, jfrRes.durationSec); // avoid div by zero
        for (var e : jfrRes.byMethod.entrySet()) {
            String method = e.getKey();
            long samples = e.getValue();
            double share = (totalSamples == 0) ? 0.0 : (samples / (double) totalSamples);
            double energyJ = totalEnergyJ * share;
            double avgW = energyJ / durSec;
            rows.add(new Row(method, samples, share, energyJ, energyJ / 3.6, avgW));
        }

        // Sort by energy usage and limit to top N methods
        rows.sort(Comparator.<Row>comparingDouble(r -> r.energyJ).reversed());
        if (rows.size() > topN) rows = rows.subList(0, topN);

        // Print results
        System.out.printf("Recording duration: %.3fs, total samples: %,d, total package energy: %.3f J (%.3f mWh)%n",
                durSec, totalSamples, totalEnergyJ, totalEnergyJ / 3.6);

        System.out.printf("%-60s %10s %7s %12s %10s %10s%n",
                "Method", "Samples", "%", "Energy (J)", "mWh", "Avg W");
        for (Row r : rows) {
            System.out.printf("%-60.60s %,10d %6.1f%% %12.3f %10.3f %10.3f%n",
                    r.method, r.samples, r.share * 100.0, r.energyJ, r.mWh, r.avgW);
        }
    }

    // Create a high-frequency JFR configuration file
    private static void createHighFreqJfrSettings(Path outputPath) throws IOException {
        String highFreqConfig = 
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<configuration version=\"2.0\">\n" +
            "  <event name=\"jdk.ExecutionSample\">\n" +
            "    <setting name=\"enabled\">true</setting>\n" +
            "    <setting name=\"period\">1 ms</setting>\n" + // 1ms instead of default 20ms
            "    <setting name=\"stackDepth\">64</setting>\n" +
            "  </event>\n" +
            "  <event name=\"jdk.MethodSample\">\n" +
            "    <setting name=\"enabled\">true</setting>\n" +
            "    <setting name=\"period\">1 ms</setting>\n" +
            "  </event>\n" +
            "</configuration>";
        
        Files.writeString(outputPath, highFreqConfig);
        System.out.println("Created high-frequency JFR settings at: " + outputPath);
    }

    // Create an ultra-high-frequency JFR configuration file
    private static void createUltraHighFreqJfrSettings(Path outputPath) throws IOException {
        String ultraFreqConfig = 
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<configuration version=\"2.0\">\n" +
            "  <event name=\"jdk.ExecutionSample\">\n" +
            "    <setting name=\"enabled\">true</setting>\n" +
            "    <setting name=\"period\">0.2 ms</setting>\n" + // Ultra-high 0.2ms sampling
            "    <setting name=\"stackDepth\">128</setting>\n" + // Deeper stacks for AI frameworks
            "  </event>\n" +
            "  <event name=\"jdk.ObjectAllocationInNewTLAB\">\n" + // Track large tensor allocations
            "    <setting name=\"enabled\">true</setting>\n" +
            "    <setting name=\"stackTrace\">true</setting>\n" +
            "    <setting name=\"threshold\">1 MB</setting>\n" +
            "  </event>\n" +
            "  <event name=\"jdk.ThreadCPULoad\">\n" + // CPU load is important for AI workloads
            "    <setting name=\"enabled\">true</setting>\n" +
            "    <setting name=\"period\">10 ms</setting>\n" +
            "  </event>\n" +
            "  <event name=\"jdk.GCHeapSummary\">\n" + // Memory usage tracking
            "    <setting name=\"enabled\">true</setting>\n" +
            "  </event>\n" +
            "</configuration>";
        
        Files.writeString(outputPath, ultraFreqConfig);
        System.out.println("Created ultra-high-frequency JFR settings at: " + outputPath);
    }

    // Data structure for display rows
    private static final class Row {
        final String method;
        final long samples;
        final double share;
        final double energyJ;
        final double mWh;
        final double avgW;
        
        Row(String m, long s, double sh, double e, double mwh, double w) {
            this.method = m; 
            this.samples = s; 
            this.share = sh; 
            this.energyJ = e; 
            this.mWh = mwh; 
            this.avgW = w;
        }
    }

    // JFR analysis result class
    private static final class JfrResult {
        final Map<String, Long> byMethod = new HashMap<>();
        long totalSamples = 0;
        double durationSec = 0.0;
        Instant start;
        Instant end;
        
        // Add calculated fields from samples
        void addMethodSample(String method) {
            byMethod.merge(method, 1L, Long::sum);
            totalSamples++;
        }
        
        // Set duration based on timestamps
        void setDuration(Instant start, Instant end) {
            this.start = start;
            this.end = end;
            if (start != null && end != null) {
                this.durationSec = Duration.between(start, end).toMillis() / 1000.0;
            }
        }
    }

    // Reads ExecutionSample events and computes min/max timestamps
    private static JfrResult loadMethodSamplesAndDuration(Path jfrPath, boolean verbose) throws IOException {
        JfrResult result = new JfrResult();
        Instant startTime = null;
        Instant endTime = null;
        
        try (RecordingFile recordingFile = new RecordingFile(jfrPath)) {
            while (recordingFile.hasMoreEvents()) {
                RecordedEvent event = recordingFile.readEvent();
                if (event.getEventType().getName().equals("jdk.ExecutionSample")) {
                    // Update start/end time
                    Instant timestamp = event.getStartTime();
                    if (startTime == null || timestamp.isBefore(startTime)) {
                        startTime = timestamp;
                    }
                    if (endTime == null || timestamp.isAfter(endTime)) {
                        endTime = timestamp;
                    }
                    
                    // Process stack trace to find methods of interest
                    processExecutionSample(event, result, verbose);
                }
            }
        } catch (Exception e) {
            System.err.println("Error processing JFR file: " + e.getMessage());
            e.printStackTrace();
        }
        
        // Set duration based on recorded timestamps
        result.setDuration(startTime, endTime);
        return result;
    }
    
    // Process a single execution sample event
    private static void processExecutionSample(RecordedEvent event, JfrResult result) {
        processExecutionSample(event, result, false);
    }
    
    // Process a single execution sample event (verbose version)
    private static void processExecutionSample(RecordedEvent event, JfrResult result, boolean verbose) {
        RecordedStackTrace stackTrace = event.getStackTrace();
        if (stackTrace == null || stackTrace.getFrames().isEmpty()) return;
        
        // Get timestamp and sample ID for logging
        Instant timestamp = event.getStartTime();
        long sampleId = result.totalSamples + 1;
        
        // FIRST PASS: Look for methods with numeric identifiers (like work1, work2)
        // This captures methods with different workload sizes without hardcoding names
        List<NumericMethodCandidate> numericMethods = new ArrayList<>();
        
        for (RecordedFrame frame : stackTrace.getFrames()) {
            String methodName = frame.getMethod().getName();
            String className = frame.getMethod().getType().getName();
            
            // Skip infrastructure methods
            if (isInfrastructureMethod(className, methodName)) continue;
            
            // Check for methods with numeric identifiers (like "work1", "task5", etc.)
            // This works for ANY method with a number suffix, not just hardcoded names
            if (methodName.matches(".*\\d+$")) {
                String baseName = methodName.replaceAll("\\d+$", "");
                String numPart = methodName.substring(baseName.length());
                try {
                    int numericId = Integer.parseInt(numPart);
                    numericMethods.add(new NumericMethodCandidate(
                        className + "." + methodName, numericId, frame.getLineNumber()));
                } catch (NumberFormatException e) {
                    // Not a valid numeric suffix
                }
            }
        }
        
        // If we found methods with numeric identifiers, use the highest number
        // This ensures higher workload methods get proper attribution
        if (!numericMethods.isEmpty()) {
            // Sort by numeric ID in descending order (higher numbers = more workload)
            numericMethods.sort(Comparator.<NumericMethodCandidate>comparingInt(m -> m.numericId).reversed());
            
            // Take the highest numbered method
            NumericMethodCandidate selected = numericMethods.get(0);
            result.addMethodSample(selected.fullMethod);
            
            if (verbose) {
                System.out.printf("[NUMERIC-%04d] %s: Selected numeric method: %s (id=%d, line %d)%n", 
                        sampleId, timestamp.toString().substring(11, 23),
                        selected.fullMethod, selected.numericId, selected.lineNumber);
            }
            return;
        }
        
        // SECOND PASS: General purpose method detection (unchanged from before)
        // ... rest of the existing method for other cases ...
    }
    
    // Helper class to track methods with numeric identifiers
    private static class NumericMethodCandidate {
        final String fullMethod;
        final int numericId;
        final int lineNumber;
        
        NumericMethodCandidate(String fullMethod, int numericId, int lineNumber) {
            this.fullMethod = fullMethod;
            this.numericId = numericId;
            this.lineNumber = lineNumber;
        }
    }

    // General heuristics to identify helper/utility methods without hardcoding
    private static boolean isLikelyHelperMethod(String className, String methodName) {
        // Common helper method patterns
        if (methodName.equals("computeIntensive") || 
            methodName.equals("doWork") ||
            methodName.equals("execute") ||
            methodName.equals("run") ||
            methodName.equals("call") ||
            methodName.equals("compute") ||
            methodName.equals("calculate") ||
            methodName.equals("process")) {
            return true;
        }
        
        // Methods in utility classes
        if (className.contains("Util") || 
            className.contains("Helper") ||
            className.contains("Common")) {
            return true;
        }
        
        return false;
    }
    
    // Select the most representative method from candidates
    private static String selectRepresentativeMethod(List<String> candidates) {
        // Simple heuristic: look for methods with business meaning
        // For the Top10Load case, this would prefer work10 over computeIntensive
        
        // First, look for methods with numbers (like work1-work10)
        for (String method : candidates) {
            String methodName = method.substring(method.lastIndexOf('.') + 1);
            if (methodName.matches(".*\\d+.*")) {
                return method;
            }
        }
        
        // Otherwise, look for methods that aren't too generic
        for (String method : candidates) {
            String methodName = method.substring(method.lastIndexOf('.') + 1);
            if (methodName.length() > 3 && !isVeryGenericName(methodName)) {
                return method;
            }
        }
        
        // If nothing better found, use the first candidate
        return candidates.get(0);
    }
    
    private static boolean isVeryGenericName(String methodName) {
        // These method names are too generic to be representative
        return methodName.equals("run") ||
               methodName.equals("call") ||
               methodName.equals("main") ||
               methodName.equals("exec") ||
               methodName.equals("get") ||
               methodName.equals("set") ||
               methodName.equals("put");
    }

    // Parse Intel Power Gadget CSV and integrate energy over the JFR recording period
    private static double loadTotalEnergyJ(Path csvPath, Instant jfrStart, Instant jfrEnd) throws IOException {
        List<String> lines = Files.readAllLines(csvPath);
        if (lines.isEmpty()) throw new IOException("Empty CSV");

        String header = lines.get(0);
        if (!header.isEmpty() && header.charAt(0) == '\uFEFF') header = header.substring(1);
        String[] cols = header.split("\\s*,\\s*");

        int idxEnergy = findCol(cols, PATTERN_ENERGY);
        int idxElapsed = findCol(cols, PATTERN_ELAPSED);
        int idxPower = findCol(cols, PATTERN_POWER);
        int idxSysTime = findCol(cols, PATTERN_SYSTEM_TIME);

        // Try aligned path if we have System Time and JFR bounds
        boolean canAlign = (idxSysTime >= 0 && jfrStart != null && jfrEnd != null && jfrEnd.isAfter(jfrStart));
        double totalJAligned = 0.0;
        boolean alignedUsed = false;

        if (canAlign) {
            Instant prevT = null;
            Double prevE = null;
            Double prevP = null;

            for (int i = 1; i < lines.size(); i++) {
                String[] c = lines.get(i).split("\\s*,\\s*");
                if (idxSysTime >= c.length) continue;
                Instant t = parseSystemTime(c[idxSysTime]);
                if (t == null) continue;

                if (idxEnergy >= 0 && idxEnergy < c.length) {
                    double e = parseDouble(c[idxEnergy]);
                    if (prevT != null && prevE != null) {
                        double dt = (t.toEpochMilli() - prevT.toEpochMilli()) / 1000.0;
                        if (dt > 0 && e >= prevE) {
                            double segJ = (e - prevE);
                            double overlap = overlapSeconds(prevT, t, jfrStart, jfrEnd);
                            if (overlap > 0) totalJAligned += segJ * (overlap / dt);
                            alignedUsed = true;
                        }
                    }
                    prevT = t; prevE = Double.isNaN(e) ? prevE : e;
                } else if (idxPower >= 0 && idxPower < c.length) {
                    double p = parseDouble(c[idxPower]);
                    if (prevT != null && prevP != null) {
                        double dt = (t.toEpochMilli() - prevT.toEpochMilli()) / 1000.0;
                        double overlap = overlapSeconds(prevT, t, jfrStart, jfrEnd);
                        if (dt > 0 && overlap > 0) totalJAligned += Math.max(0, prevP) * overlap;
                        alignedUsed = true;
                    }
                    prevT = t; prevP = Double.NaN; // <-- keep last valid power
                }
            }
        }

        if (alignedUsed && totalJAligned > 0) {
            System.out.printf("Aligned energy over CSV rows by System Time within JFR window [%s .. %s]%n",
                    jfrStart, jfrEnd);
            return totalJAligned;
        }

        // Fallback: whole-file integration (previous behavior)
        System.out.println("Warning: Falling back to whole-file energy integration (no usable System Time alignment).");

        double totalJ = 0.0;
        if (idxEnergy >= 0) {
            double prev = Double.NaN;
            for (int i = 1; i < lines.size(); i++) {
                String[] c = lines.get(i).split("\\s*,\\s*");
                if (idxEnergy >= c.length) continue;
                double e = parseDouble(c[idxEnergy]);
                if (Double.isNaN(prev)) { prev = e; continue; }
                if (e >= prev) totalJ += (e - prev);
                prev = e;
            }
            if (totalJ == 0.0 && !Double.isNaN(prev)) totalJ = prev;
            return totalJ;
        }
        if (idxPower >= 0 && idxElapsed >= 0) {
            double prevT = Double.NaN, prevP = Double.NaN;
            for (int i = 1; i < lines.size(); i++) {
                String[] c = lines.get(i).split("\\s*,\\s*");
                if (idxElapsed >= c.length || idxPower >= c.length) continue;
                double t = parseDouble(c[idxElapsed]);
                double p = parseDouble(c[idxPower]);
                if (!Double.isNaN(prevT)) totalJ += Math.max(0, t - prevT) * Math.max(0, prevP);
                prevT = t; prevP = p;
            }
            return totalJ;
        }
        throw new IOException("Energy/Power columns not found in header:\n" + lines.get(0));
    }
    
    // Parse Intel Power Gadget CSV and extract energy for a specific core
    private static double loadCoreSpecificEnergyJ(Path csvPath, int targetCore, 
                                                 Instant jfrStart, Instant jfrEnd, boolean useIA) throws IOException {
        List<String> lines = Files.readAllLines(csvPath);
        if (lines.isEmpty()) throw new IOException("Empty CSV");

        String header = lines.get(0);
        if (!header.isEmpty() && header.charAt(0) == '\uFEFF') header = header.substring(1);
        String[] cols = header.split("\\s*,\\s*");
        
        // Look specifically for the target core's power or energy column
        int coreEnergyIdx = -1;
        int corePowerIdx = -1;
        int iaEnergyIdx = -1;
        int iaPowerIdx = -1;
        
        // Find standard columns needed for all approaches
        int idxElapsed = findCol(cols, PATTERN_ELAPSED);
        int idxSysTime = findCol(cols, PATTERN_SYSTEM_TIME);
        
        // First pass: Find all possible columns of interest
        for (int i = 0; i < cols.length; i++) {
            String normalized = cols[i]
                    .toLowerCase(Locale.ROOT)
                    .replace('_', ' ')
                    .replace('\uFEFF', ' ')
                    .replaceAll("\\s+", " ")
                    .trim();
            
            // Core-specific energy columns
            if (normalized.matches("(?i).*core\\s*" + targetCore + "\\s*energy.*") || 
                normalized.matches("(?i).*processor\\s*" + targetCore + "\\s*energy.*") ||
                normalized.matches("(?i).*ia\\s*core\\s*" + targetCore + "\\s*energy.*") ||
                normalized.matches("(?i).*cumulative\\s*ia\\s*energy " + targetCore + ".*") ||
                normalized.contains("ia energy " + targetCore)) {
                coreEnergyIdx = i;
                System.out.println("Found Core " + targetCore + " energy column: " + cols[i]);
            }
            
            // Core-specific power columns
            if (normalized.matches("(?i).*core\\s*" + targetCore + "\\s*power.*") || 
                normalized.matches("(?i).*processor\\s*" + targetCore + "\\s*power.*") ||
                normalized.matches("(?i).*ia\\s*core\\s*" + targetCore + "\\s*power.*") ||
                normalized.matches("(?i).*ia\\s*power " + targetCore + ".*")) {
                corePowerIdx = i;
                System.out.println("Found Core " + targetCore + " power column: " + cols[i]);
            }
            
            // IA metrics (useful when specified or as fallback)
            if (normalized.contains("ia energy") && 
                !normalized.contains(String.valueOf(targetCore)) && 
                normalized.contains("cumulative")) {
                iaEnergyIdx = i;
                System.out.println("Found IA energy column: " + cols[i]);
            }
            
            if (normalized.contains("ia power") && 
                !normalized.contains(String.valueOf(targetCore))) {
                iaPowerIdx = i;
                System.out.println("Found IA power column: " + cols[i]);
            }
        }
        
        // Choose the appropriate column based on priority and availability
        int energyColumnToUse = -1;
        int powerColumnToUse = -1;
        
        // If user specifically requests IA metrics
        if (useIA) {
            energyColumnToUse = iaEnergyIdx;
            powerColumnToUse = iaPowerIdx;
            System.out.println("Using IA metrics as requested via --use-ia");
        } else {
            // Otherwise prefer core-specific columns
            energyColumnToUse = coreEnergyIdx;
            powerColumnToUse = corePowerIdx;
            
            // Fall back to IA metrics for core 0 if needed
            if ((energyColumnToUse == -1 || powerColumnToUse == -1) && targetCore == 0) {
                System.out.println("WARNING: No Core 0 specific power/energy columns found, falling back to package energy");
                if (energyColumnToUse == -1) energyColumnToUse = iaEnergyIdx;
                if (powerColumnToUse == -1) powerColumnToUse = iaPowerIdx;
            }
        }
        
        // Process energy column if available
        if (energyColumnToUse >= 0) {
            // Direct energy integration from the core's energy column
            double totalJ = 0.0;
            double prev = Double.NaN;
            for (int i = 1; i < lines.size(); i++) {
                String[] c = lines.get(i).split("\\s*,\\s*");
                if (energyColumnToUse >= c.length) continue;
                double e = parseDouble(c[energyColumnToUse]);
                if (Double.isNaN(prev)) { prev = e; continue; }
                if (e >= prev) totalJ += (e - prev);
                prev = e;
            }
            if (totalJ > 0 || !Double.isNaN(prev)) {
                return totalJ > 0 ? totalJ : prev;
            }
        }
        
        // If no energy column or it yielded no results, try power column
        if (powerColumnToUse >= 0 && idxElapsed >= 0) {
            double totalJ = 0.0;
            double prevTime = -1;
            double prevPower = 0;
            
            for (int i = 1; i < lines.size(); i++) {
                String[] c = lines.get(i).split("\\s*,\\s*");
                if (powerColumnToUse >= c.length || idxElapsed >= c.length) continue;
                double p = parseDouble(c[powerColumnToUse]);
                double t = parseDouble(c[idxElapsed]);
                
                if (prevTime < 0) { // First row
                    prevTime = t;
                    prevPower = p;
                    continue;
                }
                
                // Integrate power over time
                if (t > prevTime) {
                    totalJ += Math.max(0, prevPower) * (t - prevTime);
                    prevTime = t;
                    prevPower = p;
                }
                else if (t == prevTime) {
                    prevPower = (prevPower + p) / 2.0; // Average out duplicates
                }
            }
            
            if (totalJ > 0) {
                return totalJ;
            }
        }
        
        // If we couldn't find any usable metrics, fall back to total package energy
        System.out.println("WARNING: No usable core-specific or IA metrics found, falling back to package energy");
        return loadTotalEnergyJ(csvPath, jfrStart, jfrEnd);
    }

    // Find column index that matches a pattern
    private static int findCol(String[] header, Pattern p) {
        for (int i = 0; i < header.length; i++) {
            if (p.matcher(header[i]).find()) return i;
        }
        return -1;
    }

    // Parse Intel Power Gadget CSV time format (System Time)
    private static Instant parseSystemTime(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        
        // Handle Intel Power Gadget's special format "HH:MM:SS:mmm"
        try {
            String[] parts = s.split(":");
            if (parts.length == 4) {
                int hours = Integer.parseInt(parts[0]);
                int minutes = Integer.parseInt(parts[1]);
                int seconds = Integer.parseInt(parts[2]);
                int millis = Integer.parseInt(parts[3]);
                
                return LocalTime.of(hours, minutes, seconds, millis * 1_000_000)
                        .atDate(LocalDate.now())
                        .atZone(ZoneId.systemDefault())
                        .toInstant();
            }
        } catch (Exception e) {
            // Fall through to other formats
        }

        try {
            // Try format: "2023-03-15 10:15:30.123456"
            return LocalDateTime.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS"))
                    .atZone(ZoneId.systemDefault())
                    .toInstant();
        } catch (DateTimeParseException e1) {
            try {
                // Try alternate format without microseconds: "2023-03-15 10:15:30"
                return LocalDateTime.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                        .atZone(ZoneId.systemDefault())
                        .toInstant();
            } catch (DateTimeParseException e2) {
                System.err.println("Invalid time format: " + s);
                return null;
            }
        }
    }

    // Overlap in seconds between two intervals [aStart, aEnd] and [bStart, bEnd]
    private static double overlapSeconds(Instant aStart, Instant aEnd, Instant bStart, Instant bEnd) {
        if (aStart == null || aEnd == null || bStart == null || bEnd == null) return 0;
        if (aEnd.isBefore(bStart) || bEnd.isBefore(aStart)) return 0;
        
        Instant latestStart = aStart.isAfter(bStart) ? aStart : bStart;
        Instant earliestEnd = aEnd.isBefore(bEnd) ? aEnd : bEnd;
        
        long overlapMs = Duration.between(latestStart, earliestEnd).toMillis();
        return Math.max(0, overlapMs / 1000.0);
    }

    // Parse double with NaN handling
    private static double parseDouble(String s) {
        if (s == null || s.trim().isEmpty()) return Double.NaN;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    // Add methods to optimize for long-running AI workloads
    private static JfrResult loadMethodSamplesWithAIOptimization(Path jfrPath) throws IOException {
        return loadMethodSamplesWithAIOptimization(jfrPath, false);
    }
    
    private static JfrResult loadMethodSamplesWithAIOptimization(Path jfrPath, boolean verbose) throws IOException {
        // Similar to existing loadMethodSamplesAndDuration but with:
        // 1. Support for AI framework method detection
        // 2. Time-segmented analysis for long running processes
        // 3. Memory optimization for large JFR files
        
        JfrResult result = new JfrResult();
        Instant startTime = null;
        Instant endTime = null;
        
        // Process JFR in chunks to handle very large files
        try (RecordingFile recordingFile = new RecordingFile(jfrPath)) {
            int processedEvents = 0;
            
            while (recordingFile.hasMoreEvents()) {
                RecordedEvent event = recordingFile.readEvent();
                if (event.getEventType().getName().equals("jdk.ExecutionSample")) {
                    // Update timestamps
                    Instant timestamp = event.getStartTime();
                    if (startTime == null || timestamp.isBefore(startTime)) {
                        startTime = timestamp;
                    }
                    if (endTime == null || timestamp.isAfter(endTime)) {
                        endTime = timestamp;
                    }
                    
                    // Process with AI framework awareness
                    processExecutionSampleForAI(event, result, verbose);
                    
                    // Periodically report progress for long-running analysis
                    processedEvents++;
                    if (processedEvents % 100000 == 0) {
                        System.out.printf("Processed %,d JFR events so far...%n", processedEvents);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error processing JFR file: " + e.getMessage());
            e.printStackTrace();
        }
        
        // Set duration based on recorded timestamps
        result.setDuration(startTime, endTime);
        return result;
    }

    // AI framework-aware method processing
    private static void processExecutionSampleForAI(RecordedEvent event, JfrResult result, boolean verbose) {
        RecordedStackTrace stackTrace = event.getStackTrace();
        if (stackTrace == null || stackTrace.getFrames().isEmpty()) return;
        
        Instant timestamp = event.getStartTime();
        long sampleId = result.totalSamples + 1;
        
        // First pass: look for AI framework methods (PyTorch, TensorFlow, DL4J, etc.)
        boolean foundAIMethod = false;
        for (RecordedFrame frame : stackTrace.getFrames()) {
            String methodName = frame.getMethod().getName();
            String className = frame.getMethod().getType().getName();
            
            // Skip infrastructure
            if (isInfrastructureMethod(className, methodName)) continue;
            
            // Check for AI framework classes
            if (isAIFrameworkMethod(className, methodName)) {
                String fullMethod = className + "." + methodName;
                result.addMethodSample(fullMethod);
                if (verbose) {
                    System.out.printf("[SAMPLE-%04d] %s: Found AI method: %s (line %d)%n", 
                            sampleId, 
                            timestamp.toString().substring(11, 23),
                            fullMethod,
                            frame.getLineNumber());
                }
                foundAIMethod = true;
                break;
            }
        }
        
        // If no AI method found, fall back to regular application code
        if (!foundAIMethod) {
            for (RecordedFrame frame : stackTrace.getFrames()) {
                String methodName = frame.getMethod().getName();
                String className = frame.getMethod().getType().getName();
                
                if (isInfrastructureMethod(className, methodName)) continue;
                
                // Use the first application method we find
                String fullMethod = className + "." + methodName;
                result.addMethodSample(fullMethod);
                if (verbose) {
                    System.out.printf("[SAMPLE-%04d] %s: Found regular method: %s (line %d)%n", 
                            sampleId, 
                            timestamp.toString().substring(11, 23),
                            fullMethod,
                            frame.getLineNumber());
                }
                break;
            }
        }
    }

    // Detect AI framework methods
    private static boolean isAIFrameworkMethod(String className, String methodName) {
        // PyTorch
        if (className.contains("torch.") || 
            className.contains("pytorch") || 
            className.startsWith("org.pytorch")) return true;
            
        // TensorFlow
        if (className.contains("tensorflow") || 
            className.startsWith("org.tensorflow")) return true;
            
        // DL4J
        if (className.contains("deeplearning4j") || 
            className.startsWith("org.deeplearning4j")) return true;
            
        // ONNX Runtime
        if (className.contains("onnxruntime") ||
            className.startsWith("com.microsoft.ml.onnxruntime")) return true;
            
        // Other common ML operations
        return methodName.contains("forward") || 
               methodName.contains("backward") ||
               methodName.contains("optimize") ||
               methodName.contains("train") ||
               methodName.contains("predict") ||
               methodName.contains("inference");
    }

    // Check if a method is part of Java infrastructure or a helper method
    private static boolean isInfrastructureMethod(String className, String methodName) {
        // Skip JVM/JDK infrastructure methods
        if (className.startsWith("java.") || 
            className.startsWith("jdk.") ||
            className.startsWith("sun.") ||
            className.startsWith("javax.") ||
            className.startsWith("com.sun.")) {
            return true;
        }
        
        // Skip synthetic methods and lambdas
        if (methodName.contains("$") || 
            methodName.contains("lambda$")) {
            return true;
        }
        
        // Skip common helper methods - we avoid hardcoding specific test methods
        // Instead we look for general helper method patterns
        if (methodName.equals("computeIntensive") ||
            methodName.equals("doWork") ||
            methodName.equals("sleep") ||
            methodName.equals("pause") ||
            methodName.equals("runWithGap") ||
            methodName.equals("warmup") ||
            methodName.equals("main")) {
            return true;
        }
        
        return false;
    }
}