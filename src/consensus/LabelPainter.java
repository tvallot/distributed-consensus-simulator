package consensus;

import io.jbotsim.core.Node;
import io.jbotsim.core.Topology;
import io.jbotsim.ui.painting.BackgroundPainter;
import io.jbotsim.ui.painting.UIComponent;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * Draws each node's id and label next to it (JBotSim only shows labels on hover).
 * Registered as a background painter because node painters are clipped to the node icon.
 */
public class LabelPainter implements BackgroundPainter {

    private static final Font FONT = new Font(Font.SANS_SERIF, Font.BOLD, 13);

    @Override
    public void paintBackground(UIComponent component, Topology topology) {
        Graphics2D g = (Graphics2D) component.getComponent();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(FONT);
        g.setColor(Color.DARK_GRAY);

        for (Node node : topology.getNodes()) {
            String text = "node " + node.getID();
            if (node.getLabel() != null) {
                text += "  " + node.getLabel();
            }
            g.drawString(text, (int) node.getX() + 14, (int) node.getY() - 6);
        }
    }
}
