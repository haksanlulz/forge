package forge.gamemodes.net.sharedart;

import com.google.common.io.ByteStreams;
import forge.ImageKeys;
import forge.card.CardEdition;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Limits and checks for sharing custom card art over the multiplayer wire.
 * Pure: no preferences, card database or file roots of its own.
 */
public final class SharedArtPolicy {

    /** Hard cap per picture, in both directions. */
    public static final int MAX_BYTES = 1_500_000;
    /** WireStreamLimits refuses any array over 1 MiB by default, so a picture travels in pieces. */
    public static final int CHUNK_BYTES = 256 * 1024;
    /** Enough pieces for MAX_BYTES and no more. */
    public static final int MAX_CHUNKS = (MAX_BYTES + CHUNK_BYTES - 1) / CHUNK_BYTES;
    /** Longest side accepted from a picture's header. */
    public static final int MAX_SIDE = 4_000;
    /** Shortest side accepted from a picture's header. */
    public static final int MIN_SIDE = 64;
    /**
     * How far from square a picture may be, either way. Cards, planes and
     * battles all fall well inside it, and no card box scales an accepted
     * picture to a side under the three pixels the resampler needs.
     */
    public static final int MAX_ASPECT = 2;
    /** Refused from the header, before any pixel buffer exists. */
    public static final long MAX_PIXELS = 16_000_000L;
    /**
     * A multi-scan JPEG's decoder holds coefficients for the whole image
     * whatever the subsampling, and a progressive one walks all of them once
     * per scan, so such pictures get a lower pixel cap and a scan cap.
     * Multi-scan means progressive, or sequential with fewer than all
     * components in a scan.
     */
    public static final long MAX_PROGRESSIVE_PIXELS = 4_000_000L;
    /** A standard progressive script uses about ten scans; thousands are an attack. */
    public static final int MAX_JPEG_SCANS = 32;
    /** Long side a received picture is drawn at; card scans are about 745x1040. */
    public static final int DECODE_TARGET_SIDE = 1_040;
    /** Decoded pixels one viewer may hold for one game (about 256 MB as 32-bit pixels). */
    public static final long MAX_DECODED_PIXELS_PER_GAME = 64L * 1024 * 1024;
    /** Pictures one player may ask for in one game. */
    public static final int MAX_REQUESTS_PER_GAME = 200;
    /** Bytes one player may take in over one game. */
    public static final long MAX_BYTES_PER_GAME = 64L * 1024 * 1024;
    /**
     * Art shares the game's TCP stream: with one request in flight per player,
     * at most one picture is ever queued ahead of that player's game state.
     */
    public static final int MAX_IN_FLIGHT = 1;
    /** Requests a sharing player lets wait for its disk, whoever sent them. */
    public static final int MAX_OWNER_QUEUE = 16;
    /** Requests a sharing player answers per game, whatever the host asks. */
    public static final int MAX_SERVES_PER_GAME = 4 * MAX_REQUESTS_PER_GAME;
    /** Other players one sharing player can have: a lobby holds at most eight seats. */
    public static final int MAX_VIEWERS = 7;
    /** Times a sharing player sends one picture per game: once for each other player. */
    public static final int MAX_SERVES_PER_KEY = MAX_VIEWERS;
    /** Bytes a sharing player sends per game: every other player's whole budget, and no more. */
    public static final long MAX_SERVED_BYTES_PER_GAME = MAX_VIEWERS * MAX_BYTES_PER_GAME;
    /** The asking side gives up on a request after this long. */
    public static final int REQUEST_TIMEOUT_MS = 60_000;
    /** The host gives up on one sharing player's answer after this long and tries the next. */
    public static final int RELAY_TIMEOUT_MS = 20_000;
    /**
     * Sharing players the host asks for one request before it refuses it, so
     * a request settles inside the asking side's REQUEST_TIMEOUT_MS even when
     * every one of them stays silent.
     */
    public static final int MAX_RELAY_HOPS = 2;
    /** Image keys are a name, a set code and an art index; nothing legitimate comes close. */
    public static final int MAX_KEY_LENGTH = 256;
    /** The synthetic bucket every machine has for loose custom cards. */
    public static final String USER_SET = "USER";

    private static final Pattern SET_CODE = Pattern.compile("[A-Za-z0-9_-]{1,16}");
    private static final Pattern ART_INDEX = Pattern.compile("[0-9]{1,4}");
    private static final Pattern DRIVE = Pattern.compile("[A-Za-z]:");
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    /** What the owner's own data says about a key. No part of the wire string reaches a path. */
    public record Resolution(String setCode, CardEdition.Type setType, String setFolder, File file) { }

    private SharedArtPolicy() {
    }

    /** Name, set and art index of a card key, without the back-face suffix; null for anything else. */
    private static String[] parts(final String key) {
        if (key == null || !key.startsWith(ImageKeys.CARD_PREFIX)) {
            return null;
        }
        String body = key.substring(ImageKeys.CARD_PREFIX.length());
        if (body.endsWith(ImageKeys.BACKFACE_POSTFIX)) {
            body = body.substring(0, body.length() - ImageKeys.BACKFACE_POSTFIX.length());
        }
        final String[] parts = body.split("\\|", -1);
        return parts.length >= 2 ? parts : null;
    }

    public static String cardNameOf(final String key) {
        final String[] parts = parts(key);
        return parts == null ? null : parts[0];
    }

    public static String setCodeOf(final String key) {
        final String[] parts = parts(key);
        return parts == null ? null : parts[1];
    }

    /**
     * Whether a key from the wire is even worth looking up: a card key, short,
     * of exactly a name, a set code and a numeric art index, with no path
     * syntax and no control, format or line-separator characters in it.
     */
    public static boolean isServableKey(final String key) {
        if (key == null || key.length() > MAX_KEY_LENGTH || !key.startsWith(ImageKeys.CARD_PREFIX)) {
            return false;
        }
        for (int i = 0; i < key.length(); i++) {
            final char c = key.charAt(i);
            final int type = Character.getType(c);
            if (c == '\\' || type == Character.CONTROL || type == Character.FORMAT
                    || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR) {
                return false;
            }
        }
        final String body = key.substring(ImageKeys.CARD_PREFIX.length());
        if (body.contains("..") || body.startsWith("/") || DRIVE.matcher(body).lookingAt()) {
            return false;
        }
        final String[] parts = parts(key);
        return parts != null && parts.length == 3 && !parts[0].isEmpty()
                && SET_CODE.matcher(parts[1]).matches() && ART_INDEX.matcher(parts[2]).matches();
    }

    /**
     * One picture's identity: set code, name and art index, the same for
     * every spelling of a servable key that names it. Face-agnostic; null
     * for a key that is not servable.
     */
    public static String artIdOf(final String key) {
        if (!isServableKey(key)) {
            return null;
        }
        final String[] parts = parts(key);
        return artId(parts[1], parts[0], Integer.parseInt(parts[2]));
    }

    /** The art id of a card's name, set code and art index, as {@link #artIdOf(String)} names it. */
    public static String artId(final String setCode, final String name, final int artIndex) {
        return setCode.toUpperCase(Locale.ROOT) + '|' + name.toLowerCase(Locale.ROOT) + '|' + artIndex;
    }

    /** One picture and face: what a request is counted by, so no respelling of a key is a new request. */
    public static String requestIdOf(final String key) {
        final String id = artIdOf(key);
        return id == null ? null : key.endsWith(ImageKeys.BACKFACE_POSTFIX) ? id + ImageKeys.BACKFACE_POSTFIX : id;
    }

    public static boolean isCustomSet(final String code, final CardEdition.Type type) {
        return type == CardEdition.Type.CUSTOM_SET || USER_SET.equals(code);
    }

    /**
     * Whether {@code file} exists and really sits under {@code dir}, after
     * links, junctions and dot segments are resolved. getCanonicalPath does
     * not resolve an NTFS junction, so the real paths are compared.
     */
    public static boolean isContained(final File dir, final File file) {
        try {
            final Path root = dir.toPath().toRealPath();
            final Path real = file.toPath().toRealPath();
            return !real.equals(root) && real.startsWith(root);
        } catch (final IOException | SecurityException | InvalidPathException e) {
            return false;
        }
    }

    /** JPEG or PNG by magic bytes; a file name proves nothing. */
    public static boolean isJpegOrPng(final byte[] bytes) {
        return startsWith(bytes, JPEG) || startsWith(bytes, PNG);
    }

    /**
     * Whether a received JPEG is cheap to decode, from its markers alone: one
     * baseline, extended or progressive Huffman frame (SOF0, SOF1, SOF2) of
     * one or three components, at most MAX_JPEG_SCANS scans, and when it has
     * more than one scan (progressive, or sequential with a scan per
     * component) at most MAX_PROGRESSIVE_PIXELS. A four-component file is CMYK
     * or YCCK, which the reader here does not draw in its colours, and a
     * two-component one it cannot read at all. Arithmetic, lossless and
     * hierarchical frames are refused. Anything that is not a JPEG passes; PNG
     * has no such cost.
     */
    public static boolean isTameJpeg(final byte[] b) {
        if (!startsWith(b, JPEG)) {
            return true;
        }
        int i = 2;
        int scans = 0;
        boolean frame = false;
        boolean progressive = false;
        long pixels = 0;
        int components = 0;
        while (i + 3 < b.length) {
            if ((b[i] & 0xFF) != 0xFF) {
                return false;
            }
            final int marker = b[i + 1] & 0xFF;
            if (marker == 0xFF) { // fill byte
                i++;
                continue;
            }
            if (marker == 0xD9) { // end of image
                break;
            }
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) { // no length field
                i += 2;
                continue;
            }
            final int length = ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
            if (length < 2 || i + 2 + length > b.length) {
                return false;
            }
            if (marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                if (frame || marker > 0xC2 || length < 8) {
                    return false;
                }
                frame = true;
                progressive = marker == 0xC2;
                pixels = (long) (((b[i + 5] & 0xFF) << 8) | (b[i + 6] & 0xFF))
                        * (((b[i + 7] & 0xFF) << 8) | (b[i + 8] & 0xFF));
                components = b[i + 9] & 0xFF; // Nf; length >= 8 keeps this in range
            }
            i += 2 + length;
            if (marker == 0xDA) { // start of scan: skip the entropy-coded data to the next marker
                if (!frame || ++scans > MAX_JPEG_SCANS) {
                    return false;
                }
                while (i + 1 < b.length) {
                    final int next = b[i + 1] & 0xFF;
                    if ((b[i] & 0xFF) == 0xFF && next != 0x00 && (next < 0xD0 || next > 0xD7)) {
                        break;
                    }
                    i += (b[i] & 0xFF) == 0xFF ? 2 : 1;
                }
            }
        }
        // Any multi-scan file (progressive, or sequential with one scan per component) makes the
        // decoder hold the whole image's coefficients, so both get the lower pixel cap.
        final boolean fullBuffer = progressive || scans > 1;
        return frame && scans > 0 && (components == 1 || components == 3)
                && (!fullBuffer || pixels <= MAX_PROGRESSIVE_PIXELS);
    }

    /**
     * A received JPEG rebuilt from the segments a decoder needs to draw it:
     * quantization and Huffman tables, the restart interval, the baseline,
     * extended or progressive frame, and each scan with its entropy-coded
     * data. Every APPn segment (EXIF, ICC profiles, Adobe color transforms),
     * every comment and every other marker segment is dropped, so none of
     * their parsers is reachable from another player's bytes. Anything that
     * is not a JPEG comes back unchanged; a JPEG whose segments do not fit
     * its length gives null. Run after {@link #isTameJpeg}, which bounds the
     * structure.
     */
    public static byte[] stripJpeg(final byte[] b) {
        if (!startsWith(b, JPEG)) {
            return b;
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream(b.length);
        out.write(0xFF);
        out.write(0xD8);
        int i = 2;
        while (i + 1 < b.length) {
            if ((b[i] & 0xFF) != 0xFF) {
                return null;
            }
            final int marker = b[i + 1] & 0xFF;
            if (marker == 0xFF) { // fill byte
                i++;
                continue;
            }
            if (marker == 0xD9) { // end of image
                break;
            }
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) { // stray marker without a length
                i += 2;
                continue;
            }
            if (i + 3 >= b.length) {
                return null;
            }
            final int length = ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
            if (length < 2 || i + 2 + length > b.length) {
                return null;
            }
            final boolean scan = marker == 0xDA;
            if (scan || marker == 0xDB || marker == 0xC4 || marker == 0xDD || (marker >= 0xC0 && marker <= 0xC2)) {
                out.write(b, i, 2 + length);
            }
            i += 2 + length;
            if (scan) { // the entropy-coded data runs to the next marker other than a stuffed zero or a restart
                final int start = i;
                while (i + 1 < b.length) {
                    final int next = b[i + 1] & 0xFF;
                    if ((b[i] & 0xFF) == 0xFF && next != 0x00 && (next < 0xD0 || next > 0xD7)) {
                        break;
                    }
                    i += (b[i] & 0xFF) == 0xFF ? 2 : 1;
                }
                out.write(b, start, Math.min(i, b.length) - start);
            }
        }
        out.write(0xFF);
        out.write(0xD9);
        return out.toByteArray();
    }

    private static boolean startsWith(final byte[] bytes, final byte[] magic) {
        if (bytes == null || bytes.length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (bytes[i] != magic[i]) {
                return false;
            }
        }
        return true;
    }

    /** The file's bytes if it is a JPEG or PNG within the cap, else null. Reads at most one byte past the cap. */
    public static byte[] readImageFile(final File file) {
        try {
            if (file == null || !file.isFile() || file.length() > MAX_BYTES) {
                return null;
            }
            final byte[] bytes;
            try (InputStream in = new FileInputStream(file)) {
                bytes = ByteStreams.toByteArray(ByteStreams.limit(in, MAX_BYTES + 1L));
            }
            return bytes.length <= MAX_BYTES && isJpegOrPng(bytes) ? bytes : null;
        } catch (final IOException | SecurityException e) {
            return null;
        }
    }

    /**
     * The picture a player may send for a key, or null. Only a custom set's
     * picture qualifies, only from that set's own folder under the pictures
     * root, and only as a JPEG or PNG within the cap.
     */
    public static byte[] servableBytes(final String key, final Resolution local, final File picsRoot) {
        if (!isServableKey(key) || local == null || local.setCode() == null || picsRoot == null) {
            return null;
        }
        if (!local.setCode().equalsIgnoreCase(setCodeOf(key)) || !isCustomSet(local.setCode(), local.setType())) {
            return null;
        }
        // An empty or path-shaped folder would widen the check to the whole pictures tree.
        if (local.setFolder() == null || !SET_CODE.matcher(local.setFolder()).matches()) {
            return null;
        }
        if (local.file() == null || !isContained(new File(picsRoot, local.setFolder()), local.file())) {
            return null;
        }
        return readImageFile(local.file());
    }

    /** Total bytes in a reply's pieces; zero for none. */
    public static long total(final byte[][] chunks) {
        long total = 0;
        if (chunks != null) {
            for (final byte[] chunk : chunks) {
                total += chunk == null ? 0 : chunk.length;
            }
        }
        return total;
    }

    /** Pieces small enough for the wire's array bound; null for null. */
    public static byte[][] split(final byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        final int count = (bytes.length + CHUNK_BYTES - 1) / CHUNK_BYTES;
        final byte[][] chunks = new byte[count][];
        for (int i = 0; i < count; i++) {
            chunks[i] = Arrays.copyOfRange(bytes, i * CHUNK_BYTES, Math.min(bytes.length, (i + 1) * CHUNK_BYTES));
        }
        return chunks;
    }

    /**
     * Whether a reply's pieces carry a picture: well-formed pieces within the
     * cap whose first bytes are a JPEG's or PNG's. Copies nothing.
     */
    public static boolean isWellFormed(final byte[][] chunks) {
        if (chunks == null || chunks.length == 0 || chunks.length > MAX_CHUNKS) {
            return false;
        }
        long total = 0;
        for (final byte[] chunk : chunks) {
            if (chunk == null || chunk.length == 0 || chunk.length > CHUNK_BYTES) {
                return false;
            }
            total += chunk.length;
        }
        if (total > MAX_BYTES) {
            return false;
        }
        final byte[] head = new byte[(int) Math.min(PNG.length, total)];
        int at = 0;
        for (final byte[] chunk : chunks) {
            final int n = Math.min(chunk.length, head.length - at);
            System.arraycopy(chunk, 0, head, at, n);
            at += n;
            if (at == head.length) {
                break;
            }
        }
        return isJpegOrPng(head);
    }

    /**
     * The picture a reply carries, or null when it is malformed, over the cap
     * or not a JPEG or PNG. The pieces are checked before anything is copied.
     */
    public static byte[] join(final byte[][] chunks) {
        if (!isWellFormed(chunks)) {
            return null;
        }
        final byte[] bytes = new byte[(int) total(chunks)];
        int at = 0;
        for (final byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, bytes, at, chunk.length);
            at += chunk.length;
        }
        return bytes;
    }

    /**
     * Whether a header's dimensions may be decoded at all: within the side
     * and pixel caps, at least MIN_SIDE a side, and no more than MAX_ASPECT
     * times as long as wide either way. A picture far from a card's shape
     * would be drawn a pixel or less thin in a card box.
     */
    public static boolean isAcceptableSize(final int width, final int height) {
        return width >= MIN_SIDE && height >= MIN_SIDE && width <= MAX_SIDE && height <= MAX_SIDE
                && (long) width * height <= MAX_PIXELS
                && (long) width <= (long) MAX_ASPECT * height && (long) height <= (long) MAX_ASPECT * width;
    }

    /** The subsampling step that brings the long side near DECODE_TARGET_SIDE. */
    public static int subsampling(final int width, final int height) {
        return Math.max(1, (Math.max(width, height) + DECODE_TARGET_SIDE - 1) / DECODE_TARGET_SIDE);
    }
}
