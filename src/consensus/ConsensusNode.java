package consensus;

import consensus.ConsensusMessage.Type;
import io.jbotsim.core.Color;
import io.jbotsim.core.Message;
import io.jbotsim.core.Node;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rotating coordinator consensus (Chandra-Toueg) on top of the heartbeat failure detector.
 *
 * Round r is led by node (r - 1) mod n:
 *   1. every node sends ESTIMATE(value, timestamp) to the coordinator;
 *   2. once it has a majority of estimates, the coordinator picks the value with the
 *      highest timestamp and broadcasts PROPOSE(value);
 *   3. each node waits for the proposal: if it arrives, it adopts the value
 *      (timestamp = r) and answers ACK; if it suspects the coordinator first,
 *      it answers NACK. Either way it moves to round r + 1;
 *   4. when the coordinator gets a majority of ACKs, the value is locked: it
 *      decides and reliably broadcasts DECIDE (every node relays it once).
 *
 * Safety holds as long as a majority of nodes never crash. Termination needs the
 * failure detector to eventually stop suspecting some correct node.
 */
public class ConsensusNode extends HeartbeatNode {

    private int initialValue;
    private int value;
    private int timestamp;
    private int round;
    private boolean waitingForProposal;
    private Integer decision;

    // Coordinator side, indexed by round.
    private final Map<Integer, List<ConsensusMessage>> estimates = new HashMap<>();
    private final Map<Integer, Integer> acks = new HashMap<>();
    private final Set<Integer> proposedRounds = new HashSet<>();

    // Proposals that arrived before this node reached their round.
    private final Map<Integer, ConsensusMessage> earlyProposals = new HashMap<>();

    // Fault injection (tests only): crash right after deciding, before relaying DECIDE.
    boolean crashBeforeRelaying;

    @Override
    public void onStart() {
        super.onStart();
        estimates.clear();
        acks.clear();
        proposedRounds.clear();
        earlyProposals.clear();
        decision = null;

        initialValue = (int) (Math.random() * 100);
        value = initialValue;
        timestamp = 0;
        log("initial value " + value);
        startRound(1);
    }

    public int getInitialValue() {
        return initialValue;
    }

    public Integer getDecision() {
        return decision;
    }

    // ---------------------------------------------------------------- rounds

    private void startRound(int newRound) {
        round = newRound;
        waitingForProposal = true;
        updateLabel();

        Node coordinator = coordinatorOf(round);
        log("round " + round + ", coordinator is node " + coordinator.getID());
        sendTo(coordinator, new ConsensusMessage(Type.ESTIMATE, round, value, timestamp));

        if (coordinator == this) {
            tryToPropose();
        }
        ConsensusMessage early = earlyProposals.remove(round);
        if (early != null) {
            onProposal(early);
        }
    }

    private Node coordinatorOf(int r) {
        int n = getTopology().getNodes().size();
        return getTopology().findNodeById((r - 1) % n);
    }

    private int majority() {
        return getTopology().getNodes().size() / 2 + 1;
    }

    @Override
    public void onClock() {
        super.onClock();
        if (!alive || decision != null || !waitingForProposal) {
            return;
        }
        Node coordinator = coordinatorOf(round);
        if (coordinator != this && !isTrusted(coordinator)) {
            log("suspects coordinator " + coordinator.getID() + ", leaving round " + round);
            waitingForProposal = false;
            sendTo(coordinator, new ConsensusMessage(Type.NACK, round, value, timestamp));
            startRound(round + 1);
        }
    }

    // -------------------------------------------------------------- messages

    @Override
    public void onMessage(Message message) {
        if (message.getContent() instanceof ConsensusMessage content) {
            handle(content);
        } else {
            super.onMessage(message);
        }
    }

    private void handle(ConsensusMessage m) {
        if (!alive) {
            return;
        }
        if (m.type() == Type.DECIDE) {
            decide(m.value());
            return;
        }
        if (decision != null) {
            return;
        }
        switch (m.type()) {
            case ESTIMATE -> {
                estimates.computeIfAbsent(m.round(), r -> new ArrayList<>()).add(m);
                tryToPropose();
            }
            case PROPOSE -> {
                if (m.round() == round && waitingForProposal) {
                    onProposal(m);
                } else if (m.round() > round) {
                    earlyProposals.put(m.round(), m);
                }
                // Proposals from rounds this node already left are ignored.
            }
            case ACK -> {
                int count = acks.merge(m.round(), 1, Integer::sum);
                if (count >= majority()) {
                    log("majority of ACKs for round " + m.round());
                    decide(m.value());
                }
            }
            case NACK -> log("got NACK for round " + m.round());
            default -> { }
        }
    }

    /** Coordinator: propose once a majority of estimates for the current round arrived. */
    private void tryToPropose() {
        List<ConsensusMessage> received = estimates.get(round);
        if (coordinatorOf(round) != this
                || proposedRounds.contains(round)
                || received == null
                || received.size() < majority()) {
            return;
        }
        ConsensusMessage best = received.get(0);
        for (ConsensusMessage estimate : received) {
            if (estimate.timestamp() > best.timestamp()) {
                best = estimate;
            }
        }
        proposedRounds.add(round);
        log("proposes " + best.value() + " for round " + round);
        broadcast(new ConsensusMessage(Type.PROPOSE, round, best.value(), round));
    }

    private void onProposal(ConsensusMessage proposal) {
        waitingForProposal = false;
        value = proposal.value();
        timestamp = proposal.round();
        log("adopts " + value + " in round " + round);

        Node coordinator = coordinatorOf(round);
        sendTo(coordinator, new ConsensusMessage(Type.ACK, round, value, timestamp));

        if (decision == null) {
            startRound(round + 1);
        }
    }

    private void decide(int decidedValue) {
        if (decision != null) {
            return;
        }
        decision = decidedValue;
        waitingForProposal = false;
        log("DECIDES " + decision);
        if (crashBeforeRelaying) {
            crash();
            return;
        }
        // Reliable broadcast: relay once so that every correct node gets it.
        sendAll(new Message(new ConsensusMessage(Type.DECIDE, round, decision, 0)));
        setColor(Color.GREEN);
        updateLabel();
    }

    // --------------------------------------------------------------- helpers

    /** Sends a message, handling messages to self locally (JBotSim has no self-links). */
    private void sendTo(Node destination, ConsensusMessage m) {
        if (destination == this) {
            handle(m);
        } else {
            send(destination, new Message(m));
        }
    }

    private void broadcast(ConsensusMessage m) {
        sendAll(new Message(m));
        handle(m);
    }

    @Override
    public String toString() {
        Node coordinator = coordinatorOf(round);
        return "node " + getID() + (alive ? "" : " (crashed)")
                + " round=" + round + " value=" + value + " ts=" + timestamp
                + " waiting=" + waitingForProposal + " decision=" + decision
                + " coordinator=" + coordinator.getID()
                + " trusted=" + isTrusted(coordinator)
                + " estimates=" + estimates.getOrDefault(round, List.of()).size();
    }

    private void updateLabel() {
        if (!alive) {
            return;
        }
        setLabel(decision != null ? "decided " + decision : "v=" + value + " r=" + round);
    }
}
