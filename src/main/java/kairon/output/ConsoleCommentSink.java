package kairon.output;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintStream;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class ConsoleCommentSink implements CommentSink {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConsoleCommentSink.class);

    private final PrintStream output;
    private final SpeechDescriptor speechDescriptor;

    public ConsoleCommentSink() {
        this(
                System.out,
                SpeechDescriptor.disabled(null, null)
        );
    }

    public ConsoleCommentSink(PrintStream output) {
        this(output, SpeechDescriptor.disabled(null, null));
    }

    public ConsoleCommentSink(
            PrintStream output,
            SpeechDescriptor speechDescriptor
    ) {
        this.output = Objects.requireNonNull(output, "output");
        this.speechDescriptor = Objects.requireNonNull(
                speechDescriptor,
                "speechDescriptor"
        );
    }

    @Override
    public CompletionStage<CommentDeliveryResult> deliver(String comment) {
        boolean delivered = deliverNow(comment);
        return CompletableFuture.completedFuture(new CommentDeliveryResult(
                speechDescriptor,
                delivered
                        ? ConsoleOutcome.DELIVERED
                        : ConsoleOutcome.FAILED,
                SpeechDeliveryResult.notAttempted(speechDescriptor)
        ));
    }

    @Override
    public SpeechDescriptor speechDescriptor() {
        return speechDescriptor;
    }

    synchronized boolean deliverNow(String comment) {
        if (comment == null || comment.isBlank()) {
            throw new IllegalArgumentException("comment must be nonblank");
        }
        try {
            output.println(comment);
            output.flush();
            boolean delivered = !output.checkError();
            if (!delivered) {
                reportFailure("PrintStreamError");
            }
            return delivered;
        } catch (RuntimeException failure) {
            reportFailure(failure.getClass().getSimpleName());
            return false;
        }
    }

    private static void reportFailure(String cause) {
        String message = "CONSOLE_COMMENT_DELIVERY_FAILED cause=" + cause;
        LOGGER.error(message);
        System.err.println(message);
    }
}
