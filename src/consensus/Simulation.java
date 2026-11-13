package consensus;

import io.jbotsim.contrib.messaging.AsyncMessageEngine;
import io.jbotsim.core.Link;
import io.jbotsim.core.Node;
import io.jbotsim.core.Topology;

import java.util.ArrayList;
import java.util.List;

/** Builds the simulated network: a complete graph of nodes with asynchronous FIFO channels. */
public final class Simulation {

    /** Average message delay, in clock ticks. */
    public static final int AVERAGE_DELAY = 5;

    /** Print every node event to stdout. */
    static boolean verbose = true;

    private Simulation() {
    }

    public static Topology createNetwork(int nodeCount) {
        return createNetwork(nodeCount, AVERAGE_DELAY);
    }

    public static Topology createNetwork(int nodeCount, int averageDelay) {
        Topology topology = new Topology();
        topology.setDefaultNodeModel(ConsensusNode.class);
        topology.disableWireless();

        List<Node> nodes = new ArrayList<>();
        double radius = 40 + 25 * nodeCount;
        for (int i = 0; i < nodeCount; i++) {
            double angle = 2 * Math.PI * i / nodeCount - Math.PI / 2;
            ConsensusNode node = new ConsensusNode();
            topology.addNode(300 + radius * Math.cos(angle), 250 + radius * Math.sin(angle), node);
            node.setID(i);
            nodes.add(node);
        }

        for (int i = 0; i < nodeCount; i++) {
            for (int j = i + 1; j < nodeCount; j++) {
                topology.addLink(new Link(nodes.get(i), nodes.get(j), Link.Orientation.UNDIRECTED));
            }
        }

        topology.setMessageEngine(new LosslessAsyncMessageEngine(topology, averageDelay));
        return topology;
    }

    /**
     * Asynchronous FIFO channels with random (exponential) delays.
     *
     * JBotSim 1.2.0 schedules a message at {@code currentTime - 1 + delay}; when the
     * random delay rounds down to 0 (about 10% of messages with a mean of 5 ticks),
     * that date is already in the past and the message is silently dropped.
     * The channels of the model are reliable, so the delay is clamped to 1 tick.
     */
    static class LosslessAsyncMessageEngine extends AsyncMessageEngine {

        LosslessAsyncMessageEngine(Topology topology, int averageDelay) {
            super(topology, averageDelay, Type.FIFO);
        }

        @Override
        protected int computeDelayFunction(int lambda) {
            return Math.max(1, super.computeDelayFunction(lambda));
        }
    }
}
