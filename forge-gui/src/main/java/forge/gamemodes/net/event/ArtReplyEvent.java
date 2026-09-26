package forge.gamemodes.net.event;

import forge.gamemodes.net.sharedart.SharedArtPolicy;

import java.io.Externalizable;
import java.io.IOException;
import java.io.InvalidClassException;
import java.io.ObjectInput;
import java.io.ObjectOutput;

/**
 * Answers an {@link ArtRequestEvent} with the picture's bytes, or with none
 * when the owner refused or had nothing to send. Sent only between peers that
 * negotiated {@link NetCapabilities#SHARED_ART}.
 *
 * <p>Externalizable so the reader checks every declared length against the
 * picture cap before allocating it; default serialization would allocate each
 * piece at whatever length the sender declared. A refused frame surfaces as
 * InvalidClassException, which the decoder drops without closing the channel.
 */
public final class ArtReplyEvent implements NetEvent, Externalizable {
    private static final long serialVersionUID = 1L;

    private int requestId;
    private String imageKey;
    /** Pieces of at most SharedArtPolicy.CHUNK_BYTES; null when refused or unavailable. */
    private byte[][] chunks;

    /** For deserialization only. */
    public ArtReplyEvent() {
    }

    public ArtReplyEvent(final int requestId, final String imageKey, final byte[][] chunks) {
        this.requestId = requestId;
        this.imageKey = imageKey;
        this.chunks = chunks;
    }

    public int getRequestId() {
        return requestId;
    }

    public String getImageKey() {
        return imageKey;
    }

    public byte[][] getChunks() {
        return chunks;
    }

    @Override
    public void writeExternal(final ObjectOutput out) throws IOException {
        out.writeInt(requestId);
        out.writeUTF(imageKey == null ? "" : imageKey);
        if (chunks == null) {
            out.writeInt(-1);
            return;
        }
        out.writeInt(chunks.length);
        for (final byte[] chunk : chunks) {
            out.writeInt(chunk.length);
            out.write(chunk);
        }
    }

    @Override
    public void readExternal(final ObjectInput in) throws IOException {
        requestId = in.readInt();
        imageKey = in.readUTF();
        if (imageKey.length() > SharedArtPolicy.MAX_KEY_LENGTH) {
            throw refused("key too long");
        }
        final int count = in.readInt();
        if (count < 0) {
            chunks = null;
            return;
        }
        if (count == 0 || count > SharedArtPolicy.MAX_CHUNKS) {
            throw refused("piece count " + count);
        }
        chunks = new byte[count][];
        long total = 0;
        for (int i = 0; i < count; i++) {
            final int length = in.readInt();
            total += length;
            if (length <= 0 || length > SharedArtPolicy.CHUNK_BYTES || total > SharedArtPolicy.MAX_BYTES) {
                throw refused("piece of " + length + " bytes");
            }
            chunks[i] = new byte[length];
            in.readFully(chunks[i]);
        }
    }

    private static InvalidClassException refused(final String why) {
        return new InvalidClassException(ArtReplyEvent.class.getName(), why + ", over the shared-art cap");
    }

    /** Never dumps the bytes: every sent event is logged. */
    @Override
    public String toString() {
        if (chunks == null) {
            return "ArtReply #" + requestId + " (refused)";
        }
        return "ArtReply #" + requestId + " (" + SharedArtPolicy.total(chunks) + " bytes)";
    }
}
