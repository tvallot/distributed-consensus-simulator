package consensus;

import io.jbotsim.core.Topology;
import io.jbotsim.ui.JViewer;

/**
 * Opens the interactive simulation.
 *
 * Usage: Main [nodeCount]   (default: 5)
 * Left-click a node to crash it.
 */
public class Main {

    public static void main(String[] args) {
        int nodeCount = args.length > 0 ? Integer.parseInt(args[0]) : 5;

        Topology topology = Simulation.createNetwork(nodeCount);
        topology.setTimeUnit(60); // slow enough to follow the rounds on screen
        JViewer viewer = new JViewer(topology);
        viewer.getJTopology().addBackgroundPainter(new LabelPainter());
        topology.addClockListener(() -> viewer.getJTopology().repaint());
        topology.start();
    }
}
