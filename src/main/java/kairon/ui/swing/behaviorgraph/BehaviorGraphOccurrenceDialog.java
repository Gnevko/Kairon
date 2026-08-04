package kairon.ui.swing.behaviorgraph;

import javax.swing.JDialog;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.GraphicsConfiguration;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Objects;

/**
 * Reusable modeless container for the active-episode occurrence inspector.
 */
final class BehaviorGraphOccurrenceDialog extends JDialog
        implements BehaviorGraphOccurrenceDialogHandle {

    static final Dimension DEFAULT_SIZE = new Dimension(820, 620);
    static final Dimension MINIMUM_SIZE = new Dimension(600, 400);

    private final BehaviorGraphOccurrenceInspector inspector;
    private final Runnable hiddenListener;

    private boolean initialBoundsApplied;
    private boolean focusTableWhenOccurrencesArrive;
    private boolean disposed;

    BehaviorGraphOccurrenceDialog(
            Window owner,
            BehaviorGraphOccurrenceInspector inspector,
            Runnable hiddenListener
    ) {
        super(
                Objects.requireNonNull(owner, "owner"),
                BehaviorGraphOccurrenceInspector.PANEL_TITLE,
                Dialog.ModalityType.MODELESS
        );
        requireEdt();
        this.inspector = Objects.requireNonNull(inspector, "inspector");
        this.hiddenListener = Objects.requireNonNull(
                hiddenListener,
                "hiddenListener"
        );

        setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
        setResizable(true);
        setMinimumSize(new Dimension(MINIMUM_SIZE));
        setContentPane(inspector);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                focusTableWhenOccurrencesArrive = false;
                BehaviorGraphOccurrenceDialog.this.hiddenListener.run();
            }
        });
        getAccessibleContext().setAccessibleName(
                BehaviorGraphOccurrenceInspector.PANEL_TITLE
        );
        getAccessibleContext().setAccessibleDescription(
                "Active system episode event occurrences and details."
        );
    }

    @Override
    public void showForExplicitSelection() {
        requireEdt();
        if (disposed) {
            return;
        }
        applyInitialBounds();
        focusTableWhenOccurrencesArrive = true;
        setVisible(true);
        toFront();
        if (inspector.tableModel().getRowCount() > 0) {
            focusTableWhenOccurrencesArrive = false;
            requestTableFocusLater();
        }
    }

    @Override
    public void occurrencesApplied() {
        requireEdt();
        if (!focusTableWhenOccurrencesArrive) {
            return;
        }
        focusTableWhenOccurrencesArrive = false;
        if (isVisible() && inspector.tableModel().getRowCount() > 0) {
            requestTableFocusLater();
        }
    }

    @Override
    public void dispose() {
        requireEdt();
        if (disposed) {
            return;
        }
        disposed = true;
        focusTableWhenOccurrencesArrive = false;
        super.dispose();
    }

    BehaviorGraphOccurrenceInspector inspector() {
        return inspector;
    }

    static Rectangle initialBounds(
            Rectangle ownerBounds,
            Rectangle usableScreenBounds
    ) {
        Objects.requireNonNull(ownerBounds, "ownerBounds");
        Objects.requireNonNull(
                usableScreenBounds,
                "usableScreenBounds"
        );
        if (usableScreenBounds.width <= 0
                || usableScreenBounds.height <= 0) {
            throw new IllegalArgumentException(
                    "usableScreenBounds must have positive dimensions"
            );
        }

        int width = boundedExtent(
                DEFAULT_SIZE.width,
                MINIMUM_SIZE.width,
                usableScreenBounds.width,
                ownerBounds.width
        );
        int height = boundedExtent(
                DEFAULT_SIZE.height,
                MINIMUM_SIZE.height,
                usableScreenBounds.height,
                ownerBounds.height
        );
        int requestedX = ownerBounds.width > 0
                ? ownerBounds.x + (ownerBounds.width - width) / 2
                : usableScreenBounds.x
                        + (usableScreenBounds.width - width) / 2;
        int requestedY = ownerBounds.height > 0
                ? ownerBounds.y + (ownerBounds.height - height) / 2
                : usableScreenBounds.y
                        + (usableScreenBounds.height - height) / 2;
        int maximumX = usableScreenBounds.x
                + usableScreenBounds.width
                - width;
        int maximumY = usableScreenBounds.y
                + usableScreenBounds.height
                - height;
        return new Rectangle(
                Math.clamp(
                        requestedX,
                        usableScreenBounds.x,
                        maximumX
                ),
                Math.clamp(
                        requestedY,
                        usableScreenBounds.y,
                        maximumY
                ),
                width,
                height
        );
    }

    private void applyInitialBounds() {
        if (initialBoundsApplied) {
            return;
        }
        setBounds(initialBounds(ownerBounds(), usableScreenBounds()));
        initialBoundsApplied = true;
    }

    private Rectangle ownerBounds() {
        Window owner = getOwner();
        return owner == null
                ? new Rectangle()
                : owner.getBounds();
    }

    private Rectangle usableScreenBounds() {
        GraphicsConfiguration configuration = getGraphicsConfiguration();
        if (configuration == null && getOwner() != null) {
            configuration = getOwner().getGraphicsConfiguration();
        }
        if (configuration == null) {
            configuration = java.awt.GraphicsEnvironment
                    .getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice()
                    .getDefaultConfiguration();
        }
        Rectangle bounds = configuration.getBounds();
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(
                configuration
        );
        return new Rectangle(
                bounds.x + insets.left,
                bounds.y + insets.top,
                Math.max(1, bounds.width - insets.left - insets.right),
                Math.max(1, bounds.height - insets.top - insets.bottom)
        );
    }

    private void requestTableFocusLater() {
        SwingUtilities.invokeLater(() -> {
            if (!disposed && isVisible()) {
                inspector.occurrenceTable().requestFocusInWindow();
            }
        });
    }

    private static int boundedExtent(
            int preferred,
            int minimum,
            int usableExtent,
            int ownerExtent
    ) {
        int upperBound = ownerExtent > 0
                ? Math.min(usableExtent, ownerExtent)
                : usableExtent;
        upperBound = Math.max(1, upperBound);
        int lowerBound = Math.min(minimum, upperBound);
        return Math.clamp(preferred, lowerBound, upperBound);
    }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException(
                    "Occurrence dialog changes must run on the Swing EDT"
            );
        }
    }
}

interface BehaviorGraphOccurrenceDialogHandle {

    void showForExplicitSelection();

    void occurrencesApplied();

    boolean isVisible();

    void dispose();
}

@FunctionalInterface
interface BehaviorGraphOccurrenceDialogFactory {

    BehaviorGraphOccurrenceDialogHandle create(
            Window owner,
            BehaviorGraphOccurrenceInspector inspector,
            Runnable hiddenListener
    );
}
