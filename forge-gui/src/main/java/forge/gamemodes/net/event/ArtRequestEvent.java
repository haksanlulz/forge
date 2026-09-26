package forge.gamemodes.net.event;

import forge.util.LogSafe;

/**
 * Asks for a card picture by image key. Sent only between peers that
 * negotiated {@link NetCapabilities#SHARED_ART}.
 */
public final class ArtRequestEvent implements NetEvent {
    private static final long serialVersionUID = 1L;

    private final int requestId;
    private final String imageKey;

    public ArtRequestEvent(final int requestId, final String imageKey) {
        this.requestId = requestId;
        this.imageKey = imageKey;
    }

    public int getRequestId() {
        return requestId;
    }

    public String getImageKey() {
        return imageKey;
    }

    /** The key is remote text, and every sent event is logged. */
    @Override
    public String toString() {
        return "ArtRequest #" + requestId + " " + LogSafe.forLog(imageKey, 120);
    }
}
