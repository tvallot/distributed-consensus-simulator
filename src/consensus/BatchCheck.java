package consensus;

import io.jbotsim.core.Clock;
import io.jbotsim.core.ClockManager;
import io.jbotsim.core.Node;
import io.jbotsim.core.Topology;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.Set;

/**
 * Runs many headless simulations and checks the three consensus properties on each run:
 *   - agreement:   no two nodes decide differently (crashed nodes included);
 *   - validity:    the decided value is one of the initial values;
 *   - termination: every node that did not crash eventually decides.
 *
 * Failure scenarios, always with at most f = (n - 1) / 2 crashed nodes:
 *   - random:        0 to f random nodes crash at random times during the first rounds;
 *   - coordinators:  the coordinators of the first f rounds are dead from the start,
 *                    so the nodes have to detect them and rotate f times;
 *   - silent-decide: f random nodes crash right after deciding, before relaying the
 *                    decision, so the others must reach the same value on their own;
 *   - slow-network:  messages take as long as the detector timeout on average, which
 *                    causes many false suspicions and overlapping rounds, plus random crashes.
 *
 * Usage: BatchCheck [runsPerScenario] [-v]   (default: 1000 runs, -v prints every event)
 */
public class BatchCheck {

    private static final int[] NETWORK_SIZES = {3, 4, 5, 7, 9};
    private static final int MAX_CRASH_TIME = 30;
    private static final int MAX_TICKS = 20_000;

    private static final int SLOW_DELAY = HeartbeatNode.INITIAL_TIMEOUT;

    private enum Scenario { RANDOM, COORDINATORS, SILENT_DECIDE, SLOW_NETWORK }

    private record Crash(ConsensusNode node, int time) {
    }

    public static void main(String[] args) {
        int runs = args.length > 0 ? Integer.parseInt(args[0]) : 1000;
        Simulation.verbose = args.length > 1 && args[1].equals("-v");
        Random random = new Random();

        int totalFailures = 0;
        for (Scenario scenario : Scenario.values()) {
            Map<String, Integer> failures = new TreeMap<>();
            long totalTicks = 0;
            for (int run = 1; run <= runs; run++) {
                int n = NETWORK_SIZES[random.nextInt(NETWORK_SIZES.length)];
                int delay = scenario == Scenario.SLOW_NETWORK ? SLOW_DELAY : Simulation.AVERAGE_DELAY;
                Topology topology = Simulation.createNetwork(n, delay);
                topology.setClockModel(ManualClock.class);
                List<ConsensusNode> nodes = new ArrayList<>();
                for (Node node : topology.getNodes()) {
                    nodes.add((ConsensusNode) node);
                }

                int ticks = run(topology, nodes, planCrashes(scenario, nodes, random));
                totalTicks += ticks;

                String violated = check(nodes);
                if (violated != null) {
                    failures.merge(violated, 1, Integer::sum);
                    if (Simulation.verbose) {
                        System.out.printf("%s run %d (n=%d): %s violated%n", scenario, run, n, violated);
                        nodes.forEach(node -> System.out.println("    " + node));
                    }
                }
            }
            int failed = failures.values().stream().mapToInt(Integer::intValue).sum();
            System.out.printf("%-14s %d runs, %d failed%s, decision after %.0f ticks on average%n",
                    scenario.name().toLowerCase().replace('_', '-'), runs, failed,
                    failed == 0 ? "" : " " + failures, (double) totalTicks / runs);
            totalFailures += failed;
        }
        System.exit(totalFailures == 0 ? 0 : 1);
    }

    private static List<Crash> planCrashes(Scenario scenario, List<ConsensusNode> nodes, Random random) {
        int maxCrashes = (nodes.size() - 1) / 2;
        List<Crash> crashes = new ArrayList<>();
        switch (scenario) {
            case RANDOM, SLOW_NETWORK -> {
                List<ConsensusNode> shuffled = new ArrayList<>(nodes);
                Collections.shuffle(shuffled, random);
                int count = random.nextInt(maxCrashes + 1);
                for (int i = 0; i < count; i++) {
                    crashes.add(new Crash(shuffled.get(i), random.nextInt(MAX_CRASH_TIME)));
                }
            }
            case COORDINATORS -> {
                // Node i coordinates round i + 1.
                for (int i = 0; i < maxCrashes; i++) {
                    crashes.add(new Crash(nodes.get(i), 0));
                }
            }
            case SILENT_DECIDE -> {
                List<ConsensusNode> shuffled = new ArrayList<>(nodes);
                Collections.shuffle(shuffled, random);
                for (int i = 0; i < maxCrashes; i++) {
                    shuffled.get(i).crashBeforeRelaying = true;
                }
            }
        }
        return crashes;
    }

    /** Runs the simulation until every correct node decided; returns the number of ticks. */
    private static int run(Topology topology, List<ConsensusNode> nodes, List<Crash> crashes) {
        topology.start();
        ManualClock clock = ManualClock.current;

        int tick = 0;
        while (tick < MAX_TICKS && !allCorrectNodesDecided(nodes)) {
            for (Crash crash : crashes) {
                if (crash.time() == tick) {
                    crash.node().crash();
                }
            }
            clock.tick();
            tick++;
        }
        return tick;
    }

    private static boolean allCorrectNodesDecided(List<ConsensusNode> nodes) {
        for (ConsensusNode node : nodes) {
            if (node.isAlive() && node.getDecision() == null) {
                return false;
            }
        }
        return true;
    }

    /** Returns the name of the violated property, or null if the run is correct. */
    private static String check(List<ConsensusNode> nodes) {
        Set<Integer> initialValues = new HashSet<>();
        Set<Integer> decisions = new HashSet<>();
        for (ConsensusNode node : nodes) {
            initialValues.add(node.getInitialValue());
            if (node.getDecision() != null) {
                decisions.add(node.getDecision());
            } else if (node.isAlive()) {
                return "termination";
            }
        }
        if (decisions.size() > 1) {
            return "agreement";
        }
        if (!initialValues.containsAll(decisions)) {
            return "validity";
        }
        return null;
    }

    /** Clock driven by the caller instead of a timer thread, so runs are fast and reproducible in order. */
    public static class ManualClock extends Clock {
        static ManualClock current;
        private boolean running;

        public ManualClock(ClockManager manager) {
            super(manager);
            current = this;
        }

        void tick() {
            manager.onClock();
        }

        @Override public int getTimeUnit() { return 0; }
        @Override public void setTimeUnit(int timeUnit) { }
        @Override public boolean isRunning() { return running; }
        @Override public void start() { running = true; }
        @Override public void pause() { running = false; }
        @Override public void resume() { running = true; }
    }
}
