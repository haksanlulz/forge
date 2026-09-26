package forge.gamemodes.net.sharedart;

import forge.ImageKeys;
import forge.StaticData;
import forge.card.CardDb;
import forge.card.CardEdition;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgeConstants;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.util.ImageUtil;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * This machine's side of shared custom art: what it may send, and what it
 * would have to ask for. Resolves keys against this machine's own card data,
 * editions and preferences; nothing from the wire reaches a path.
 */
public final class SharedArtSource {

    private SharedArtSource() {
    }

    /**
     * The picture this player may send for a key, or null. Only a card this
     * player brought to the game qualifies ({@code brought} tests the key), so
     * whoever asks cannot fish for the rest of a custom set. Never throws: a
     * refusal and a failure look the same to the asking side.
     */
    public static byte[] serve(final String key, final Predicate<String> brought) {
        try {
            if (!FModel.getPreferences().getPrefBoolean(FPref.UI_NETPLAY_SHARE_CUSTOM_ART)) {
                return null;
            }
            // First, so an unchecked wire key never reaches the card database.
            if (!SharedArtPolicy.isServableKey(key) || brought == null || !brought.test(key)) {
                return null;
            }
            return SharedArtPolicy.servableBytes(key, resolve(key), new File(ForgeConstants.CACHE_CARD_PICS_DIR));
        } catch (final RuntimeException e) {
            return null;
        }
    }

    /** A card's art id, as {@link SharedArtPolicy#artIdOf(String)} names its key; null for a malformed card. */
    public static String artIdOf(final PaperCard card) {
        if (card == null || card.getName() == null || card.getEdition() == null) {
            return null; // a hostile peer's deserialized card may carry either as null
        }
        return SharedArtPolicy.artId(card.getEdition(), StringUtils.stripAccents(card.getName()), card.getArtIndex());
    }

    /** Adds the art id of every card in a pool. */
    public static void addArtIds(final Iterable<Map.Entry<PaperCard, Integer>> pool, final Set<String> ids) {
        if (pool == null) {
            return;
        }
        for (final Map.Entry<PaperCard, Integer> entry : pool) {
            final String id = artIdOf(entry.getKey());
            if (id != null) {
                ids.add(id);
            }
        }
    }

    /** Whether a deck holds, in any section, the picture a key names. */
    public static boolean deckHolds(final Deck deck, final String key) {
        final String id = SharedArtPolicy.artIdOf(key);
        if (deck == null || id == null) {
            return false;
        }
        for (final Map.Entry<DeckSection, CardPool> section : deck) {
            if (section.getValue() == null) {
                continue;
            }
            for (final Map.Entry<PaperCard, Integer> entry : section.getValue()) {
                if (id.equals(artIdOf(entry.getKey()))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The art ids of a deck's cards that this machine's card database holds
     * in a custom edition of that code. A deck from the wire may name any
     * card in any edition, and a card this machine does not know is one it
     * cannot be playing, so only these count as brought.
     */
    public static Set<String> knownArtIds(final Deck deck) {
        final Set<String> ids = new HashSet<>();
        if (deck == null) {
            return ids;
        }
        for (final Map.Entry<DeckSection, CardPool> section : deck) {
            if (section.getValue() == null) {
                continue;
            }
            for (final Map.Entry<PaperCard, Integer> entry : section.getValue()) {
                final String id = artIdOf(entry.getKey());
                if (id != null && !ids.contains(id) && isKnownInCustomSet(entry.getKey())) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    private static boolean isKnownInCustomSet(final PaperCard card) {
        final CardEdition edition = StaticData.instance().getEditions().get(card.getEdition());
        if (edition == null || !SharedArtPolicy.isCustomSet(edition.getCode(), edition.getType())) {
            return false;
        }
        PaperCard known = StaticData.instance().getCommonCards().getCard(card.getName(), edition.getCode());
        if (known == null) {
            known = StaticData.instance().getVariantCards().getCard(card.getName(), edition.getCode());
        }
        return known != null && edition.getCode().equalsIgnoreCase(known.getEdition());
    }

    /**
     * The keys of every custom-set picture in these decks that this machine
     * lacks: what a viewer asks for when a match starts. Every player already
     * receives every deck in the lobby state, so asking for all of them then
     * says nothing about which card anyone drew or looked at.
     */
    public static List<String> lobbyArtKeys(final Iterable<Deck> decks) {
        final Set<String> keys = new LinkedHashSet<>();
        if (decks != null) {
            for (final Deck deck : decks) {
                if (deck == null) {
                    continue;
                }
                for (final Map.Entry<DeckSection, CardPool> section : deck) {
                    if (section.getValue() == null) {
                        continue;
                    }
                    for (final Map.Entry<PaperCard, Integer> entry : section.getValue()) {
                        addKeys(entry.getKey(), keys);
                    }
                }
            }
        }
        final List<String> wanted = new ArrayList<>();
        for (final String key : keys) {
            if (needsRemote(key)) {
                wanted.add(key);
            }
        }
        return wanted;
    }

    private static void addKeys(final PaperCard card, final Set<String> keys) {
        if (artIdOf(card) == null) {
            return;
        }
        try {
            keys.add(card.getImageKey(false));
            if (card.hasBackFace()) {
                keys.add(card.getImageKey(true));
            }
        } catch (final RuntimeException e) {
            // a malformed card from the wire: nothing to ask for
        }
    }

    /**
     * Whether a key names a custom-set picture this machine lacks. Stock sets
     * are never asked for: for those the viewer's own art and set priority
     * stand, exactly as without shared art. Neither is a card whose name a
     * stock edition prints here, whether this machine holds it in that custom
     * set, holds the set without it, or lacks the set, since no player serves
     * one.
     */
    public static boolean needsRemote(final String key) {
        try {
            if (!SharedArtPolicy.isServableKey(key)) {
                return false;
            }
            final String set = SharedArtPolicy.setCodeOf(key);
            final CardEdition edition = StaticData.instance().getEditions().get(set);
            if (edition != null) {
                if (!SharedArtPolicy.isCustomSet(set, edition.getType())) {
                    return false;
                }
                final PaperCard card = cardOf(key, edition);
                if (card != null ? !isCustomCard(card) : isStockName(SharedArtPolicy.cardNameOf(key))) {
                    return false;
                }
            } else if (isStockName(SharedArtPolicy.cardNameOf(key))) {
                return false;
            }
            final SharedArtPolicy.Resolution local = resolve(key);
            return local == null || local.file() == null || local.setFolder() == null
                    || !SharedArtPolicy.isContained(new File(ForgeConstants.CACHE_CARD_PICS_DIR, local.setFolder()), local.file());
        } catch (final RuntimeException e) {
            return false;
        }
    }

    /**
     * What this machine's own data says about a key: its edition, that
     * edition's picture folder, and the file the local lookup finds. Null when
     * this machine does not hold the card in exactly that edition, or holds a
     * card there whose picture may be a stock scan.
     */
    static SharedArtPolicy.Resolution resolve(final String key) {
        final String set = SharedArtPolicy.setCodeOf(key);
        final CardEdition edition = StaticData.instance().getEditions().get(set);
        if (edition == null) {
            return null;
        }
        final PaperCard card = cardOf(key, edition);
        if (card == null) {
            return null;
        }
        // A stock card reprinted in a custom edition is one the image fetcher downloads into the
        // custom folder, so its picture there may be a stock scan: only custom cards' art is shared.
        if (!isCustomCard(card)) {
            return null;
        }
        final boolean back = key.endsWith(ImageKeys.BACKFACE_POSTFIX);
        final String fileKey = back ? card.getCardAltImageKey() : card.getCardImageKey();
        final File file = fileKey == null ? null : ImageKeys.getImageFile(fileKey);
        String folder = ImageKeys.getSetFolder(card.getEdition());
        if (folder == null || folder.isEmpty()) {
            folder = card.getEdition(); // as ImageUtil.getImageRelativePath does for custom editions
        }
        // The card's own edition, so the policy's set check compares the key with what was found.
        return new SharedArtPolicy.Resolution(card.getEdition(), edition.getType(), folder, file);
    }

    /** The card a key names, in exactly that edition on this machine, or null. */
    private static PaperCard cardOf(final String key, final CardEdition edition) {
        final String cardKey = key.endsWith(ImageKeys.BACKFACE_POSTFIX)
                ? key.substring(0, key.length() - ImageKeys.BACKFACE_POSTFIX.length()) : key;
        final PaperCard card = ImageUtil.getPaperCardFromImageKey(cardKey);
        // The card database falls back to another printing when the set lacks the card.
        return card == null || !card.getEdition().equalsIgnoreCase(edition.getCode()) ? null : card;
    }

    /** Whether an edition that is not a custom set prints a card of this name on this machine. */
    static boolean isStockName(final String name) {
        final CardEdition.Collection editions = StaticData.instance().getEditions();
        for (final CardDb db : new CardDb[]{StaticData.instance().getCommonCards(), StaticData.instance().getVariantCards()}) {
            for (final PaperCard printing : db.getAllCards(name)) {
                final CardEdition edition = editions.get(printing.getEdition());
                if (edition == null || !SharedArtPolicy.isCustomSet(edition.getCode(), edition.getType())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether a card's art can only be custom: its rules are custom, and no
     * edition but a custom set prints its name. A custom script named like a
     * stock card replaces that card's rules for every printing of the name,
     * so custom rules alone do not rule out a stock scan the image fetcher
     * downloaded into a custom set's folder.
     */
    static boolean isCustomCard(final PaperCard card) {
        if (card.getRules() == null || !card.getRules().isCustom()) {
            return false;
        }
        final CardEdition.Collection editions = StaticData.instance().getEditions();
        for (final CardDb db : new CardDb[]{StaticData.instance().getCommonCards(), StaticData.instance().getVariantCards()}) {
            for (final PaperCard printing : db.getAllCards(card.getName())) {
                final CardEdition edition = editions.get(printing.getEdition());
                if (edition == null || !SharedArtPolicy.isCustomSet(edition.getCode(), edition.getType())) {
                    return false;
                }
            }
        }
        return true;
    }
}
