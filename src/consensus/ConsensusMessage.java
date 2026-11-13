package consensus;

/**
 * Message exchanged by the consensus algorithm.
 *
 * @param type      kind of message
 * @param round     round the message belongs to
 * @param value     estimate, proposal or decided value
 * @param timestamp round in which {@code value} was last adopted (only used by ESTIMATE)
 */
public record ConsensusMessage(Type type, int round, int value, int timestamp) {

    public enum Type {
        ESTIMATE,   // participant -> coordinator: my current value and its timestamp
        PROPOSE,    // coordinator -> all: value chosen for this round
        ACK,        // participant -> coordinator: proposal adopted
        NACK,       // participant -> coordinator: I suspected you and moved on
        DECIDE      // reliable broadcast of the decided value
    }
}
