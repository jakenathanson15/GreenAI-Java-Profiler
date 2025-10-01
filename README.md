# GreenAI-Java-Profiler

This project implements a method-level energy profiling system for Java applications, combining hardware power measurements with JVM execution sampling. The architecture leverages two independent data collection mechanisms that are correlated post-execution:
Java Flight Recorder (JFR): A low-overhead JVM profiling tool that captures execution samples showing which methods are active at specific timestamps
Intel Power Gadget: A hardware-level monitoring tool that measures CPU power consumption with high temporal resolution
