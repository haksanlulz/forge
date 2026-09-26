package forge.gamemodes.net.event;

/**
 * What a peer understands beyond the base protocol, as named tokens.
 *
 * <p>A client sends one as a trailing object in its login frame. Every decoder
 * before this one reads a single object per frame and closes the stream, so an
 * older host never resolves this class. A host answers with its own only to a
 * client that sent one, so neither side sends a class the other may lack.
 *
 * <p>Descriptors on this wire are thin, and a peer reads with its own field
 * layout. Never change this class's fields; extend it by adding tokens.
 */
public final class NetCapabilities implements NetEvent {
    private static final long serialVersionUID = 1L;

    /** ArtRequestEvent / ArtReplyEvent, under the limits in SharedArtPolicy. */
    public static final String SHARED_ART = "shared-art/1";
    /** Sent with SHARED_ART by a player whose SHARE switch is on: only such a player is asked for pictures. */
    public static final String SHARED_ART_SERVE = "shared-art-serve/1";

    private final String[] tokens;

    public NetCapabilities(final String... tokens) {
        this.tokens = tokens == null ? new String[0] : tokens.clone();
    }

    /** What this build supports. */
    public static NetCapabilities local() {
        return new NetCapabilities(SHARED_ART);
    }

    /** What a joining player announces: the protocol, and whether it serves pictures. */
    public static NetCapabilities forLogin(final boolean share) {
        return share ? new NetCapabilities(SHARED_ART, SHARED_ART_SERVE) : local();
    }

    public boolean has(final String token) {
        if (tokens == null || token == null) { // a hostile frame can null the array
            return false;
        }
        for (final String t : tokens) {
            if (token.equals(t)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return "NetCapabilities(" + (tokens == null ? 0 : tokens.length) + " tokens)";
    }
}
