package kairon.ui.swing.behaviorgraph;

import org.junit.jupiter.api.Test;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.event.WindowEvent;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

final class BehaviorGraphOccurrenceDialogTest {

    @Test
    void initialBoundsAreSizedAndClampedToUsableScreen() {
        Rectangle normal = BehaviorGraphOccurrenceDialog.initialBounds(
                new Rectangle(100, 100, 1320, 820),
                new Rectangle(0, 0, 1920, 1040)
        );
        assertEquals(new Dimension(820, 620), normal.getSize());
        assertEquals(350, normal.x);
        assertEquals(200, normal.y);

        Rectangle constrained =
                BehaviorGraphOccurrenceDialog.initialBounds(
                        new Rectangle(0, 0, 700, 500),
                        new Rectangle(0, 0, 700, 500)
                );
        assertTrue(constrained.width >= 600);
        assertTrue(constrained.height >= 400);
        assertTrue(constrained.width <= 700);
        assertTrue(constrained.height <= 500);

        Rectangle offScreen =
                BehaviorGraphOccurrenceDialog.initialBounds(
                        new Rectangle(3_000, 2_000, 1320, 820),
                        new Rectangle(0, 0, 1920, 1040)
                );
        assertEquals(1_100, offScreen.x);
        assertEquals(420, offScreen.y);
        assertTrue(new Rectangle(0, 0, 1920, 1040)
                .contains(offScreen));
    }

    @Test
    void dialogIsOwnerBoundModelessReusableAndHiddenOnClose()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            JFrame owner = new JFrame("Owner");
            BehaviorGraphOccurrenceDialog dialog = null;
            try {
                owner.setBounds(100, 100, 1000, 700);
                BehaviorGraphOccurrenceInspector inspector =
                        new BehaviorGraphOccurrenceInspector();
                AtomicInteger hiddenCount = new AtomicInteger();
                dialog = new BehaviorGraphOccurrenceDialog(
                        owner,
                        inspector,
                        hiddenCount::incrementAndGet
                );

                assertSame(owner, dialog.getOwner());
                assertEquals(
                        "Event Occurrences",
                        dialog.getTitle()
                );
                assertEquals(
                        Dialog.ModalityType.MODELESS,
                        dialog.getModalityType()
                );
                assertEquals(
                        WindowConstants.HIDE_ON_CLOSE,
                        dialog.getDefaultCloseOperation()
                );
                assertTrue(dialog.isResizable());
                assertEquals(
                        new Dimension(600, 400),
                        dialog.getMinimumSize()
                );

                dialog.showForExplicitSelection();
                assertTrue(dialog.isVisible());
                Rectangle userBounds = new Rectangle(
                        dialog.getX() + 10,
                        dialog.getY() + 10,
                        700,
                        500
                );
                dialog.setBounds(userBounds);
                dialog.dispatchEvent(new WindowEvent(
                        dialog,
                        WindowEvent.WINDOW_CLOSING
                ));
                assertFalse(dialog.isVisible());
                assertEquals(1, hiddenCount.get());

                dialog.showForExplicitSelection();
                assertTrue(dialog.isVisible());
                assertEquals(userBounds, dialog.getBounds());
                assertSame(inspector, dialog.inspector());

                owner.dispose();
                assertFalse(dialog.isDisplayable());
            } finally {
                if (dialog != null) {
                    dialog.dispose();
                }
                owner.dispose();
            }
        });
    }
}
