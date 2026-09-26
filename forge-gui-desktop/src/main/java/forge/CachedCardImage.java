package forge;

import java.awt.image.BufferedImage;

import forge.game.card.CardView;
import forge.game.player.PlayerView;
import forge.util.ImageFetcher;
import forge.util.SwingImageFetcher;
import org.tinylog.Logger;

public abstract class CachedCardImage implements ImageFetcher.Callback {
    final CardView card;
    final Iterable<PlayerView> viewers;
    final int width;
    final int height;

    static final SwingImageFetcher fetcher = new SwingImageFetcher();

    public CachedCardImage(final CardView card, final Iterable<PlayerView> viewers, final int width, final int height) {
        this.card = card;
        this.viewers = viewers;
        this.width = width;
        this.height = height;
        if (ImageCache.isSupportedImageSize(width, height)) {
            BufferedImage image = ImageCache.getImageNoDefault(card, viewers, width, height);
            String key = card.getCurrentState().getImageKey(viewers);
            if (image == null) {
                Logger.debug("Fetch due to missing key: " + key + " for " + card);
                fetcher.fetchImage(key, this);
            }
            // A shared custom picture on its way replaces the local one when it lands, as a download would.
            SharedArtImages.callWhenSettled(key, this);
        }
    }

    public BufferedImage getImage() {
        return ImageCache.getImage(card, viewers, width, height);
    }

    public abstract void onImageFetched();
}
