package consensus;

import io.jbotsim.core.Color;
import io.jbotsim.core.Message;
import io.jbotsim.core.Node;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Node running a heartbeat failure detector with adaptive timeouts.
 *
 * Every HEARTBEAT_PERIOD ticks the node sends a heartbeat to all its neighbours.
 * A neighbour that stays silent for longer than its timeout becomes suspected.
 * If a heartbeat later arrives from a suspected neighbour, the suspicion was a
 * mistake: the neighbour is trusted again and its timeout is increased.
 *
 * In an asynchronous network such mistakes cannot be avoided, but growing the
 * timeouts makes them rarer over time, so the detector ends up suspecting only
 * crashed nodes (eventually perfect). That is what the consensus needs to terminate.
 *
 * Clicking a node in the viewer crashes it (crash-stop failure model).
 */
public class HeartbeatNode extends Node {

    static final int HEARTBEAT_PERIOD = 10;
    static final int INITIAL_TIMEOUT = 20;
    static final int TIMEOUT_INCREMENT = 10;
    private static final String HEARTBEAT = "HEARTBEAT";

    protected boolean alive;
    private int ticks;

    private final Map<Node, Integer> lastHeard = new HashMap<>();
    private final Map<Node, Integer> timeout = new HashMap<>();
    private final Set<Node> suspected = new HashSet<>();

    @Override
    public void onStart() {
        alive = true;
        ticks = 0;
        lastHeard.clear();
        timeout.clear();
        suspected.clear();
        for (Node node : getTopology().getNodes()) {
            if (node != this) {
                lastHeard.put(node, 0);
                timeout.put(node, INITIAL_TIMEOUT);
            }
        }
    }

    @Override
    public void onSelection() {
        crash();
    }

    public void crash() {
        if (!alive) {
            return;
        }
        alive = false;
        setColor(Color.RED);
        setLabel("crashed");
        log("crashed");
    }

    public boolean isAlive() {
        return alive;
    }

    protected boolean isTrusted(Node node) {
        return !suspected.contains(node);
    }

    @Override
    public void onClock() {
        if (!alive) {
            return;
        }
        ticks++;

        if (ticks % HEARTBEAT_PERIOD == 0) {
            sendAll(new Message(HEARTBEAT));
        }

        for (Node node : lastHeard.keySet()) {
            if (!suspected.contains(node) && ticks - lastHeard.get(node) > timeout.get(node)) {
                suspected.add(node);
                log("suspects node " + node.getID());
            }
        }
    }

    @Override
    public void onMessage(Message message) {
        if (!alive || !HEARTBEAT.equals(message.getContent())) {
            return;
        }
        Node sender = message.getSender();
        lastHeard.put(sender, ticks);
        if (suspected.remove(sender)) {
            int newTimeout = timeout.merge(sender, TIMEOUT_INCREMENT, Integer::sum);
            log("wrongly suspected node " + sender.getID() + ", timeout raised to " + newTimeout);
        }
    }

    protected void log(String text) {
        if (Simulation.verbose) {
            System.out.printf("[t=%5d] node %d: %s%n", getTopology().getTime(), getID(), text);
        }
    }
}
