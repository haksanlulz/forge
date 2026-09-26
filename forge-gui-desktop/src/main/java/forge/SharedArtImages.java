package forge;

import forge.gamemodes.net.sharedart.SharedArtPolicy;
import forge.gamemodes.net.sharedart.SharedArtSession;
import forge.gamemodes.net.sharedart.SharedArtSource;
import forge.util.ImageFetcher;
import forge.util.LogSafe;
import forge.util.ThreadUtil;
import org.tinylog.Logger;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.swing.SwingUtilities;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Desktop side of shared custom art: other players' pictures, decoded under
 * hard bounds off the UI thread and held only in memory for the current game.
 * Mobile does not render them.
 *
 * <p>Pictures are asked for only while a network match view is open, and
 * all at once: every custom-set picture this machine lacks among the decks
 * of the lobby, which every player already receives. Nothing is asked for
 * when a card is first drawn, so the timing of a request says nothing about
 * which card anyone drew or looked at. With SHOW off, or outside a network
 * game with a capable peer, lookup returns null and the local art resolves
 * exactly as before. Turning SHOW off stops shared pictures showing at once,
 * scaled copies included; the decoded ones are kept until the match view
 * closes, so turning SHOW back on shows them again. When the session ends
 * they are dropped.
 *
 * <p>A fallback drawn while a key's picture is on its way is cached like any
 * other; when the picture lands, every scaled copy of the key is dropped, so
 * it replaces the fallback at any size already drawn.
 *
 * <p>Rendering can run off the EDT in network play, so all state here is
 * guarded by LOCK. Repaint callbacks, and the cache invalidation when a
 * picture lands, run on the EDT, after any paint there that cached the local
 * art meanwhile. A card painted off the EDT while its picture lands can still
 * cache the local art after that invalidation; it shows the shared picture
 * once that copy is evicted or the card is drawn at another size.
 */
public final class SharedArtImages {

    private static final Object LOCK = new Object();
    // Guarded by LOCK.
    /** A match view is open: only then is anything asked for. */
    private static boolean inMatch;
    private static boolean listening;
    /** The lobby's pictures were asked for this game. */
    private static boolean lobbyAsked;
    /** Bumped whenever the pictures are dropped, so a decode begun before is thrown away. */
    private static int generation;
    private static final Map<String, BufferedImage> decoded = new HashMap<>();
    private static long decodedPixels;
    /** Keys whose picture was refused, failed, timed out or went over budget this game. */
    private static final Set<String> failed = new HashSet<>();
    /** Keys asked for this game. */
    private static final Set<String> asked = new HashSet<>();
    /** Keys refused because the host had no game running, asked once more this game. */
    private static final Set<String> retried = new HashSet<>();
    /** Keys whose bytes arrived and are being decoded. */
    private static final Set<String> decoding = new HashSet<>();
    /** Keys that rendered shared art, whose scaled copies must go with the pictures. */
    private static final Set<String> shown = new HashSet<>();
    /**
     * Cards waiting on a picture that is on its way, held weakly: a card
     * rebuilt while it waits keeps only its latest image object here.
     */
    private static final Map<String, Set<ImageFetcher.Callback>> waiting = new HashMap<>();
    /** Fast path for the image cache: nothing shared was ever shown since the last drop. */
    private static volatile boolean holding;
    /** SHOW went off with pictures held: the local copies cached meanwhile go when it is back on. */
    private static volatile boolean hidden;

    /** Delay before a key the host refused for want of a game is asked for once more. */
    private static final int RETRY_DELAY_MS = 2_000;

    /** One picture decodes at a time, off the UI thread. */
    private static final ExecutorService DECODER = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "SharedArt-Decode");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private static final SharedArtSession.Listener LISTENER = new SharedArtSession.Listener() {
        @Override
        public void onArtSettled(final SharedArtSession session, final String key, final boolean arrived) {
            final byte[] bytes = arrived ? session.receivedBytes(key) : null;
            // Refused because the host had no game running: the key may be asked for again, so it has not failed.
            final boolean mayRetry = !arrived && !session.hasSeen(key);
            final int gen;
            synchronized (LOCK) {
                gen = generation;
                if (bytes != null) {
                    decoding.add(key); // before discard, so callWhenSettled never sees neither
                }
            }
            session.discard(key); // the decoded copy is the only one kept
            if (bytes == null) {
                settle(gen, key, null, mayRetry);
                if (mayRetry) {
                    ThreadUtil.delay(RETRY_DELAY_MS, () -> askAgain(session, key, gen));
                }
                return;
            }
            DECODER.execute(() -> {
                if (isCurrent(gen)) { // bytes queued before the pictures were dropped are never decoded
                    settle(gen, key, decode(bytes, pixelsLeft()), false);
                } else {
                    synchronized (LOCK) {
                        decoding.remove(key);
                    }
                }
            });
        }

        @Override
        public void onSessionEnded(final SharedArtSession session) {
            dropPictures(true); // the connection closed, maybe without the match view seeing the game end
        }
    };

    private SharedArtImages() {
    }

    /** A network match view opened: the lobby's pictures are asked for, and may arrive until it closes. */
    public static void beginGame() {
        synchronized (LOCK) {
            inMatch = true;
        }
        final SharedArtSession session = SharedArtSession.active();
        if (session != null && session.isShowEnabled()) {
            startAsking(session);
        }
    }

    /**
     * Asks, once per game, for every custom-set picture this machine lacks
     * among the lobby's decks, off the UI thread. Waits for a capable peer:
     * until one answers, the next lookup tries again.
     */
    private static void startAsking(final SharedArtSession session) {
        final int gen;
        synchronized (LOCK) {
            if (!inMatch || lobbyAsked || !session.isPeerCapable()) {
                return;
            }
            if (!listening) {
                SharedArtSession.setListener(LISTENER);
                listening = true;
            }
            lobbyAsked = true;
            gen = generation;
        }
        ThreadUtil.getServicePool().submit(() -> {
            for (final String key : SharedArtSource.lobbyArtKeys(session.lobbyDecks())) {
                if (!ask(session, key, gen)) {
                    return;
                }
            }
        });
    }

    /** Asks for one key; false once the pictures were dropped or the match view closed. */
    private static boolean ask(final SharedArtSession session, final String key, final int gen) {
        synchronized (LOCK) {
            if (gen != generation || !inMatch) {
                return false;
            }
            if (session.hasSeen(key)) {
                return true;
            }
            asked.add(key);
            failed.remove(key); // asked again after a refusal that allowed it, before any reply can mark it
        }
        if (!session.request(key) && session.isPeerCapable() && !session.hasSeen(key)) {
            synchronized (LOCK) {
                if (gen == generation) {
                    failed.add(key); // past the game's budget: the local art is final
                }
            }
        }
        return true;
    }

    /** A key the host refused for want of a running game, asked for once more if the match view is still open. */
    private static void askAgain(final SharedArtSession session, final String key, final int gen) {
        if (session != SharedArtSession.active() || !session.isShowEnabled()) {
            return;
        }
        synchronized (LOCK) {
            if (gen != generation || !retried.add(key)) {
                return;
            }
        }
        ask(session, key, gen);
    }

    private static boolean isCurrent(final int gen) {
        synchronized (LOCK) {
            return gen == generation;
        }
    }

    /**
     * Another player's picture for a card key, or null to use the local one.
     * Never asks for this key in particular: the lobby's pictures are asked
     * for together, when the match view opens or, failing a capable peer then,
     * at the first lookup that finds one.
     */
    static BufferedImage lookup(final String key) {
        final SharedArtSession session = SharedArtSession.active();
        if (session == null) {
            if (holding || hidden) {
                dropPictures(false); // the session ended: local art from here on
            }
            return null; // local art exactly as before
        }
        if (!session.isShowEnabled()) {
            hidePictures(); // SHOW is off: local art until it is back on
            return null;
        }
        if (key == null) {
            return null;
        }
        final boolean askNow;
        synchronized (LOCK) {
            if (!inMatch) {
                return null;
            }
            final BufferedImage image = decoded.get(key);
            if (image != null) {
                shown.add(key);
                holding = true;
                return image;
            }
            askNow = !lobbyAsked;
        }
        if (askNow) {
            startAsking(session); // SHOW went on, or the peer answered, after the match view opened
        }
        return null;
    }

    /**
     * Another player's picture that the desktop cannot draw gives way to the
     * local art for the rest of the game.
     *
     * @return whether a shared picture was held for this key
     */
    static boolean refuse(final String key) {
        synchronized (LOCK) {
            final BufferedImage image = decoded.remove(key);
            if (image == null) {
                return false;
            }
            decodedPixels -= (long) image.getWidth() * image.getHeight();
            shown.remove(key);
            failed.add(key);
            return true;
        }
    }

    /**
     * Whether a cached copy of this key must be resolved afresh: it may be
     * another player's picture that must no longer show (SHOW went off, or
     * the session ended without the game's end reaching this screen), or the
     * local art cached while SHOW was off, for a key whose shared picture is
     * held now that SHOW is back on.
     */
    static boolean dropIfStale(final String key) {
        if (hidden) {
            final SharedArtSession session = SharedArtSession.active();
            if (session != null && session.isShowEnabled()) {
                return revealPictures(key);
            }
        }
        if (!holding) {
            return false;
        }
        synchronized (LOCK) {
            if (!shown.contains(key)) {
                return false;
            }
        }
        final SharedArtSession session = SharedArtSession.active();
        if (session != null && session.isShowEnabled()) {
            return false;
        }
        if (session == null) {
            dropPictures(false);
        } else {
            hidePictures();
        }
        return true;
    }

    /** Calls back when a picture on its way for this key lands, as a download would. */
    static void callWhenSettled(final String key, final ImageFetcher.Callback callback) {
        if (key == null || callback == null) {
            return;
        }
        final SharedArtSession session = SharedArtSession.active();
        if (session == null || !session.isShowEnabled()) {
            return;
        }
        // Pending means on the wire or arrived-not-yet-decoded, never merely asked-for.
        final boolean pending = session.isPending(key) || session.receivedBytes(key) != null;
        final boolean landed;
        synchronized (LOCK) {
            if (!inMatch) {
                return;
            }
            landed = decoded.containsKey(key); // an extra repaint is harmless; a missed one is not
            if (!landed && !failed.contains(key) && (pending || decoding.contains(key))) {
                // CachedCardImage keeps Object's identity equals, so this is a weak identity set.
                waiting.computeIfAbsent(key, k -> Collections.newSetFromMap(new WeakHashMap<>())).add(callback);
            }
        }
        if (landed) {
            SwingUtilities.invokeLater(callback::onImageFetched);
        }
    }

    /** The match view closed: forget the game's pictures and stop asking. */
    public static void endGame() {
        dropPictures(true);
        final SharedArtSession session = SharedArtSession.active();
        if (session != null) {
            session.endGame();
        }
    }

    private static void settle(final int gen, final String key, final BufferedImage image, final boolean mayRetry) {
        synchronized (LOCK) {
            decoding.remove(key);
            if (gen != generation) {
                return; // from before the pictures were dropped
            }
            final Set<ImageFetcher.Callback> callbacks = waiting.remove(key);
            final long pixels = image == null ? 0 : (long) image.getWidth() * image.getHeight();
            if (image == null || decodedPixels + pixels > SharedArtPolicy.MAX_DECODED_PIXELS_PER_GAME) {
                if (image != null || !mayRetry) {
                    failed.add(key); // refused, failed, timed out or over budget: the local art stays, silently
                }
                return;
            }
            decodedPixels += pixels;
            decoded.put(key, image);
            // Posted under LOCK, so it runs before the repaint of any card that finds the picture landed,
            // and on the EDT, after any paint there that cached the local art while this picture decoded.
            SwingUtilities.invokeLater(() -> landed(key, callbacks));
        }
    }

    /** On the EDT: the local art cached for a key gives way to its picture, and the cards waiting repaint. */
    private static void landed(final String key, final Set<ImageFetcher.Callback> callbacks) {
        ImageCache.invalidateScaled(key);
        if (callbacks == null) {
            return;
        }
        for (final ImageFetcher.Callback callback : new ArrayList<>(callbacks)) {
            try {
                callback.onImageFetched();
            } catch (final RuntimeException e) {
                Logger.debug("Shared art repaint failed: {}", LogSafe.forLog(e.toString()));
            }
        }
    }

    private static long pixelsLeft() {
        synchronized (LOCK) {
            return SharedArtPolicy.MAX_DECODED_PIXELS_PER_GAME - decodedPixels;
        }
    }

    /** SHOW went off: stop showing shared pictures now; the decoded copies stay until the match view closes. */
    private static void hidePictures() {
        final List<String> stale;
        synchronized (LOCK) {
            if (decoded.isEmpty()) {
                return; // nothing held, so nothing to hide or to show again later
            }
            hidden = true;
            stale = new ArrayList<>(shown);
            shown.clear();
            holding = false;
        }
        for (final String k : stale) {
            ImageCache.invalidateScaled(k);
        }
    }

    /**
     * SHOW is back on: the local copies cached while it was off give way to
     * the pictures still held.
     *
     * @return whether {@code key} is one of them
     */
    private static boolean revealPictures(final String key) {
        final List<String> keys;
        synchronized (LOCK) {
            hidden = false;
            keys = new ArrayList<>(decoded.keySet());
        }
        for (final String k : keys) {
            ImageCache.invalidateScaled(k);
        }
        return keys.contains(key);
    }

    private static void dropPictures(final boolean endMatch) {
        final Set<String> stale;
        synchronized (LOCK) {
            generation++;
            stale = new HashSet<>(shown);
            if (endMatch) {
                inMatch = false;
                lobbyAsked = false;
                // Fallbacks cached for these are resolved afresh next game, which may bring their pictures.
                stale.addAll(failed);
                stale.addAll(asked);
                asked.clear();
                retried.clear();
            }
            shown.clear();
            decoded.clear();
            failed.clear();
            decoding.clear();
            waiting.clear();
            decodedPixels = 0;
            holding = false;
            hidden = false;
        }
        for (final String key : stale) {
            ImageCache.invalidateScaled(key);
        }
    }

    /**
     * Decodes a received picture, or returns null. JPEG structure is checked
     * from the markers and the dimensions from the header before any pixel
     * buffer exists, so neither an image bomb nor a costly multi-scan file is
     * decoded, nor a picture far from a card's shape; a JPEG reaches the
     * reader rebuilt without its metadata segments. Large pictures decode
     * subsampled, and the result is refused if it would not fit in
     * {@code maxPixels}. The result is always a compact 32-bit or smaller
     * image, whatever the source's sample depth. Nothing is cached to disk on
     * the way.
     */
    static BufferedImage decode(final byte[] received, final long maxPixels) {
        if (received == null || received.length > SharedArtPolicy.MAX_BYTES || !SharedArtPolicy.isJpegOrPng(received)
                || !SharedArtPolicy.isTameJpeg(received)) {
            return null;
        }
        final byte[] bytes = SharedArtPolicy.stripJpeg(received);
        if (bytes == null) {
            return null;
        }
        // MemoryCacheImageInputStream: ImageIO.createImageInputStream may cache to a temp file.
        try (ImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            final Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                return null;
            }
            final ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                final int width = reader.getWidth(0);
                final int height = reader.getHeight(0);
                if (!SharedArtPolicy.isAcceptableSize(width, height)) {
                    return null;
                }
                final int step = SharedArtPolicy.subsampling(width, height);
                final long outPixels = (long) ((width + step - 1) / step) * ((height + step - 1) / step);
                if (outPixels > maxPixels) {
                    return null;
                }
                final ImageReadParam param = reader.getDefaultReadParam();
                if (step > 1) {
                    param.setSourceSubsampling(step, step, 0, 0);
                }
                return compact(reader.read(0, param));
            } finally {
                reader.dispose();
            }
        } catch (final IOException | RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    /** 16-bit and custom rasters would double or quadruple the footprint the pixel budget counts. */
    private static BufferedImage compact(final BufferedImage raw) {
        switch (raw.getType()) {
            case BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_INT_RGB, BufferedImage.TYPE_INT_BGR,
                 BufferedImage.TYPE_3BYTE_BGR, BufferedImage.TYPE_4BYTE_ABGR, BufferedImage.TYPE_BYTE_GRAY:
                return raw;
            default:
                final BufferedImage out = new BufferedImage(raw.getWidth(), raw.getHeight(), BufferedImage.TYPE_INT_ARGB);
                final Graphics2D g = out.createGraphics();
                try {
                    g.drawImage(raw, 0, 0, null);
                } finally {
                    g.dispose();
                }
                return out;
        }
    }
}
